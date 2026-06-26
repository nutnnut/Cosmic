---
name: Bot nav search scaling
description: Runtime A* scaling decisions for large bot populations.
type: project
---
# Bot Nav Search Scaling

2026-06-26 changes for 1000+ bot scaling:

- `BotNavigationGraph.costToGoal(targetRegionId, skillMask)` keeps separate reverse-Dijkstra heuristic caches per movement-skill mask. Walk-only bots no longer get a heuristic that assumes teleport / flash-jump edges are usable.
- `BotNavigationManager.runSearch` uses the same skill mask for the reverse goal-distance map, the one-step heuristic exit scan, and the expansion outgoing list.
- Map graphs now serialize a compact portal route index: for every portal-containing target region, every source region, and 8 source-position buckets, the graph stores the precomputed next edge index. Runtime portal-target planning can use that table without running A*.
- The shared route cache key now includes start and target position buckets in addition to `(startRegion,targetRegion,routeBucket)`, and `ROUTE_CACHE_ENABLED` is back on for live testing.

Scope note: the per-search skill-mask heuristic is always active. Skill-capable bots still bypass shared walk-route cache decisions because MP/meso gates are per bot.
