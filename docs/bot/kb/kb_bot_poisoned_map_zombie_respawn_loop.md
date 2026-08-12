---
name: kb_bot_poisoned_map_zombie_respawn_loop
description: "Disposed Character ghost left in MapleMap.mapobjects poisons the map (every addPlayer NPEs on its null inventory) → every bot spawned there becomes a tickless midair zombie, evicted + respawned forever. Cause = async disconnect racing changeMapInternal. Companion: canWarpMap latch leak silently freezes a bot's warps after ONE failed changeMap."
metadata:
  node_type: memory
  type: project
---

Symptom (reported 2026-08-12): bots (Rebel17/Judicial/WeeklyCovert, all saved on map 800000000)
float midair doing nothing, get auto-evicted by the zombie sweep, then reappear minutes later and
stick again — forever. `BotScheduler - bot population sweep failed / NullPointerException:
"this.inventory" is null` every sweep at `MapleMap.sendObjectPlacement → sendSpawnData →
addCharEquips`. Separately, bots standing still in town with a travel destination set
(ignoretakes at the Mu Lung portal for 216s+, `!reach` NPEs for a real player on the same map).

Two distinct root causes, one cascade:

1. POISONED MAP. A disconnect (`Client.disconnectInternal`, async via ThreadManager — bots arm it
   3-8s AFTER `finishLoggingOut` while their tick keeps running) can race the tick's
   `changeMapInternal`: the tick passes the player-storage check (Character.java ~1860), the
   disconnect's `Client.removePlayer` removes from the OLD map, then the tick's `addPlayer` lands
   the char on the NEW map after which the disconnect removes it from player storage and calls
   `clear()`/`empty(true)`. Five minutes later `empty`'s delayed runnable nulls
   `inventory/map/client` — and the disposed instance is still in the new map's
   `mapobjects`/`characters`. From then on EVERY `addPlayer` on that map NPEs serializing the
   ghost's equips: `changeMap` into it fails (players AND bots), and every managed-bot spawn whose
   saved map it is fails.
2. ZOMBIE LOOP. `loadOfflineBot` added the char to channel+world player storage BEFORE
   `spawnMap.addPlayer`; when addPlayer threw, the char stayed online with no registry entry — a
   tickless zombie floating at spawn (no physics ticks). The zombie sweep evicts it, the scheduler
   (`cohereCrews`/`bringOnline`) respawns it into the same NPE, repeat every ~3 min.
3. FROZEN WARPS (companion bug). `Character.changeMap`'s `canWarpCounter++ / -- / canWarpMap`
   dance was not exception-safe: ONE `changeMapInternal` throw leaks the counter, `canWarpMap`
   sticks false, and every later warp for that character silently returns at the
   `if (!canWarpMap)` guard — the bot walks to portals, fires `enterPortal`, and never leaves the
   map (travel gives up with `warp-no-land`). No log line at any point.

Fixes (dev, 2026-08-12):
- `Character.changeMap`/`forceChangeMap`: counter decrement + `canWarpMap` restore moved into
  `finally`.
- `Character.changeMapInternal`: after `addPlayer`, re-check player storage; if the char vanished
  mid-warp, unwind with `map.removePlayer` (logs "disconnected mid-warp"). Narrows the race; the
  sweep below catches the residual window.
- `BotManager.loadOfflineBot`: failed `spawnMap.addPlayer` unwinds map+channel+world registration
  and rethrows — a failed spawn no longer creates a zombie. `spawnManagedBot` catches
  RuntimeException too, so one unspawnable bot doesn't abort the whole population sweep.
- `BotManager.sweepGhostPlayerObjects` (runs with the 60s zombie sweep): purges any PLAYER map
  object whose instance is not the one in channel player storage (identity compare, 90s grace,
  `MapleMap.removeStalePlayer`) — self-heals a poisoned map within ~2 min instead of requiring a
  restart, whatever the leak path.

Diagnosis trail: `/api/bot/pathlog` showed the frozen bot GROUNDED 4px from the portal with
`goal=[move-target]` pinned and `Stuck: YES` — i.e., travel executing but warps no-oping.
Related: [[kb_bot_double_register_botpop_race]] (the other spawn race),
[[kb_bot_logout_loiter_unreachable_anchor]] (logout-linger travel is what puts a logging-out bot
mid-warp in the first place).
