---
name: kb_bot_zakum_prequest
description: Bot Zakum prequest driver (100200 approval + 100201 trials -> Eyes of Fire) - server requirements, PQ/reactor mechanics, driver design
metadata:
  node_type: memory
  type: project
---

# Zakum prequest bot driver (BotZakumPrequestManager)

## 1. What this server actually requires

`USE_ENABLE_SOLO_EXPEDITIONS: false` (config.yaml), so the prequests are NOT skipped. The altar
entrance portal (`scripts/portal/Zakum05.js` on 211042300 `ps00`) requires: quest **100200**
started-or-completed, quest **100201** completed, `haveItem(4001017)` (Eye of Fire), and door
reactor 2118002 in state 0. The expedition itself (`ExpeditionType.ZAKUM(6, 30, 50, 255, 5)` =
minSize 6, maxSize 30, level 50-255, 5 min registration) is **out of the driver's scope** — the
driver only makes a bot *eligible* (approval + trials + 5 Eyes held).

**100200/100201 are custom, script-only quests — no WZ data.** `Quest.getInstance` synthesizes an
empty quest and `forceStart`/`forceComplete` work exactly as the NPC scripts' `cm.startQuest`/
`cm.completeQuest` do. Gotcha: `QuestStatus.questID` is a `short`, so they persist in `queststatus`
as **-30872 / -30871**; every read path truncates identically, so it's self-consistent — but always
go through `Quest.getInstance`, never store the raw int.

- **100200** ("council approval"): started by ONE talk with the bot's class chief in El Nath
  Chief's Residence (map 211000001) at level 50+ — Tylus 2020008 (warrior), Robeira 2020009
  (magician), Rene 2020010 (bowman), Arec 2020011 (thief), Pedro 2020013 (pirate); Cygnus/Aran
  branches fold onto the same five via `jobBase % 10`. Never completed, started is enough.
- **100201** ("the trials"): completed only by Adobis (2030008, map 211042300) on handing over
  **4031061** (Breath of Fire) + **4031062** (Breath of Lava) + **30x 4000082** (Zombie's Lost
  Gold Tooth); consumes all three, grants **5x 4001017** (Eye of Fire).

## 2. The three trial stages (all via Adobis at 211042300)

**Stage 1 — Zakum PQ ("Unknown Dead Mine", event `ZakumPQ`, instance maps 280010000-280011006).**
`minPlayers = 1` — a solo party works; single lobby per channel (`maxLobbies=1`), 30-min timer.
Inside the instanced maze:
- 7 key chests (reactors **2112011** x2 @ 280010041/280011005, **2112004** x5 @ 280010091/
  280010110/280010140/280011002/280011003), each a plain hit-to-break dropping one **4001016**
  key (reactordrops chance=1 = always). Exactly 7 chests = exactly the 7 keys needed.
- The Giant Chest (reactor **2112014** @ 280011005) is a type-100 item-trigger reactor: it opens
  when ONE dropped MapItem of **exactly quantity 7** of 4001016 lands inside its trigger box
  (lt(-78,-67)..rb(29,25) around the reactor). `MapleMap.searchItemReactors` matches
  `reactQty == item.getQuantity()` — a 7-stack drop, not 7 single drops — then schedules
  `ActivateItemReactor` **5s later**, which consumes the drop and breaks the chest, dropping
  **4001018** Fire Ore owned by the dropper's client.
- Turn-in: Aura (**2032002**, 280010000) consumes the ore, `giveEventPlayersExp(12000)`,
  `clearPQ()`; each member then claims **4031061** via the grid. Optional documents path (30x
  4001015 from reactors 2112005/2112012) additionally grants 5x Wheel of Destiny — the bot driver
  SKIPS documents (30 more reactor breaks for a side reward).
- 4001015/4001016/4001018 are event-exclusive items — auto-removed when leaving the instance.
- Maze topology: hub 280010000 fans into in01..in15; only **280010071** (via in07) and
  **280010101** (via in10) lead on to the second hub 280011000, whose in05 is the chest room
  280011005. All inter-map portals are normal (pt=1, no scripts) and baked in the world graph;
  the maze is a portal ISLAND (entry only via `em.startInstance`, exit via NPC/portal script).

