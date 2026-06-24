---
name: kb_bot_npc_dwell_walk_in_place
description: "Root cause + fix for bot \"walking in place\" at NPCs (quest/job/taxi/gachapon) — dwell ticks consume the tick without stepping physics, freezing the last WALK packet; plus progress-aware errand timeout"
metadata: 
  node_type: memory
  type: project
  originSessionId: 776bbd44-b744-4aa1-9905-ebfb0c4a0aa9
---

**Bug class: a tick that consumes the tick (`return true`) WITHOUT stepping movement leaves a stale WALK broadcast → the client extrapolates it and the bot visibly "walks in place".** Stance is derived from `entry.moveDir` in `BotPhysicsEngine.resolveStance` (WALK_*_STANCE when moveDir!=0); broadcasts only update when something changes (`doBroadcastMovement` dedups). So if the last movement tick left moveDir=walk and no later tick re-runs physics, the bot renders walking forever.

This bit the **NPC "reading"/"talking" dwell**: `BotQuestManager.tickErrand` ARRIVED→`if(!npcDwellReady)return true` and `BotTravelManager.tickTaxiHop` dwell and `BotGachaponManager` mid-roll pacing all returned true without ticking physics, so the bot walked-in-place through the 2–22s pause. The fix idiom already existed in `BotManager.tickActionLocked` (attack lock, ~line 5035): call `BotMovementManager.tickGrounded(entry, null)` so momentum decays and stance flips WALK→STAND. The **shop flow never had this bug** because `BotManager.stepMovementCore` is called every tick for shop visits (BotManager ~3067) — that's the SSOT lesson.

**Fix (commit on experimental, 2026-06-19):** new `BotTravelManager.settleStandingDwell(entry)` = `tickGrounded(entry,null)` when grounded. Called in the SSOT `tickApproachNpc` ARRIVED branch (covers quest START+TURNIN and job errand, which share the stepper — see [[kb_bot_job_change_walk_to_npc]]), the taxi dwell, and gachapon pacing. Broadcast dedups so steady-state standing sends nothing.

**Also fixed "couldn't get to that quest, dropping it":** `ERRAND_TIMEOUT_MS=90s` was a flat wall-clock from errand start, so a legit long multi-hop journey got dropped mid-route. Now `tickErrand` resets `questErrandStartedAtMs` on every `TRAVELING` status (a hop actively progressing; tickTravel has its own give-up windows so sustained TRAVELING = real progress). `WALKING` (on the NPC map, can't close the last <500px gap) does NOT reset, so genuine unreachability still times out.

**SSOT note:** quest/job use `tickApproachNpc` (radius 500). Shop (`BotShopManager.tickShopVisit`) and gachapon (`BotGachaponManager`) are still PARALLEL approach impls — shop has extra machinery (approach-point pick for fenced booths, async purchase sequencing) so full unification was judged out-of-scope/risky; only the settle symptom was unified.

**Pre-existing red tests (NOT from this change, verified via stash):** BotQuestManagerTest ×3 (errandArrivalStartsQuestAndClears + talkQuest…RogersApple expect immediate interaction, stale vs the dwell feature; piggybackQueuesStartErrandWhenMobsOverlap map-selection), BotTravelManagerTest ×2 (shouldWalkTowardPortalThenEnterWhenInRange, shouldWalkToCabNpcThenPayAndRide — Mockito strict-stub/order, fail in isolation on clean HEAD too). Surefire XML `<testcase name=>` is offset-by-one from the failure's stack trace — trust the `at server.bots.…Test.<method>` line, not the name attr.
