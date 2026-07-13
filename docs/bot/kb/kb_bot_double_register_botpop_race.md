---
name: kb_bot_double_register_botpop_race
description: "botpop-on spawns DUPLICATE BotEntry per char (two tick tasks → one char thrashing between 2 positions, grindadvisor reports twice on 2 maps). Cause = non-atomic register racing overlapping fast-start sweeps."
metadata: 
  node_type: memory
  type: project
  originSessionId: fadf3125-00d6-4195-8516-a7b4fec80417
---

Symptom (reported 2026-06-20): on server restart + `@botpop` on, some bots thrash between two
positions/states (client sees teleporting between instances) and "heading to X to farm" is
announced TWICE on different maps for one bot. Also implicated downstream: bots stuck idle/grinding
in town after hours (two autopilot brains install conflicting plans → neither converges), and a
shutdown NPE (`InventoryManipulator.equip` chr null via `BotOfferManager.offerBestGearToSibling`
→ a stale duplicate/torn-down entry).

Root cause: TWO `BotEntry` tick tasks driving ONE character. `BotScheduler.kickFastStart()` (fires
on `setEnabled(true)` i.e. botpop-on) schedules ~30 `sweep()` calls across POPULATION_FASTSTART_MS
onto the MULTI-WORKER `TimerManager` pool. Each sweep → `reconcile` → `spawnManagedBot(charId)`.
Both `spawnManagedBot`'s `getEntryByBotCharId==null` check AND `registerBotInternal`'s dedup sweep
(removeIf same botCharId across all owner lists) were check-then-act / non-atomic: two workers
spawn the same id concurrently, each sweeps BEFORE the other adds, neither removes the other →
both entries + tasks survive. The `registerBotInternal` ponytail comment literally predicted this
race ("not atomic vs a truly concurrent register of the same id — add per-bot locking only if that
race is ever observed"). Steady-state single-timer sweep can't overlap itself, so it ONLY triggers
at botpop-on (the burst).

Fix (experimental, 2026-06-20):
- `BotManager.spawningBotIds` (ConcurrentHashMap.newKeySet): `spawnManagedBot` does
  `if(!spawningBotIds.add(charId)) return false;` then re-checks `getEntryByBotCharId`, finally
  removes. Stops two workers both loading (double PlayerStorage add) + registering the same id.
- `BotManager.registryLock`: `registerBotInternal` builds the entry OFF-lock (personality/scroll/
  graph IO must not serialize the boot spawn storm), then does the dedup-sweep + add + followOffset
  recompute INSIDE `synchronized(registryLock)` — SSOT atomic publish covering ALL register paths
  (managed spawn, owned spawn, takeover), not just botpop.
- `BotChatManager.checkBotStatus`: added liveness guard `if (bot.getMap()==null ||
  !bot.isLoggedinWorld()) return;` — it runs from a delayed/periodic scheduled task (not the tick),
  so the bot can be torn down (shutdown/logout) between schedule and fire → the equip-path NPE.

NOT yet fixed (latent, re-test botpop first — likely duplicate artifacts): grinding=true +
autopilotMapId=-1 zombie-grind in mobless town bypasses `maybeRecoverInertAutopilot` (gated on
!grinding); `owner-supply-grace` livelock (broke self-owned crew all wait for a pot-share that
never lands, blocking their own resupply errand — only surfaced in logs AT shutdown, 0 during the
idle hours). Related: [[kb_bot_self_owned_owner_assumptions]],
[[kb_bot_cold_decide_gc_storm]].