**Stage 2 — Breath of Lava (maps 280020000 -> 280020001, NOT instanced, no party).** Adobis
(selection 1, gated `haveItem(4031061) && !haveItem(4031062)`) warps to 280020000; one plain
`east00` portal reaches 280020001 where Lira (**2032003**) grants **4031062** + 10k exp and warps
back. 280020000 is a lava jump course with 3 Firebombs; 280020001 has no mobs. Amon (2030010) is
the give-up exit on both. `fieldLimit=402046` blocks jump-down/mounts/migration but NOT plain
jumps. Entry exists ONLY via the NPC warp — no graph edge, and none is needed (the driver warps
like the script does; in-course routing uses the baked 280020000->280020001 edge).

**Stage 3 — teeth.** 4000082 drops from **Miner Zombie 5130108 only** (drop_data chance 20000 =
2%, questid 0 = unconditional), maps **211041500-211041800** (Dead Mine chain). 30 needed ->
~1500 kills expected, the long tail of the errand.

Reachability: the whole El Nath dungeon chain (Sharp Cliff -> Dead Mine -> 211042300) is a portal
island entered via the existing Jeff `TaxiEdge(211040200, 2030000, 211040300, ...)` in
`BotWorldGraph` — 211042300 and the tooth maps were already routable before this driver.

## 3. Driver design (mirrors the Temple driver)

`BotZakumPrequestManager`, a `BotAutopilotManager.DetourErrand` registered between job-change and
Temple (its ambition band is lower, so it wins ties until done). Stateless step resolution each
tick (`resolveStep`: APPROVAL -> PQ -> LAVA -> TEETH -> TURNIN -> DONE) from quest state +
inventory; script-only NPC effects are reproduced with the scripts' exact server calls.

- **Opt-in / stagger**: `BotPersonality.zakumAmbitionLevel()` — stable per-bot roll in [70,120],
  same splitmix64-avalanche discipline as the Temple trait but a distinct salt (independent, not
  rank-correlated). Kill switch `BotManager.cfg.ZAKUM_PREQUEST`.
- **PQ leg**: party-of-one via `Party.createParty` (declines to run while in a >1 social party);
  `em.getEligibleParty` + `em.startInstance(party, map, 1)` exactly as `2030008.js`. Inside the
  instance the errand ALWAYS consumes the tick — the maze is unroutable for the normal grind flow
  (its only "escape" would be a return scroll, forfeiting the run). Key-room order is a fixed
  list; loot-first (any floor key beats another hit); the 7-stack drop at the Giant Chest sets
  `zakumPqChestDropAtMs`, which (a) keeps the bot in the chest phase while its key count is 0 and
  (b) suppresses `BotLootEligibility` from re-looting the bot's own pending stack. Stall (4 min
  no progress) or unrecoverable state -> warp to 211042300 (the same exit warp every Zakum-side
  NPC dialog performs) + jittered back-off, fresh instance later.
- **LOD**: `BotManager.lod1MotionPlanCovered` now excludes `BotZakumPrequestManager.inLiveMaps`
  (PQ maze + lava course) — instance-blind timed-warp travel / motion-plan lerps must not fire
  there. The TEETH grind phase stays LOD-eligible like any grind.
- **Lava leg**: `tickApproachNpc(280020001, Lira)` does both the portal hop and the jump-course
  walking. 3 stalled attempts -> 2-4h step-aside (a bot whose nav can't climb the course must not
  enter/give-up loop forever). Whether the baked graph can actually climb the course was NOT
  live-verified — watch a bot's first attempt via /api/botdebug.
- **Teeth leg**: Temple-lane-style pin (occupancy crowd-defer across the four maps, seconds-cadence
  scan, never a full advisor pass on the tick thread); kills/loot accrue through the normal flow.
- **Item protection**: script-only quest items are invisible to the WZ-driven quest-item guard, so
  `collectSellTrashEtcItems` now consults `isQuestCriticalItem` on BOTH drivers (Zakum: teeth/
  keys/ore until 100201 done, Eyes of Fire forever; Temple: the six Force Field materials until
  3521 done — a latent bug fixed retroactively, see [[kb_bot_temple_of_time]]). Debug ladder
  reason: `errand-quest-critical`.

## 4. Limitations / watch-list

- The Eye of Fire is granted x5 and kept forever; actually USING one (running the expedition) is
  future work — see docs/bot/ROADMAP.md if/when that becomes a wanted feature.
- If a human party is mid-ZakumPQ on the channel, `startInstance` fails and the bot backs off —
  bots share the single lobby with players on equal terms.
- The lava jump course and full in-instance nav were verified structurally (portals/graph), not
  live; first live run worth watching end to end.
