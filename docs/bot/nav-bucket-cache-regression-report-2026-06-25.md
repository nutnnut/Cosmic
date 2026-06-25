# Bot Movement/Nav Regression Report - 2026-06-25

Context: review movement/navigation changes since
`b311289d98513d51082524a4fd17693d8b597d03` (`perf(bots): lazy region-route cache to kill runtime A* on the hot path`).
User-reported symptoms: bots ping-ponging between regions since the bucket cache change; later ladder fixes still not
solving the live issue; evidence file `logs/bot-nav/pathlog-duiuganda-2026-06-25T053502.txt`.

## Executive Summary

The bucket cache introduced in `b311289` is not valid for live next-hop decisions as currently keyed.

It caches by `(startRegion, targetRegion, bucket)`, but the best first hop out of a region depends on the bot's exact
current position inside `startRegion`. A* charges the walk cost from the current search point to each candidate edge
launch point, so two bots in the same region and same route bucket can legitimately need different first hops.

This makes the cache position-blind. Once one bot/position populates a hop, another bot/position can be served a hop
that is wrong for its current x/y. Adjacent regions can then hold mutually inconsistent cached hops, such as
`r45 -> r42` and `r42 -> r45`, creating region ping-pong.

Verdict: the current bucket cache method is not appropriate for runtime next-hop routing unless the cache key includes
enough start-position information, or unless the cached object is a route computed and followed from a specific bot's
actual start state. The later "commit one full route per bot" approach is the right root-cause direction.

## Root Cause Details

Relevant implementation points:

- `src/main/java/server/bots/BotNavigationGraph.java`
  - `routeCache` stores cached next-hop edges per `(startRegionId, targetRegionId)` with bucket slots.
  - `cachedNextHop(...)` and `putNextHop(...)` do not include start position or launch-point proximity.

- `src/main/java/server/bots/BotNavigationManager.java`
  - `SearchState` includes an exact `Point`, not just a region.
  - `runSearch(...)` starts from `new SearchState(startRegionId, new Point(startPos), false)`.
  - Goal and edge relaxation costs include `intraRegionTravelCost(...)` from the current point to edge start/end
    points.
  - Therefore the first hop is a function of `(region, target, current point, route seed/skill state)`, not only
    `(region, target, bucket)`.

The original cache in `b311289` intentionally replaced per-bot/per-position searches with shared bucket searches.
That is where correctness was lost. Buckets preserve route diversity, but they do not make route choice
position-independent.

## Is The Cache Salvageable?

Only with constraints.

Safe options:

- Remove `routeCache` from live `findNextEdge` decisions and compute a route from the bot's current position when a
  route leg starts.
- Cache complete committed routes per bot/journey and follow the sequence until invalidated.
- Include a start-position component in the cache key, such as region-local x bucket plus y/rope state. This reduces
  but does not eliminate risk unless the bucket is fine enough around launch windows.
- Use the current cache only for non-authoritative hints, measurements, warming, or scoring where a wrong next hop
  cannot directly command movement.

Unsafe option:

- Continue using `(startRegion, targetRegion, bucket)` as an authoritative next-hop cache for runtime movement.

## Later Fix Direction

The existing later fix direction is conceptually correct:

- Store `BotEntry.committedRoute`.
- Compute it from the bot's own current position and route seed.
- Follow the planned hop sequence instead of recomputing per region.
- Recompute only when the goal region changes, graph/profile changes, or the bot is knocked off the route.

Current uncommitted worktree also adds:

- `BotEntry.committedRouteCursor`
- cursor-based route following in `nextCommittedRouteEdge(...)`
- `clearNavigationState(...)` no longer clearing `committedRoute`
- explicit `clearCommittedRoute(...)` on true replans

That cursor idea addresses a plausible follow-up bug: a valid A* route can revisit the same region at different points.
Following by "first edge whose fromRegion == currentRegion" can alias a later route visit onto an earlier hop and bounce
between regions. A cursor follows the hop sequence instead of just matching region ids.

This should be covered by tests before trusting it in production.

## Separate Finding From Provided Pathlog

The provided file `logs/bot-nav/pathlog-duiuganda-2026-06-25T053502.txt` does not show an active ladder/CLIMB
oscillation.

It shows:

- bot starts in `r45`
- bot reuses `DROP r45 -> r48`
- bot lands in `r48`
- target becomes same-region grind-wander `(3627,394)`
- bot stops at `(3578,394)`, so `dx = 49`
- nav decision becomes `same-region`
- edge is `none`

This is not route-cache ping-pong and not ladder execution. It is a same-region ground movement stall.

Likely immediate cause:

- `BotMovementManager.calcStepX(...)` returns zero when `!wasMovingX && absDx <= followDist`.
- Normal non-precise ground movement passes `followDist = cfg.FOLLOW_DIST`, currently 80.
- The bot is 49 px from the grind-wander target, inside `FOLLOW_DIST`, so if it is no longer moving it refuses to
  restart even though the grind target is not a follow-spacing target.

This means follow hysteresis is being applied too broadly. It is appropriate for owner/follow spacing, but not for
grind-wander, travel, or other explicit same-region movement targets.

Recommended fix for this separate issue:

- For non-follow movement targets, especially grind-wander/travel/local objective targets, use `followDist = stopDist`
  or another small restart threshold instead of `FOLLOW_DIST`.
- Keep `FOLLOW_DIST` hysteresis for actual owner-follow spacing.

## Ladder Status

The duiuganda log is insufficient to confirm or reject the ladder fixes. It contains no active `CLIMB` edge and no
climbing state in the tick history.

For ladder/root-cause review, capture a pathlog while the bot is visibly oscillating on or around a ladder/rope and
confirm the history includes one or more of:

- `edge=CLIMB ...`
- `Physics: CLIMB`
- `entry.climbing`
- repeated rope/ladder region transitions
- `nav=reuse`, `nav=exec`, or `lastEdgeBlockReason` around the ladder edge

Without that, the current provided evidence points to same-region ground hysteresis after a drop, not ladder logic.

## Recommended Engineering Fixes

1. Stop using the position-blind bucket route cache as an authoritative runtime next-hop cache.
2. Preserve the per-bot committed-route approach; it is the root-cause fix for the ping-pong class.
3. Keep a route cursor so repeated-region routes are followed by sequence, not by region match alone.
4. Define precise invalidation:
   - graph instance/profile change
   - target region change
   - map change
   - stale edge execution gate give-up
   - route cursor mismatch / bot knocked off route
   - movement-skill eligibility changing for a committed skill edge
5. Fix same-region movement target hysteresis so grind/travel/local targets do not inherit owner-follow `FOLLOW_DIST`.
6. Add regression tests:
   - position-dependent first hop from the same region should not use a shared cached hop
   - committed route survives landing/edge clear and advances forward
   - committed route with repeated region ids follows cursor order
   - same-region grind target at `STOP_DIST < dx < FOLLOW_DIST` restarts walking
   - ladder/CLIMB oscillation test once a representative pathlog is captured

## Bottom Line

The bucket cache is the root cause of the cross-region ping-pong class because it caches a position-dependent routing
decision without position in the key. It should not be used as-is for live movement.

The provided duiuganda pathlog shows a separate same-region ground movement bug: follow-distance hysteresis prevents
grind-wander movement from restarting 49 px away from the target. That should be fixed independently and should not be
mistaken for a ladder-cache issue.
