---
name: Bot empty committed route replan storm
description: Empty committed routes mean direct walk, not missing route; treating them as stale causes repeated committed A* searches.
type: project
---
# Bot Empty Committed Route Replan Storm

Live perf on 2026-06-26 showed `pathfind-committed` at about 1.3 cores with hundreds of calls/sec while
the old shared route cache was disabled/irrelevant.

Root cause: `computeCommittedRoute` legitimately returns an empty list when the bot is already in the
target region and only needs direct intra-region walking. `nextCommittedRouteEdge` treated
`route.isEmpty()` the same as "no committed route", so `resolveTarget` recomputed the same committed
A* route every AI tick for stable same-region targets.

Fix: `BotEntry.committedRouteTargetPos` records the point the route was planned for, and
`committedRouteStillCoversTarget` treats an empty/exhausted committed route as valid while the bot is
already in the goal region and the target point is still nearby. If the target moves far enough, the
route is recomputed so same-region portal shortcuts can still be reconsidered.

Perf check after restart: compare `/api/perf?durationMs=10000` before/after. Expected primary drop is
`pathfind-committed` call rate/core; `nav-resolve` should also fall because it no longer wraps repeated
same-region A* searches.
