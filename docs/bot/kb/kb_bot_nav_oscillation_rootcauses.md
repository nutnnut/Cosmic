---
name: Bot Nav Oscillation Root Causes (rope endpoints + committed routes)
description: Three distinct "bot stuck in a loop" oscillations and their fixes — rope-top down-key phantom DROP, rope-bottom region dead-zone, and the position-blind route-cache r45<->r42 ping-pong fixed by committing one route. Verified 2026-06-25 on experimental.
type: project
---
# Bot Nav Oscillation Root Causes

Three separate "bot stuck ping-ponging" bugs diagnosed from `logs/bot-nav/pathlog-*.txt`
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

## Not-a-bug
`pathlog-fictionxD` "jumping back-forth" = a single clean walk-off DROP mid-descent (`Stuck:no`,
`r=-1` is the normal airborne reading). No oscillation.

## Files
- `BotNavigationGraphProvider.java` — `addDropEdges` + `downKeyGrabsRope` (#1)
- `BotNavigationManager.java` — `resolveCurrentRegionId` inAir gate (#2); `resolveTarget` +
  `computeCommittedRoute` / `nextCommittedRouteEdge` / `skillAwareRoutePath` (#3)
- `BotEntry.java`, `BotMovementManager.clearNavigationState` — committed-route state (#3)
- Tests in `BotNavigationGraphProviderTest` (fast synthetic + Henesys WZ graph).

Related: [[kb_bot_nav_costs_and_anchors]], [[kb_bot_downjump_eligibility]],
[[kb_bot_town_nav_airborne_target]], [[kb_bot_navigation_architecture]].
