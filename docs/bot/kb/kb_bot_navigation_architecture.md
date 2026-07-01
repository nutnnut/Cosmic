---
name: Bot Navigation Architecture (Graph + Physics)
description: File:line map of bot nav graph build, physics walk-off drops, manager, fallback mode
type: project
originSessionId: adb164c2-f898-43af-91a9-dc823a422303
---
# Key files & line anchors (as of experimental branch, 2026-04-11)

> **2026-05-07 update**: Many line numbers below have drifted. Current GRAPH_VERSION is 41 (was 21). For up-to-date details on cost model, anchor sampling, rope-grab semantics, and same-region portals, see `kb_bot_nav_costs_and_anchors.md`. The architecture and call structure below is still broadly accurate; treat specific line numbers as outdated.

## BotNavigationGraphProvider.java (1628 lines)
- `GRAPH_VERSION = 21` at line 31 — **bump when edge format changes** (incompat triggers rebuild) [2026-05-07: now 41, file is ~2132 lines]
- `GRAPHS / PENDING_GRAPHS / LAST_BUILD_REPORTS` ConcurrentHashMaps at lines 36-38
- `GRAPH_WARMUP_EXECUTOR` single-thread daemon line 41 — **serialized warmups**
- `GraphCacheKey(mapId, totalSpeedStat, totalJumpStat)` record line 47 — profile-keyed
- `GraphBuildReport` class line 54 — regionCount, dropEdgeCount, buildDropEdgesNs etc.
- `peekGraph(map)` line 298 — first graph for map ANY profile (useful for cross-bot reuse)
- `peekClosestGraph(map, profile)` line 319 — closest-stat cached graph
- `warmGraphAsync(map, profile)` line 343 — fire & forget
- `getOrStartGraphLoad` line 370 — dedupes via PENDING_GRAPHS
- `buildGraph()` line 465 — top-level build pipeline. Order: collectFootholds → buildRegions → addRopeRegions → buildFeatureXs → buildAnchors → addWalkEdges → addDropEdges → addJumpEdges → addRopeEntry → addRopeExit → addPortals
- **`addDropEdges` line 703** — calls addDirectionalDropEdge twice (-1, +1) PLUS iterates every anchor for vertical down-jumps
- **`addDirectionalDropEdge` line 739** — calls findDirectionalDropBuild
- **`findDirectionalDropBuild` line 773** — SLOW LOOP: iterates every X from anchor back to region limit, calls `BotPhysicsEngine.simulateWalkOffLanding` per candidate (up to 256 ground-motion ticks each)
- `addJumpEdges` line 810 — uses jumpLandingCache, 3 launchStepX per anchor
- `anchorPoints()` line 1339 — produces per-region anchor list

## BotPhysicsEngine.java (1677 lines)
- `Config` class line 22 — calibrated constants
  - `WALK_VEL = 125` px/s (real client)
  - `GRAVITY_PXS2 = 2000.0f`
  - `JUMP_SPEED_PXS = 555.0f`
  - `JUMP_DOWN_PXS = 196.0f` (was 320 — changed recently; **possibly related to regression**)
  - `HFORCE_PXS = 16.667` — yields 125 px/s walk via hF*slip/(friction+slope)
  - `GROUNDSLIP = 3.0`, `FRICTION = 0.3`, `SLOPEFACTOR = 0.1`
- `WalkOffLanding` record line 96 — (launchPoint, launchStepX, landing, travelTimeMs)
- `GroundTravelState` record line 52 — (physX, hspeed, carryMs)
- `initialGroundTravelState(position)` line 751 — hspeed=0 default
- `simulateGroundMotion()` line 755 — single ground tick with slope/walls
- **`simulateWalkOffLanding(map, from, dir, profile)` line 786** — overload at line 793 takes explicit GroundTravelState. Iterates up to 256 ground ticks until lostGround, then calls simulateFallLanding.
- `simulateDownJumpLanding(map, from)` line 1027 — vertical down-jump landing
- `simulateFallLanding(map, from, stepX)` line 1031 — airborne trajectory given launch stepX
- `estimateDownJumpLandingTimeMs(map, from)` line 1051
- `estimateFallLandingTimeMs(map, from, stepX)` line 1055
- `queueDownJump(entry, bot)` line 565 — sets `downJumpPending=true`, `crouching=true`. **Physics handles the actual down-jump in a subsequent tick.**
- `beginDownJump(entry, bot)` line 592 — called later; launches airborne with `-downJumpForcePerTick()` and grace period

## BotNavigationManager.java (1080 lines)
- `resolveTarget(entry, rawTargetPos, runAiTick)` line 48 — main nav entry
  - line 58 `resolveActiveGraph` (exact → closest) — **if null sets graphWarmupFallback=true and calls warmGraphAsync**
  - line 70: even if active graph found, if EXACT profile not cached, trigger warmup (comment "graph-fallback-profile")
