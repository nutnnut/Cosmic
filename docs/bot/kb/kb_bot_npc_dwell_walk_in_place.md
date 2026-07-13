---
name: kb_bot_npc_dwell_walk_in_place
description: "Root cause + fix for bot \"walking in place\" at NPCs (quest/job/taxi/gachapon) — dwell ticks consume the tick without stepping physics, freezing the last WALK packet; plus progress-aware errand timeout"
metadata: 
  node_type: memory
  type: project
  originSessionId: 776bbd44-b744-4aa1-9905-ebfb0c4a0aa9
---

**Bug class: a tick that consumes the tick (`return true`) WITHOUT stepping movement leaves a stale WALK broadcast → the client extrapolates it and the bot visibly "walks in place".** Stance is derived from `entry.moveDir` in `BotPhysicsEngine.resolveStance` (WALK_*_STANCE when moveDir!=0); broadcasts only update when something changes (`doBroadcastMovement` dedups). So if the last movement tick left moveDir=walk and no later tick re-runs physics, the bot renders walking forever.

This bit the **NPC "reading"/"talking" dwell**: `BotQuestManager.tickErrand` ARRIVED→`if(!npcDwellReady)return true` and `BotTravelManager.tickTaxiHop` dwell and `BotGachaponManager` mid-roll pacing all returned true without ticking physics, so the bot walked-in-place through the 2–22s pause. The **shop flow never had this bug** because `BotManager.stepMovementCore` is called every tick for shop visits — that's the SSOT lesson.

**First fix (2026-06-19, superseded):** per-call-site `BotTravelManager.settleStandingDwell(entry)` = `tickGrounded(entry,null)`, sprinkled into `tickApproachNpc`, the taxi dwell, and gachapon pacing. The flaw: every NEW consume-without-step branch had to remember to call it — and the ferry boarding/standing branches (`BotFerryManager`) were added later and missed it, re-introducing walk-in-place on docks and in the ferry waiting lobby.

**Central SSOT (2026-06-26, current):** the settle now lives in the common tick, so no call-site can forget it. Mechanism:
- `BotEntry.broadcastedThisTick` — reset at the top of `tickCore`, set `true` inside every broadcast chokepoint (`doBroadcastMovement` + the teleport/flash-jump pin paths) in `BotMovementManager`.
- `BotManager.settleIdleIfUnbroadcast(entry)` runs once after `tickCore` (in both `tick()` and `runTickForTest`). It calls `BotMovementManager.settleIdle` (= grounded `tickGrounded(entry,null)`) **only when** the tick consumed without a broadcast (`!broadcastedThisTick`) AND the last broadcast still showed motion (`lastBroadcastVelX/Y != 0`). The velocity gate makes it fire exactly once on the stop tick (idleOnGround zeroes vel+moveDir → next tick the gate is false), so no packet spam; the `broadcastedThisTick` gate guarantees an actively-walking tick is never force-stopped. Excluded: dead / spawn-warmup / skip-delay / airshow / inAir / climbing. `settleStandingDwell` and all its call-sites were deleted.

Why velocity, not stance: `idleOnGround` always zeroes `moveDir` and velocity together, so a walking broadcast has velX≠0 and a settled one has velX=0 — velocity is the clean one-shot "still appears to be moving" signal that clients extrapolate. Swim maps have no walk-in-place (resting bot floats = inAir, SWIM stance never extrapolates as a walk), so they're intentionally skipped.

**Also fixed "couldn't get to that quest, dropping it":** `ERRAND_TIMEOUT_MS=90s` was a flat wall-clock from errand start, so a legit long multi-hop journey got dropped mid-route. Now `tickErrand` resets `questErrandStartedAtMs` on every `TRAVELING` status (a hop actively progressing; tickTravel has its own give-up windows so sustained TRAVELING = real progress). `WALKING` (on the NPC map, can't close the last <500px gap) does NOT reset, so genuine unreachability still times out.

**SSOT note:** quest/job use `tickApproachNpc` (radius 500). Shop (`BotShopManager.tickShopVisit`) and gachapon (`BotGachaponManager`) are still PARALLEL approach impls — shop has extra machinery (approach-point pick for fenced booths, async purchase sequencing) so full unification was judged out-of-scope/risky; only the settle symptom was unified.

**Second walk-in-place class (2026-07-02, distinct root cause): steering target reachable GROUNDED.**
A committed directional DROP edge steered at its landing point once the bot's x was "past the
runway anchor" — but a bot on a *different same-height foothold across a gap* also satisfies that
x test (NLC 600000000, pathlog-itunes-2026-07-02T071428: DROP (2938,261)->(2898,381) stepX=-6,
bot grounded at (2899,261)). It walks to endPoint.x, stays grounded (no lip there), and parks
against the edge forever; travel deadlines and retries the SAME hop in a loop (461s observed).
Fix in `BotNavigationManager`: the x-only `hasReachedDirectionalDropRunway` shortcut is deleted;
`matchesDirectionalDrop` (the live `simulateWalkOffLanding` gate) now decides in ALL grounded
stances, checking (a) the sim's `launchPoint` falls BEFORE the steering point in the launch
direction (point-steering stops at endPoint.x — a lip beyond it is unreachable) and (b) the
landing region == edge.toRegionId (exact landing pixel is irrelevant at execution time). A
rejected stance steers back to edge.startPoint (the authored runway). Regression:
`BotDirectionalDropNavigationTest#shouldSteerBackToRunwayWhenBotStandsOnWrongFootholdPastTheAnchor`.

**Pre-existing red tests (NOT from this change, verified via stash):** BotQuestManagerTest ×3 (errandArrivalStartsQuestAndClears + talkQuest…RogersApple expect immediate interaction, stale vs the dwell feature; piggybackQueuesStartErrandWhenMobsOverlap map-selection), BotTravelManagerTest ×2 (shouldWalkTowardPortalThenEnterWhenInRange, shouldWalkToCabNpcThenPayAndRide — Mockito strict-stub/order, fail in isolation on clean HEAD too). Surefire XML `<testcase name=>` is offset-by-one from the failure's stack trace — trust the `at server.bots.…Test.<method>` line, not the name attr.
