---
name: Bot Nav Oscillation Root Causes (rope endpoints + committed routes)
description: Three distinct "bot stuck in a loop" oscillations and their fixes — rope-top down-key phantom DROP, rope-bottom region dead-zone, and the position-blind route-cache r45<->r42 ping-pong fixed by committing one route. Verified 2026-06-25 on experimental.
type: project
---
# Bot Nav Oscillation Root Causes

Four separate "bot stuck ping-ponging" bugs diagnosed from `logs/bot-nav/pathlog-*.txt`
captures + the live `/api/navprobe` (bot web server, port 8089). All on Henesys (100000000).
Verified on `experimental`, 2026-06-25. Diagnose oscillations with the pathlog tick history
(`nav=new/reuse/exec`, the committed `edge=`, and region `r=`) plus `/api/navprobe?id=&x=&y=`.

## 1. Rope TOP — down-key intent conflict (phantom DROP over a grabbable rope)
Symptom (`pathlog-LeSsOn`): on the ground at a rope top the bot grabs the rope, top-steps back
to the ground, re-grabs — forever. A* *displays* the correct `DROP r25->r32` (a down-jump going
left) but execution always grabs the rope instead.

Root cause: a **down-jump and a rope grab share the DOWN key**. `BotPhysicsEngine` gives rope-grab
precedence, so a straight-down DROP edge whose launch column is within `ROPE_GRAB_X` of a grabbable
rope is a **phantom** A* prefers but execution can never satisfy. The grab is already modelled as a
CLIMB edge (`canTopStep`, BotNavigationGraphProvider), so the parallel DROP is a lie.

Fix: in `BotNavigationGraphProvider.addDropEdges` skip the straight-down (`launchStepX==0`) DROP when
`downKeyGrabsRope(map, anchor)` — which reuses `BotPhysicsEngine.simulateDownJumpRopeGrab` (the exact
physics the builder's `canTopStep` uses; returns null below a rope bottom, so real downward drops are
untouched). Descent stays available via the rope CLIMB-down edge. Walk-off drops (`launchStepX!=0`,
no DOWN key) are unaffected. Test: `shouldNotAuthorStraightDownDropThatWouldGrabRopeInstead`.

## 2. Rope BOTTOM — region dead-zone resolves to -1 (empty path)
Symptom (`pathlog-rApIdScUrVy`): at a rope bottom the bot's y dithers ~2px; `/api/navprobe` from
(3398,**332**) → `fromRegion=-1, path=[]` while (3398,**334**) → `r45` with a clean rope-free
`JUMP r45->r48->r51` to the right. The bot keeps grabbing/exiting the rope instead of just jumping.

Root cause: `resolveCurrentRegionId` blanked the region to `-1` whenever `entry.inAir`, even when the
bot is settled ~2px above a platform (the off-graph gap between the rope region's bottom and the
ground). `-1` → empty A* path → the bot flails on the rope.

Fix (the in-place, root version — NOT a special-case helper): only blank when ground is genuinely far
below — `if (entry.inAir && BotPhysicsEngine.isGroundFarBelow(map, botPos)) return -1; else return
graph.findRegionId(...)`. A bot hugging a platform resolves to it and gets a real route. Tradeoff: a
jump skimming <`MAX_SNAP_DROP` over an unrelated platform may resolve to it for one self-correcting
tick. Test: `shouldResolveRopeBottomDeadZoneToGroundInsteadOfMinusOne`.

## 3. Flat ground — position-blind route cache → r45<->r42 ping-pong (the deep one)
Symptom (`pathlog-GearArrow`): on the wide r45 platform the bot jumps `r45->r42` (a dead-end-ish
branch) then `r42->r45` back, forever, never heading to the goal r41 (directly left). `Stuck:no`
(it keeps moving, so the blocked-give-up never fires). The pathlog's "CURRENT A* PATH" shows the
*correct* `r45->r41` — because that display runs a **fresh** `findPath` (bypasses the cache), while
the committed edge comes from `findNextEdge`'s cache.

