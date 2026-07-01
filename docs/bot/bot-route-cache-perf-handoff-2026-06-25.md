# Bot Route Cache / Pathfind Perf Handoff - 2026-06-25

## Context

User asked to review whether bot route caching should be removed or simplified, and whether Google Maps-style routing
techniques such as bidirectional search or Customizable Contraction Hierarchies make sense for the current bot
navigation graphs.

This handoff is based on local code review plus live perf samples from the running server on 2026-06-25.

## Live Perf Snapshot

Useful samples:

- `logs/bot-perf/bot-perf-1782391147638.csv`
  - `tick-total`: 3.76 cores
  - `tick-grind-dispatch`: 1.28 cores
  - `pathfind`: 0.87 cores, avg 0.53 ms, ~1629 calls/s
  - `combat-plan`: 0.78 cores
  - `tick-map-change`: 0.56 cores
  - `nav-resolve`: 0.29 cores
  - `pathfind-target-score`: 0.20 cores, avg 0.40 ms, ~498 calls/s
- Later live `/api/perf?durationMs=10000` sample:
  - process: ~1.93 cores
  - `tick-total`: 2.24 section-cores
  - `tick-grind-dispatch`: 1.51 section-cores
  - `combat-plan`: 0.99 section-cores
  - `pathfind`: 0.35 section-cores, avg 0.50 ms, ~709 calls/s
  - `pathfind-target-score`: 0.12 section-cores, avg 0.41 ms, ~300 calls/s
- `/api/botdebug` route cache stats at review time:
  - earlier: `192 hits / 5173 misses`, ~3.6% hit rate
  - later: `516 hits / 6672 misses`, ~7.2% hit rate

Interpretation:

- Pathfind is a real CPU consumer, especially during stressed windows.
- The displayed route-cache hit rate is low and includes some portal warmup/check noise, but it is still not evidence
  that the shared cache is buying much in the current primary route flow.
- `pathfind-target-score` is smaller than general `pathfind`, but still large enough to optimize after the main route
  replanning path.

## Current Route Cache Mechanics

Relevant files:

- `src/main/java/server/bots/BotNavigationGraph.java`
  - `routeCache` is a transient per-graph map from `(startRegionId, targetRegionId)` to bucketed next-hop slots.
  - `cachedNextHop(...)` increments static cumulative hit/miss counters exposed by `/api/botdebug`.
  - `putNextHop(...)` stores a single next-hop edge or `NO_EDGE`.
- `src/main/java/server/bots/BotNavigationManager.java`
  - `ROUTE_BUCKETS = 8`.
  - `findNextEdge(...)` uses `routeBucket(bot)` and reads/writes the shared `routeCache`.
  - Same-region targets bypass the cache because same-region/self-loop portal decisions are position-dependent.
  - Skill-capable bots bypass the shared cache and do two-pass walk-vs-skill route planning.
  - `warmPortalRoutes(...)` precomputes bucket-0 next hops between portal regions.
  - `computeCommittedRoute(...)` computes a full per-bot route from the bot's actual current position.
  - `nextCommittedRouteEdge(...)` follows the committed route by cursor.
- `src/main/java/server/bots/BotEntry.java`
  - `committedRoute`, `committedRouteTargetRegionId`, and `committedRouteCursor` are the newer per-bot route cache.

## Important Correctness Finding

The shared `routeCache` is position-blind:

```text
cache key = (startRegionId, targetRegionId, routeBucket)
```

But `runSearch(...)` starts from an exact `Point`, and the route cost includes the cost to walk from the current point
inside the region to each candidate edge launch point. Therefore the best first hop can differ for two bots in the same
region, with the same target region and same bucket.

This is already documented in:

- `docs/bot/nav-bucket-cache-regression-report-2026-06-25.md`
- `docs/bot/kb/kb_bot_nav_oscillation_rootcauses.md`

The known failure mode was adjacent regions caching mutually inconsistent next hops, e.g. `r45 -> r42` and `r42 -> r45`,
causing ping-pong. A fresh A* path display could show the correct path while live movement used the stale cached hop.

## Current Correct Direction

The newer committed-route approach is the right root-cause direction:

- Plan one route from the bot's own current position.
- Store it on `BotEntry`.
- Follow the route by cursor.
- Recompute when graph/profile changes, target region changes, the bot is knocked off route, or a stale edge gate
  refuses execution.

This preserves per-bot route diversity without serving a first hop computed from another bot's position.

## Should `routeCache` Be Removed?

Recommendation: remove or neuter `routeCache` as an authoritative live movement decision path, but do not blindly delete
all route caching.

Keep:

- `BotEntry.committedRoute` as the main route cache.
- Per-bot committed-route cursor logic.

Remove or demote:

- Shared `(startRegion,targetRegion,bucket)` next-hop decisions in live fallback routing.
- Any refresh path that can replace a committed-route edge from `findNextEdge`/`routeCache`.

Possible ways to handle the remaining `findNextEdge` fallback:

1. Simpler/safest: compute fresh from live position for uncommittable routes such as intra-region portal self-loops.
2. If still needed for perf: cache only non-authoritative hints, then validate against a fresh route before movement.
3. If using a key: include enough start-position detail, such as region-local x/y bucket and rope/air state. This reduces
   but does not eliminate risk near launch windows, and it adds complexity.

Memory/bloat note:

- `routeCache` RAM is probably not the biggest RAM problem. It is bounded in practice by region-pairs * buckets per graph.
- The bigger issue is correctness risk and low hit-rate payoff.

## Google Maps-Style Algorithms

### Bidirectional Search

Maybe useful later, not first.

Pros:

- Could reduce edge checks on long map-local searches.
- Much less invasive than contraction hierarchies.

Cons:

- Graph edges are directed and movement-specific: jump, drop, climb, portal, teleport, flash jump.
- Need reverse adjacency and care around asymmetric edge usability/costs.
- Current hot cost is repeated searches and dense edge checks, not enormous node count.

Verdict:

- Consider only after reducing search count and instrumenting expanded nodes/edge checks by caller.

### Customizable Contraction Hierarchies / Contraction Hierarchies

Not recommended.

Reasons:

- Our graphs are small by node count. Recent logs showed many maps around 20-60 regions; large examples were roughly
  176-290 regions.
- Edge counts can be high due to dense jump/drop candidates, but this is not a 64-million-node road-network problem.
- CH/CCH adds preprocessing, shortcuts, ordering, invalidation, reverse search, profile-specific cost handling, and
  skill-state complications.
- Movement costs depend on position inside a region and on bot state/skills/MP/meso in some paths.

Verdict:

- Almost certainly bloat for this codebase.

### Better Fit Than CH: Small-Graph Precompute

If algorithmic caching is needed after simpler fixes, prefer small-graph techniques:

- Per-graph/profile all-pairs region cost or next-hop table.
- Repeated Dijkstra/Floyd-Warshall style table for region-level lower bounds.
- Use as a heuristic/hint, then still patch with live start/end intra-region costs.

This matches the actual graph size much better than CH.

## Recommended Next Work

1. Split pathfind perf counters by route caller.
   - Need separate sections for committed-route replan, fallback `findNextEdge`, skill walk pass, skill-enabled pass,
     target-score, and portal warm.
   - Current `pathfind`/`pathfind-target-score` tells us the total cost but not which route path is responsible.

2. Remove shared `routeCache` from authoritative live fallback.
   - Keep committed route as SSOT.
   - For the uncommittable intra-region portal case, run a fresh live-position search instead of using a shared cached
     next hop.

3. Reduce route query count before changing the search algorithm.
   - Avoid replanning while target region is unchanged and the committed route remains valid.
   - Memoize target-score route checks within a scoring pass by `(graph instance/profile, startRegion, targetRegion)`.
   - Throttle target-score pathfind for candidate regions that were recently scored from the same start region.

4. Improve route-cache stats if the old cache remains temporarily.
   - Split hits/misses by caller: portal warm, fallback movement, probe/debug.
   - Add a reset endpoint or sampled-window stats, because static cumulative counters hide current behavior after warmup.

5. Consider edge-density optimization before bidirectional search.
   - Slow pathfind logs show millions of edge checks on large/dense maps despite modest region counts.
   - Investigate outgoing edge pruning, duplicate-equivalent jump/drop edges, and direct dominated-edge removal.

6. Only after the above, benchmark bidirectional Dijkstra/A*.
   - Use slow-pathfind profiles: expanded nodes, edge checks, openPeak, map id, caller.
   - Require side-by-side result-cost equality before enabling in production.

## Test / Verification Suggestions

Focused tests worth adding or preserving:

- Same `(startRegion,targetRegion,bucket)` from different x positions can choose different first hops; shared next-hop
  cache must not command movement.
- Committed route survives edge clear/landing and advances forward.
- Committed route with repeated region ids follows cursor order, not first matching `fromRegion`.
- Uncommittable intra-region portal fallback computes from live position.
- Target-score memoization returns the same decision as uncached search within a scoring pass.

Perf verification:

- Run `/api/perf?durationMs=30000` after each change.
- Compare:
  - `pathfind` core
  - `pathfind-target-score` core
  - `nav-resolve` core
  - `tick-grind-dispatch` core
  - `/api/botdebug.routeCache` if still present
- Watch logs for `Slow bot pathfind` on maps like `120000000`, `103000000`, `600020100`, and `261000000`, which showed
  large edge-check counts in recent samples.

## Current Local Worktree Caveat

At the time this handoff was written, the worktree already had unrelated or prior dirty files. Do not assume all dirty
files belong to the route-cache review.

Known modified files observed recently included:

- `src/main/java/server/bots/BotManager.java`
- `tools/botperf_report.py`
- `src/main/java/server/bots/BotAutopilotManager.java`
- `src/test/java/server/bots/BotAutopilotManagerTest.java`
- `bash.exe.stackdump`

Before committing any route-cache work, re-check `git status --short` and inspect diffs carefully.

