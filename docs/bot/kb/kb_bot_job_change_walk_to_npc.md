---
name: kb_bot_job_change_walk_to_npc
description: Bot job advancement walks to a verified instructor NPC for ALL explorer tiers (1st-4th); per-tier branch→NPC→map SSOT tables (2nd job = distinct 1072xxx FIELD instructors, NOT the 1st-job NPC); scripted hidden-street portal traversal for Grendel/Athena/Leafre; re-trigger reconciliation on interrupt/relog; JOB_CHANGE_FALLBACK_ANYWHERE config (default off)
metadata: 
  node_type: memory
  type: reference
  originSessionId: 3ba2e094-4330-46cc-ad73-ebfae4332dae
---

Autopilot/ownerless bots no longer change job instantly-anywhere — they walk to a verified instructor NPC, then advance on arrival, with grinding suppressed en route (anti-overlevel). Original 1st/2nd commit `4d727c000`; 3rd/4th tiers + fallback config added later (experimental branch).

**Routing now covers ALL explorer tiers**, keyed by branch = `id/100` (1..5) and tier = `id%10` (0=1st/2nd, 1=3rd, 2=4th); within tier 0, `id%100==0` is 1st job and the rest are 2nd. Four SSOT tables in `BotStarterKitManager`, all verified vs Map.wz life data + handbook/NPC.txt. `jobChangeNpcFor(Job)` switches on tier (and 1st-vs-2nd within tier 0); `routesThroughNpc(j) = jobChangeNpcFor(j)!=null`.

1st job — `FIRST_JOB_NPC` (town instructor):
- 1 Warrior → 1022000 Dances with Balrog @ 102000003 (Perion)
- 2 Magician → 1032001 Grendel the Really Old @ 101000003 (Ellinia) — scripted entrance, see below
- 3 Bowman → 1012100 Athena Pierce @ 100000201 (Henesys) — scripted entrance, see below
- 4 Thief → 1052001 Dark Lord @ 103000003 (Kerning)
- 5 Pirate → 1090000 Kyrin @ 120000101 (Nautilus) — Kyrin also in 912010200 (special instance), use 120000101.

2nd job — `SECOND_JOB_NPC`, the DISTINCT field "Job Instructor" NPCs (1072xxx). **CORRECTION of an earlier wrong assumption:** 2nd job is NOT the same NPC as 1st, and the 1072xxx are NOT in unreachable test maps — they're in plain-reachable FIELD maps (BFS-verified reachable from all hubs). Commit `28b129a94`:
- 1 Warrior → 1072000 @ 102020300 (West Rocky Mountain IV)
- 2 Magician → 1072001 @ 101020000 (Forest North of Ellinia)
- 3 Bowman → 1072002 @ 106010000 (Road to the Dungeon)
- 4 Thief → 1072003 @ 102040000 (Construction Site N of Kerning)
- 5 Pirate → 1090000 Kyrin @ 120000101 (reuses 1st-job Kyrin)

3rd job — `THIRD_JOB_NPC_BY_BRANCH`, same NPC **1061009 "Door of Dimension"** placed in each branch's hidden dungeon map:
- 1 Warrior → 105070001 (Ant Tunnel Park)
- 2 Magician → 100040106 (Forest of Evil II)
- 3 Bowman → 105040305 (Sleepy Dungeon V)
- 4 Thief → 107000402 (Monkey Swamp II)
- 5 Pirate → 105070200 (Cave of Evil Eye II)

4th job — `FOURTH_JOB_NPC_BY_BRANCH`, per-branch master, all in Leafre Forest of the Priest **240010501**:
- 1 Warrior → 2081100 Harmonia
- 2 Magician → 2081200 Gritto
- 3 Bowman → 2081300 Legor
- 4 Thief → 2081400 Hellin
- 5 Pirate → 2081500 Samuel

**Fallback is now a config, default OFF.** `BotManager.cfg.JOB_CHANGE_FALLBACK_ANYWHERE` (false). When OFF the bot stays COMMITTED and keeps retrying forever (never drops back to grinding/autopilot — `tickJobErrand` returns true on NPC_GONE/TRAVEL_YIELDED), logging a throttled `log.error` WITH reachability (`BotWorldGraph.route(botMap, instructorMap, MAX_TRAVEL_HOPS) != null`, throttle `jobErrandLastWarnMs` every 30s). When ON, a NO-PROGRESS deadline (`ERRAND_NO_PROGRESS_MS` 90s) force-advances on the spot via `forceAdvance` — see the shared [[kb_bot_errand_progress_ssot]] (`BotTravelManager.ErrandProgress`), which makes the deadline boat/long-travel safe (refreshes on hop + active travel, so a 30-min cross-continent route with boat waits never trips it; only a genuine single-map wedge does). Only autopilot bots route; supervised (owner-following) advance instantly. Dispatch in `BotBuildManager.scheduleAutoAdvance` keys off `jobChangeNpcFor != null`, so adding tiers needed no caller change.