**Root cause (verified, and it is NOT "jitter is non-transitive"):** the per-bot route jitter
(`JITTER_FRAC=0.55`, stable per `(routeSeed, edge)`) is deterministic, and a single search over fixed
costs IS transitive — so jitter alone cannot cycle. The real culprit: **the best first hop OUT of a
region is position-dependent** (the search charges `intraRegionTravelCost` from the bot's x to each
candidate launch point), but `findNextEdge`'s next-hop cache (`BotNavigationGraph.routeCache`) is
keyed `(region, target, bucket)` — **position-blind** — and **never invalidated** (graph lifetime),
and **shared across bots**. It stores the hop computed from `bot.getPosition()` of whichever bot first
populated it. Proven with a per-x probe: same bucket/goal, `r45->goal` first hop is `r45->r41` from
the LEFT of r45 (x<=1400) but `r45->r42` from the RIGHT (x>=1800). So `cache(r45,goal)` filled from
the right serves `r45->r42` to a bot on the left, while `cache(r42,goal)` (filled elsewhere) serves
`r42->r45` — mutually inconsistent → a frozen 2-cycle.

**Fix — "diversity is a good human feature; just stick to one route":** keep the per-bot route
variety, but commit ONE route and follow it instead of re-deciding the next hop per region.
- `BotEntry.committedRoute` + `committedRouteTargetRegionId`.
- `BotNavigationManager.resolveTarget`: when it needs a new edge, take `nextCommittedRouteEdge` (first
  usable non-WALK edge leaving the bot's current region from the committed route). Miss (goal region
  changed, or knocked off the route) → `computeCommittedRoute` (the bot's OWN seed via `findPath` /
  skill-aware `skillAwareRoutePath`) and re-commit. One search = acyclic, so no flip-flop; per-bot
  seed keeps diversity. Routes with intra-region PORTAL self-loops are NOT committed (would re-select
  the self-loop) → fall back to per-hop `findNextEdge`.
- `clearNavigationState` clears the route. `findNextEdge`/`routeCache` stay (still used by
  target-scoring + portal warming). Behavior change: routing now plans a per-bot route per journey
  leg instead of shared per-region cache hits. Test:
  `committedRouteIsFollowedForwardToGoalWithoutFlipFlop`.

## 4. Capped Best-Effort - horizontal-only "closest" route loops
Symptom (`pathlog-DecembeR-2026-06-26T034055`, map 105030000): target portal was far above at
`(58,-2586)` / r1. A full path search capped, then capped best-effort returned the loop
`DROP r83->r89`, `CLIMB r89->r102`, `CLIMB r102->r83`; the bot repeated it forever.

Root cause: `runSearch` said the capped best-effort frontier was chosen by raw distance to target, but
the code used `heuristic(...)`. For ground regions that heuristic is X-only walk cost, so returning to
r83 at x=60 looked better than starting at x=63 even though it was still about 2500px vertically below
the portal. A large vertical detour was accepted as "progress".

Fix: capped best-effort frontier selection now uses Manhattan raw distance (`rawDistance`) while A*
keeps the existing cost heuristic. Regression:
`BotNavigationGraphProviderTest.committedBestEffortRouteDoesNotCycleWhenPortalRegionSearchCaps`.

## 5. Foothold branch detour disarms too early
Symptom (`pathlog-MemoryCovert-2026-06-28T144650`, map 100040000): path and edge are stable
(`CLIMB r11->r48`), but the waypoint alternates between the rope-entry launch approach
(`~1014,233`) and a left branch detour (`883,294`). The bot walks right, then left, forever, with
`blocked: climb-pos`.

Root cause: `footholdDetourWaypoint` correctly detected that the lower branch must first walk left
through a shared endpoint, but after one 6px walk tick foothold resolution could see the adjacent
upper branch and return `null`. Normal CLIMB steering then resumed toward the launch x before the bot
had actually crossed the detour waypoint, sending it back onto the lower branch.