- `reuseCommittedEdge()` line 203 — edge reuse rules
- `tryExecuteDrop()` line 317 — `if (edge.launchStepX != 0) return null;` — **walk-off drops are NOT an explicit action; bot just keeps steering and physics carries it off**
  - **This is why Leroy's DROP with launchStepX=7 is stuck**: manager never executes; relies on bot continuing to walk past endpoint. But Clawer saw "nav=reuse[jump-pos]" — different issue.
- `selectDropWaypoint()` line 554 — if runway reached, use endPoint; else startPoint
- `hasReachedDirectionalDropRunway()` line 584 — true when bot crossed startPoint in launch direction
- `canExecuteDropFromCurrentPosition()` line 439 — requires `edge.launchStepX == 0` (only vertical drops executable)
- `resolveActiveGraph()` line 546 — exact profile → closest (uses peekGraph + peekClosestGraph)
- `canExecuteJumpFromCurrentPosition()` line 805 → `isWithinJumpLaunchWindow()` line 850
- `resolveCurrentRegionId()` line 983 — airborne returns -1

## BotManager.java
- `tickStuckDetection()` line 1444 — decrement unstuckCooldownMs, measures stuck, calls `BotMovementManager.tickUnstuck(entry)` at line 1475 when stuck >=500ms
- **Current skip condition line 1448**: `(entry.navEdge == null && entry.moveTarget == null && !entry.graphWarmupFallback)` — this runs the unstuck logic WHEN in fallback mode (problem per task #2).
- `dropMessage(5, ...)` is how owner messages are sent (see 822, 830)

## BotMovementManager.java
- `tickUnstuck(entry)` line 592 — randomly begins left/right ground jump, clears nav, 5s cooldown
- `clearNavigationState(entry)` — called from multiple places
- `cfg.STOP_DIST`, `cfg.FOLLOW_DIST`, `cfg.JUMP_Y_THRESH` — tuning constants

## BotEntry.java
- `graphWarmupFallback = false` line 182
- `stuckMs = 0` line 215, `unstuckCooldownMs = 0` line 216
- `movementProfile` — per-entry profile (speed/jump stats)
- `navEdge`, `navTargetPos`, `navTargetRegionId`, `navPreciseTarget` — committed edge state
- `lastEdgeBlockReason` — string like "jump-pos" for debug

## BotFallbackMovementManager.java
- line 65 checks `entry.graphWarmupFallback` — heuristic movement while graph warms
- Produces simple walk-toward-target with no jumps

## BotFidgetManager.java
- lines 158, 183 — **already gates fidgets by !graphWarmupFallback** (precedent for task #2 disable in fallback)

## Drop edge runtime semantics
- DROP edge with `launchStepX != 0` = walk-off drop: bot walks toward endPoint past startPoint; physics naturally falls via `simulateGroundMotion` returning lostGround
- DROP edge with `launchStepX == 0` = explicit down-jump: `queueDownJump` → crouch → `beginDownJump` later
- Leroy's log shows `DROP r48->r51 (3638,394)->(3672,454) stepX=7`: this is a walk-off drop. Bot is at x=3637 and start=3638, so `hasReachedDirectionalDropRunway` false → steers to (3638,394). But bot stops at 3637 and never reaches 3638. **Stuck detection triggers unstuck jump, bot falls sideways, eventually lands in r51.** So Leroy case IS fallback-related unstuck jump saving it — not breaking, just ugly.
  - Root cause: `wasMovingX` hysteresis probably. Bot at 3637, target 3638 (1px difference), below STOP_DIST so steers 0.

## Clawer case analysis
- Map 100000000 r45, bot (940,334). Edge `JUMP r45->r41 (938,334)->(889,274) stepX=-6`.
- lastNavDecision `reuse[jump-pos]` for 2550ms. `jump-pos` block reason set at line 307 in `tryExecuteJump`.
- Means `canExecuteJumpFromCurrentPosition` keeps returning false. Bot is AT (940,334), edge start is (938,334). `isWithinJumpLaunchWindow` requires `edge.containsLaunchX(botPos.x)`, i.e. botPos.x in [launchMinX, launchMaxX]. The edge may have launchMinX==launchMaxX==938, so 940 is outside.
- Bot never moves because it's already "there". AI expects it to walk back to x=938 but walk hysteresis (STOP_DIST?) blocks.
- **Likely root cause**: after recent `HFORCE_PXS` / `WALK_VEL` calibration, walkStep is smaller; the 2px difference fails the jump launch window but also fails the stop-distance walk trigger.

## Recent suspect commits
- `7997bb689` Block bot ground wall phasing during fallback
- `aafc56f5f` Apply bot movement profile changes during graph warmup
- `322765bc4` Calibrate bot physics constants — **HFORCE_PXS/WALK_VEL/GRAVITY change**
- `29c3e1909` Model directional bot drops with natural walk-off physics — **pre-this had no directional drops; was faster**