`!botstatus` reports the errand: "going to <town> to job advance" (or "walking to the instructor to job advance" once on the map) — see `BotAutopilotManager.statusReport`.

**Scripted hidden-street entrances (1st-job Magician/Bowman + 4th-job Leafre).** Grendel (101000003), Athena (100000201), and the Leafre 4th-job room (240010501) sit behind SCRIPTED portals (tm=999999999) the WZ scan can't follow — so they're forward-unreachable islands (routing said unreachable; even after a graph edge, the portal-finder matches `tm==dest` and skips scripted portals, so traversal failed too: "route-reachable=true but stuck"). Fix (commits `a299cc36b` graph-edge, superseded by `fb2916abe` table+traversal): `BotWorldGraph.SCRIPTED_ENTRANCES` SSOT `{fromMap, portalName, destMap}` — (1) adds the routing edge AND (2) `scriptedEntrancePortal(from,dest)` resolves the live portal by name in `BotTravelManager.adjacentOrScriptedPortal`, so the bot treats it as a normal portal to dest. It walks there and `GenericPortal.enterPortal` runs the portal's OWN warp script (headless-safe `pi.warp(dest)`), staying legal. Entrances (verified Map.wz + scripts/portal): `101000000 jobin00 enterMagiclibrar→101000003`, `100000200 in02 enterAchter→100000201` (Henesys school LOBBY, reached from town via in01), `240010500 in00 minar_job4→240010501`. (Warrior/Thief 1st-job + all 3rd-job + 2nd-job maps are plain-reachable, no scripting.) Note: 4th-job reachability LOG still shows route-reachable=false because the warn check uses PORTALS_ONLY (Leafre is ferry-gated); travel itself uses ferry, so it's a log nit not a block.

**Re-trigger reconciliation (commit `220cbbb33`).** `buildJobPrompt` is edge-triggered (fires on a level-up, gated by `jobPromptSent`), so an errand interrupted (follow command), never started, or lost to a relog mid-walk was never restarted — the overdue bot just farmed. `BotBuildManager.maybeStartOverdueJobAdvance` (called each autopilot tick in `BotAutopilotManager.tick` when `jobErrandMapId==-1`) restarts it from level+job via SSOT `autoAdvanceTarget` (deterministic 3rd/4th, planned/picked 1st/2nd); the 1st/2nd pick is persisted (`persistPlannedChoice`) so a restart re-targets the same job. `scheduleAutoAdvance`'s deferred lambda now bails if an errand already started (no double-begin).

**OFF-GRAPH INSTRUCTOR SPOT — approach-radius fix (commit ea005559a, 2026-06-23).** The "maps are plain-reachable" claim above is true for the MAP but NOT the NPC's SPOT: the instructor sprite often sits on a high platform / off the nav graph (e.g. 1072003 @ 102040000 at (-3394,-921); nearest reachable foothold ~262px Manhattan away). `tickApproachNpc` only searched for a reachable approach foothold within `APPROACH_SPREAD_PX`=150px, found none, and fell back to the NPC's own unreachable pixel → A* `no-path` → bot loops "walking to the instructor" forever, capping the WHOLE population at lv30 (10 bots piled at 102040000, 6 mages at 101020000). Diagnosed live via `/api/bot/pathlog` ("Last nav decision: no-path", route=<unreachable>) + `/api/botdebug`. Fix (SSOT in `BotTravelManager.pickReachableApproachPoint`): added a `maxPx` widen param — try the 150px de-stack ring first (unchanged), and when nothing's reachable there, widen the foothold hunt up to `maxPx` and take the NEAREST reachable (de-stacked within that cluster). `tickApproachNpc` passes the interaction radius (`radiusPx`, = `NPC_TRIGGER_RADIUS_PX`=500) as maxPx, so 262 < 500 → bot stands at the nearest reachable foothold and advances. Benefits ALL NPC approaches (job/quest/taxi). One-shot per approach (cached). NEEDS RESTART; verify the lv30 pileup clears. Related: [[kb_bot_npc_hop_hail_map_wide]] (taxi/ferry dock/tree NPCs), [[kb_bot_npc_dwell_walk_in_place]].

**Shared errand machinery (SSOT, rule #6):** the "travel to map → approach NPC within radius → status" loop was EXTRACTED to `BotTravelManager.tickApproachNpc(...)` + `ApproachStatus` enum (TRAVELING/WALKING/ARRIVED/NPC_GONE). BOTH `BotQuestManager.tickErrand` and `BotStarterKitManager.tickJobErrand` use it now. Combat suppression = the autopilot tick early-returns when the errand consumes the tick (same pattern as the quest errand check in `BotAutopilotManager.tick`). NPC-map *resolution* differs per errand (quest = current/return-map lookup; job = fixed verified town) so that stays separate.