Fix: store the active foothold detour on `BotEntry` and keep returning that waypoint until the bot
reaches/crosses it; active detours use zero stop distance so they do not park one pixel short.
Regression: `BotNavStuckAnalysisTest.footholdDetourDoesNotFightLegalClimbApproachInMemoryCovert`.

### 5b. The real root cause behind #5 — ground-walk snaps DOWN at a joined fork (graph v66)
The #5 detour was a **bandaid**; #5's regression only checked x=892/887 (both RIGHT of the fork at
883) so it stayed green while the bug lived. Reproduced exactly (`pathlog-MinusSent-2026-06-29T032740`,
same map/spot) in `BotRegion11ForkOscillationTest`, which rebuilds ONLY region 11 from the WZ foothold
layer (ids+prev+next): region 11 forks at vertex (883,294) into **ridge A** (the next-chain
`fh173.next=178->...->188`, climbs to the rope launch at 1037,233) and a **dead-end spur B**
(`fh177->174->175->176`, ends 964,300). A and B overlap in x with A rising ~8px above B.

Root cause (physics, NOT nav): `BotPhysicsEngine.findWalkRegionGroundSample` picked the **lowest**
foothold in the region by raw dx/|dy| score. Walking UP across the fork, the bot's y lags at B's
level, so every step right snapped it DOWN onto spur B — it then either walked off B's dead-end and
**fell off the map** (clean moveTarget) or **oscillated** (when #5's detour yanked it back). Probe
truth from the user's real client: walking always takes the UPPER leg; you can only reach the lower
spur by an explicit down-jump. The client tracks the standing foothold (SN) and walks its prev/next
chain — the bot physics diverged by re-snapping to lowest-ground each tick.

Fix (rule #9, the physics): `findWalkRegionGroundSample` now prefers the **chain step** (the standing
foothold or its direct `prev`/`next`) over any non-chain overlapping segment; dx/|dy| only breaks ties
within the same chain class. This matches the client and is naturally directional — walking down still
follows the chain, and a *crossing* ramp (footholds that share no chain link) is unaffected, so it can
still be walked down. Region stays merged (B→A via the fork is a real walk path; A→B is a drop). The
builder uses the same walk sim, so authored edges change → **GRAPH_VERSION 65→66**. Regressions:
`BotRegion11ForkOscillationTest` (spur, main-ridge, and merge cases).

## 6. Rope climb keeps stale ground jump from another region
Symptom (`pathlog-d1vcreek-2026-06-29T033330`, map 103000101): bot oscillates vertically on a rope
near a mob. The fresh current path starts with `CLIMB r30->r7`, but the active reused edge is still
`JUMP r7->r4` with `reuse[jump-pos]`. Combat is not blocked (`SHOULD FIRE`); movement is frozen
downstream because the stale ground jump steers to an off-rope waypoint while the bot is climbing on
rope region `r30`.

Root cause: `reuseCommittedEdge` had a broad climbing retention rule: while climbing and not already
at the edge destination, keep the committed edge. That rule exists for rope-exit false positives, but
it also retained non-CLIMB ground edges whose source region did not match the current rope region.
The stale `JUMP r7->r4` therefore survived even though the live A* route wanted the rope exit.

Fix: while climbing, retain CLIMB edges through false-positive region readings, and retain non-CLIMB
edges only when the current resolved region is that edge's source. A bot on `r30` now drops the stale
`JUMP r7->r4`, replans, and can take the fresh `CLIMB r30->r7`. Regression:
`BotNavigationManagerTest.shouldDropStaleGroundJumpWhileClimbingOnDifferentRopeRegion`.

## 7. Loot detour pulls a bot off a climb to a vertically-stacked mob (combat, not nav)
Symptom (`pathlog-porprism345-2026-06-29T081014.txt`, map 103000101): bot oscillates `x≈1493↔1507`
on flat region 24, never climbs, never attacks (last hit 58s ago). Goal alternates every ~200ms
between `grind-target (1431,71)` (the mob, 4 climbs up region 11, `dy=252`, out of range) and
`nav-input (1604,323)` (flat ground, to the RIGHT). `cmb=ATK` on BOTH phases ⇒ the bot keeps its
grind target the whole time (not wander/null); the 1604 goal is the **loot-detour override**
(`tickGrindMode` line ~4029), not a nav-graph decision.

Root cause: `convenientLootTarget` (BotManager.java) gates the detour with
`lootDistSq < mobDistSq * GRIND_LOOT_CONVENIENCE_RATIO`, where `mobDistSq` is the mob's
**straight-line** `distanceSq` (≈263²). That ignores the mob being ~1200 graph-cost (4 ropes) away,
so flat loot always "wins". Each tick the bot lurches toward a drop; once within `LOOT_RADIUS=100`
(`activeGrindLootPosition`) the drop is marked arrived + suppressed *without a real pickup*, the bot
turns back toward the climb, the next nearby drop re-acquires, repeat → a tight foot-of-rope
oscillation. `findGrindTarget` already scores by graph cost; the loot-convenience check did not — an
SSOT/like-for-like-distance violation (the Euclidean-vs-travel mismatch is the same bug class as #4).

Fix (rule #9): `convenientLootTarget` resolves bot vs mob nav regions (`peekGraph` +
`resolveCurrentRegionId`/`resolveTargetRegionId`, same pattern as `selectCrossRegionRetreatTarget`)
and returns `null` (no detour) when they differ. The convenience comparison only runs for a
same-region flat fight; cross-region (climb/drop-away) loot is collected when the bot travels there
naturally. Graph-unavailable falls back to prior behavior (detour allowed).

## 8. Phantom cross-region JUMP onto SHARED ground (overlapping foothold chains)
Symptom (`pathlog-SuseRug-2026-06-29T141220`, map 600020100): bot frozen/oscillating at (-1318,156)
on r97, committed `JUMP r97->r73` with `blocked: jump-pos`, give-up→replan-same-edge forever. The
goal (541,155) is on the SAME flat r97 platform (`x[-1475..865] y156`, contiguous — a plain walk
right reaches it), yet A* returned a 9-hop portal tour entered by that jump.

Root cause (TWO defects, verified live via `/api/mapgraph` + Angel.idb): r73 is a ramp rising to
y-144 whose **flat foot overlaps r97 exactly at y156** (`x[-1315..-1260]`) — two distinct foothold
chains on the same ground. (1) **Builder** authored `JUMP r97->r73` whose landing `(-1314,156)` is on
ground r97 itself covers; the client tracks the standing foothold's prev/next chain (`CVecCtrl::CalcWalk`
/ `GetCrossCandidate`, and direct observation: walking onto the shared foot from r97 keeps you on r97,
from the ramp keeps you on r73 — there is **no way to switch chains on shared ground**), so the jump
can never change region — A* commits it, the bot can't perform it. (2) **Region resolution** was
chain-blind: `resolveCurrentRegionId -> findRegionId -> findGroundFoothold` is a coordinate `findBelow`,
so on shared ground it could flip between r97/r73. The 0-cost portal chain made the phantom-entered tour
(cost 4228) beat the direct walk (~10645), so A* preferred it.

Fix (rule #9, two parts): (a) **builder** — `addJumpEdges`/`addFlashJumpEdges` skip an edge whose
authored window endpoint lies on the SOURCE region's own surface (`Region.surfaceCoversPoint`, exact
tolerance `SHARED_GROUND_Y_PX=0` — 1px off = a real platform on top = a legitimate edge); guard the
WINDOW endpoint, not the raw per-anchor sim (they differ after window expansion). `GRAPH_VERSION 66→67`
(regenerates serialized routes; needs a live graph rebuild/restart to take effect). (b) **region
continuity** — `BotEntry.lastRegionId`; `resolveCurrentRegionId` keeps the last region when the current
point is shared with it (the chain we walked in on), reset on graph swap. Regressions:
`BotSharedGroundPhantomJumpTest` (synthetic ramp-foot-over-flat; reproduces the phantom with the guard
disabled, and asserts a genuine same-height gap jump is NOT over-pruned).

## 9. Directional walk-off DROP: lip-authored landing vs real dismount overshoot (anchor park)
Symptom (live 2026-07-03, Henesys department store 100000102): bots "resupplied and omw back"
never left — parked/oscillating on the small shelves r17/r18 above the ground floor, travel
re-running the r17→r24 plan every tick (perf stalls 280ms–2.3s under load). `/api/pathfind`
17→24 succeeded — planner fine; execution wedged.

Root cause (physics disagreement between builder and executor): `addDirectionalDropEdge`
authored the landing with `simulateFallLanding` from the EXACT lip pixel, but a real dismount
(`simulateWalkOffLanding` — the sim the bot's ground motion actually runs) leaves the ground up
to one sub-tick walk step PAST the lip. Those few px can change which platform catches the fall
(r18's walk-off really lands the y=120 bookshelf, not the floor the lip-fall predicted; r17's
landing flips r18↔r23 on a **1px** launch difference — knife-edge). The 2026-07-02 NLC gate
(`matchesDirectionalDrop`) then required the landing REGION to equal the authored `toRegionId`,
mismatched every tick, and steered back to the runway anchor forever — a permanent park the
blocked-pos watchdog missed (position kept changing by ±walkStep).

Fix (rule #9, three parts, all nav/physics):
- **Builder** — `addDirectionalDropEdge` authors the landing via `simulateWalkOffLanding` from
  the runway anchor (execution SSOT). `GRAPH_VERSION 66→68` (67 was documented in #8 but the
  constant was never bumped — 68 also finally regenerates those stale caches).
- **Executor gate** — `matchesDirectionalDrop` no longer matches the exact landing region
  (knife-edge); it requires a real dismount that DESCENDS off the source region and launches
  before the steering stop (the wrong-ledge park check stays). The route replans from wherever
  it touches down.
- **Watchdog** — `trackBlockedPositionGate` resets on leaving a 16px drift radius
  (`BLOCKED_POS_DRIFT_PX`) instead of on any 1px move, so a bounce against an unexecutable gate
  now trips the ~300-500ms give-up.

Regression: `BotHenesysDeptStoreDescentTest` (WZ-backed — 12 start stances incl. the live stuck
ones must reach the exit portal; every authored directional DROP must land where the walk-off
sim lands).

## 10. 1px JUMP launch window is unhittable by quantized steps (jump-pos bounce)
Symptom (same map, surfaced by #9's regression sweep): committed `JUMP r12→r19` with launch
window `[108,108]`; the bot's integer positions phase-skip x=108 (±6px steps), so
`isWithinJumpLaunchWindow`'s exact `containsLaunchX` never passed — `jump-pos` forever while the
±6 bounce defeated the exact-position watchdog. Some windows are legitimately 1px (the arc only
lands the target region from one column, e.g. an overhead shelf clips wider launches) — but a
1px window is below the bot's motor precision.

Fix: `isWithinJumpLaunchWindow` gained a `minAcceptSpanPx` overload —
`canExecuteSelectedJumpFromCurrentPosition` passes the walk step, widening acceptance
symmetrically only when the window is narrower than one step (wide windows keep exact
containment; the existing `selectedJumpLaunchX` ±walkStep check still applies). An off-column
launch lands a few px off-plan and simply replans — strictly better than the infinite park.
Plus the #9 watchdog drift radius as the systemic backstop.

## 11. #10's widened acceptance fired physically impossible launches (jump-in-place loop) — REVERTED
Symptom (live 2026-07-03, NLC 600000000, pathlog-CheatSTanK): vertical `JUMP r68→r62`, window
`[1620,1623]`, bot parked at x=1624 jumping straight up forever — the r62 slope rises ~4.9px per
x, so the floor at 1624 (y≈170) sits above the jump apex (y=174) while at 1621 (y≈185) it clears.
Neither watchdog fired: the gate *accepted* the launch (no `jump-pos` ticks) and the arc kept the
bot "moving".

Root cause: the #10 `minAcceptSpanPx` widening let the executor fire from OUTSIDE the authored
window. But `expandJumpLaunchWindow` authors the **maximal per-x-simulated valid span** — every
x outside it is a proven miss, so any runtime widening launches an invalid arc by construction.
Narrow windows exist precisely where the physics are knife-edge (steep target slopes).

Fix: widening reverted — `isWithinJumpLaunchWindow` is strictly the authored window again; the
graph is the SSOT, the runtime only checks it. Unhittable 1px windows fall to the #9/#10
blocked-pos watchdog (`jump-pos` give-up + replan), which the descent regression confirms still
clears 100000102. **Rule: never "help" an authored launch window at runtime — if a window looks
wrong, fix the builder.**

## 12. Closest-profile fallback graph flies arcs with the wrong physics (overshoot loop)
Symptom (live 2026-07-03, Kerning 103000000, pathlog-TeensDusk): Haste bot (speed 140 / jump
120) navigating the base 100/100 graph (`Fallback: closestGraph=yes` — its exact-profile graph
not built yet after the v68 cache invalidation). Committed `JUMP r127→r122` launched correctly
in-window, but with jumpForce 666 and airVelX ±9 the arc flew ~50% farther than authored,
overshot the target platform entirely and landed back on r127 → walk back, jump, repeat ~2.5s
per cycle. No watchdog: execution "succeeds" every time.

Fix: `runSearch` clears arc edges from the plan when the serving graph's profile ≠ the bot's
live profile (`graphMatchesLiveProfile`): new `BotNavigationGraph.EXCLUDE_JUMP_ARCS` mask bit
(exclusion semantics — default callers unchanged) drops ground JUMPs, and `SKILL_FLASH_JUMP` is
cleared too. WALK/PORTAL/CLIMB/DROP/TELEPORT stay (profile-safe: teleport dest is computed live,
directional walk-off drops live-sim with the entry profile). `canReach`/`costToGoal`/
`nearestReachableRegion` share the mask, so reachability verdicts match what the executor can
actually fly; the exact-profile graph warms in the background and the plan upgrades on swap.

## 13. Heuristic-fallback rope-top dismount loop (no-graph maps)
Symptom (live 2026-07-03, NLC-mall-town 551000000, pathlog-BishopDemo): no graph at all
(`graph-warmup` after the v68 invalidation), heuristic fallback walking. Target ~500px below;
fallback correctly steers to a rope to descend, attaches at the rope TOP — and `tickClimbing`'s
non-nav dismount rule ("target far horizontally AND below the rope bottom → jump off") fired
immediately, launching the bot back onto the entry platform for zero descent; fallback walks it
back to the rope, ~1.6s loop.

Fix: the dismount now also requires the bot to be near the rope BOTTOM
(`botPos.y >= bottomY - STOP_DIST`) — climb the descent out first, then jump off toward the
target.

## 14. Knife-edge walk-off DROP authored from ONE launch state — planner lie, replan loop (v69)

Symptom (live 2026-07-03, FM entrance 910000000, pathlog-CabinOpened): exact-profile graph,
`DROP r4->r5` authored landing (353,-176) on the r5 portal ledge. Live, the bot crossed r5's
height at x=370 and fell through to the r6 floor — the only route from r6 leads back through
r7 -> portal -> r4, so travel replanned through the same edge forever (~3.3s loop, `Stuck: no`
since the bot keeps moving). A second edge on the same map (`DROP r1->r5`) failed identically.

Root cause: `addDirectionalDropEdge` authored the landing from a SINGLE walk-off sim (runway
anchor, fractional physX phase 0, standing start). The live launch state is not unique — the bot
arrives with arbitrary fractional physX, sub-step `carryMs`, and hspeed, which shifts the
dismount pixel and the seeded air drift by one rounding step (6 vs 7 px/tick at 105% speed).
Near a platform edge, that flips which region catches the fall. The #9 `matchesDirectionalDrop`
relaxation ("dismount anywhere that descends, replan from touchdown") then converts the wrong
landing into a silent infinite replan loop whenever the touchdown region routes back through the
source region.

Fix (build time, GRAPH_VERSION 68→69): `BotPhysicsEngine.walkOffLandingVariants` sims the
walk-off across the live launch-state spread (physX phase, carryMs, standing vs full-speed
arrival); `addDirectionalDropEdge` authors the edge only when EVERY variant lands the same
region. Knife-edge drops are simply not authored — the planner uses committed JUMP edges or
portal routes instead (both deterministic).

Rule: an edge whose outcome depends on live launch state the graph doesn't encode must be
authored for its WHOLE outcome envelope or not at all — one sampled outcome is a planner lie.

## Not-a-bug
`pathlog-fictionxD` "jumping back-forth" = a single clean walk-off DROP mid-descent (`Stuck:no`,
`r=-1` is the normal airborne reading). No oscillation.

## Files
- `BotNavigationGraph.java` — `Region.surfaceCoversPoint` + `SHARED_GROUND_Y_PX` (#8)
- `BotNavigationGraphProvider.java` — `addJumpEdges`/`addFlashJumpEdges` shared-ground guard,
  `GRAPH_VERSION` 66→67 (#8); `addDropEdges` + `downKeyGrabsRope` (#1)
- `BotNavigationManager.java` — `resolveCurrentRegionId` inAir gate (#2) + lastRegionId chain
  continuity (#8); `BotEntry.lastRegionId` (#8); `resolveTarget` +
  `computeCommittedRoute` / `nextCommittedRouteEdge` / `skillAwareRoutePath` (#3);
  capped best-effort `rawDistance` frontier selection (#4)
- `BotEntry.java`, `BotMovementManager.clearNavigationState` — committed-route state (#3)
- `BotPhysicsEngine.findWalkRegionGroundSample` + `isChainStep` — chain-step preference at joined
  forks (#5b); `BotNavigationGraphProvider.GRAPH_VERSION` 65→66
- `BotNavigationGraphProvider.addDirectionalDropEdge` — walk-off-sim landing authoring,
  `GRAPH_VERSION` 66→68 (#9); `BotNavigationManager.matchesDirectionalDrop` (#9),
  `trackBlockedPositionGate` + `BLOCKED_POS_DRIFT_PX` (#9/#10),
  `isWithinJumpLaunchWindow(minAcceptSpanPx)` (#10; reverted to strict in #11)
- `BotNavigationManager.isWithinJumpLaunchWindow` strict window (#11),
  `runSearch` profile-mismatch arc mask + `graphMatchesLiveProfile` (#12);
  `BotNavigationGraph.EXCLUDE_JUMP_ARCS` (#12);
  `BotMovementManager.tickClimbing` rope-bottom dismount gate (#13)
- `BotPhysicsEngine.walkOffLandingVariants` + `addDirectionalDropEdge` variant-stability guard,
  `GRAPH_VERSION` 68→69 (#14); `BotFreeMarketEntranceDescentTest` (WZ-backed 910000000, #14)
- Tests in `BotNavigationGraphProviderTest` (fast synthetic + Henesys WZ graph),
  `BotRegion11ForkOscillationTest` (synthetic region-11 fork, #5/#5b),
  `BotHenesysDeptStoreDescentTest` (WZ-backed 100000102 descent, #9/#10).

Related: [[kb_bot_nav_costs_and_anchors]], [[kb_bot_downjump_eligibility]],
[[kb_bot_town_nav_airborne_target]], [[kb_bot_navigation_architecture]].
