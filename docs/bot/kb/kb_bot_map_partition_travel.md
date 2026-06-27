---
name: Partition-aware cross-map travel (split maps)
description: Travel routes by arrival-platform so a bot stranded on a platform it can't walk off is routed the long way round instead of promised an unreachable portal. Reachability is BotNavigationGraph.canReach (SSOT); this layer only composes it across maps.
type: project
---
# Partition-aware cross-map travel

## Problem
`BotWorldGraph` collapses each map to ONE node (mapId -> target mapIds), assuming any portal in a map is
reachable from any other. False on **split maps**: an upper deck you down-jump off but can't climb back
to. A bot on the lower platform is promised `A->D` in one hop (D's portal is upper-only), the nav layer
fails the walk, and the bot stalls.

## SSOT: canReach is the intra-map partition
`BotNavigationGraph.canReach(startRegion, targetRegion, skillMask)` IS the per-map (directed) reachability
truth — memoized, used by the pathfinder, and explicitly replaced an undirected union-find to be
partition-correct. **This layer does NOT re-derive reachability.** An earlier version hand-rolled a
Kosaraju SCC over the same edges — a parallel implementation — and was removed. Everything reachability
flows through `canReach` (walk-only = skillMask 0, the s100/j100 "no skill" reference).

## What this layer adds: cross-map composition by ARRIVAL platform
When a bot enters a map it spawns at a portal (a platform); on a split map the exits it can then reach
depend on which platform it landed on. So the unit is `arrivalPortalName -> reachable cross-map exits`.

- `BotMapPartition` — per map: the cross-map exits + `arrivalPortalName -> reachable exit names`, every
  entry decided by `graph.canReach`. `fullyConnected` flag for the common non-split case (every arrival
  reaches every exit) — stored without the per-arrival map. Holds the SHARED portal-eligibility predicate
  `isTravelPortal` / `isTravelCrossMapPortal` (SSOT for "what the travel layer treats as a usable targeted
  portal": open, not a town door, real positive target map). **Positive-target scripted portals ARE allowed
  (intentional)** — the portal's own script still decides if entry succeeds, and a wrong landing just
  re-plans from wherever the bot ends up (each landing re-plans). Only script-only `tm=999999999` portals
  stay allowlist-only (`BotWorldGraph.SCRIPTED_ENTRANCES`). `serialize`/`deserialize` own the TSV format.
- `BotWorldPartitionRouter` — BFS over `(mapId, arrivalPortalName)` nodes. The START is the bot's live
  position; its reachable exits are passed in (computed by the caller via `canReach` on the loaded graph),
  so the current map needs no partition. Downstream maps expand from their (persisted) partitions. Two
  platforms of one map are distinct nodes → a route may re-enter a map it already left.
- `BotMapPartitionProvider` — lazy + incremental write-through disk cache
  (`cache/bot-partition/v{navVersion}/`, keyed by `BotNavigationGraphProvider.graphVersion()`). Builds via
  `canReach` on the warm reference graph; cold maps fall back to `fullyConnected` (today's behavior) and
  aren't persisted (self-heal). Persisted maps are served WITHOUT loading the map/graph. A **same-version**
  graph rebuild drops the stale partition: `BotNavigationGraphProvider.rebuildGraph` fires a
  `setGraphRebuildListener` hook → `invalidate(mapId)` (memory; disk row is superseded on next re-derive,
  last-row-wins). The hook keeps the nav provider free of any dependency on the partition layer.

## Layering (one-way deps; no cycle; NOT folded into BotWorldGraph)
`BotTravelManager -> BotMapPartition{,Provider}/BotWorldPartitionRouter -> BotNavigationGraph.canReach`.
The shared portal predicate lives in `BotMapPartition` (needs only `server.maps.Portal`) so deps flow
downward — the provider never depends on the travel manager. `BotWorldGraph` stays physics-free (Map.wz
scan only); partition logic is deliberately NOT merged into it (would couple the cheap structural graph to
the heavy lazy nav layer).

## Live wiring (`BotTravelManager.tickTravel`, plan branch)
`navGraph = peekGraph(base)`, `botRegion = findRegionId`. `canCheck = navGraph != null && botRegion >= 0`.
- Direct exit: reject when `isTravelCrossMapPortal` AND `!canReach(botRegion, portalRegion, 0)`.
- Partition routing runs for multi-hop planning whenever the current map graph is known, even if the bot's
  current platform can reach every local exit. A fully connected current map can still choose a bad next hop
  into a downstream split map's lower/one-way arrival platform (live repro: Admin looped
  `610010103 <-> 610010005`, entering Forgotten Path via `U6_3` instead of routing to the platform that can
  reach `U6_2 -> 610020000`). **Partition routing honors the SAME danger gate** (`routeBlockFor(bot)` passed
  into the router's `blocked` predicate) as the map-level route — never walks a fragile bot through a trap map.
  Falls back to `routeLookup`/`BotWorldGraph.route` (allowlisted scripted entrances, no graph, data gaps).
- Next-hop portal: **when `canCheck`, only ever a `canReach`-verified portal** — `findReachableAdjacentPortal`,
  else `reachableScriptedEntrance` (allowlisted entrance, also `canReach`-checked), else `tryConsumableHop`.
  It NEVER falls back to the unfiltered `adjacentOrScriptedPortal`, which could re-select the very portal
  `canReach` just rejected and collapse the split-map fix. Only `!canCheck` takes the old unfiltered pick.
`findAdjacentPortal` / `pickRandomCrossMapPortal` use the shared predicate (one filter, not three).

## Notes / deferred
- Live repro 2026-06-27, Dead Man's Gorge (`610010004`): the persisted partition row was correct
  (`U5_4/U5_5/U5_6` bottom arrivals cannot use top-left `U5_1 -> 610010005`), and `/api/pathfind`
  proved `R6 -> R4/R5` unreachable even with flash-jump enabled. The failure mode is not bad partition
  derivation; it is the travel planner's cold-current-map fallback. `tickTravel` uses
  `BotNavigationGraphProvider.peekGraph(base)` and, when it returns null / unknown region, falls back to
  the old direct map-level portal pick. That can pin `U5_1` before `canReach` is available, and the active
  travel branch does not revalidate the committed portal after graph warmup. Root fix: do not commit a
  cross-map portal on a split-sensitive route until the current map graph can prove reachability, or
  revalidate and clear/replan any active portal once the graph becomes available.
- `nextHopPortalPosition` (party-loiter hint) not canReach-filtered yet — low stakes; follow-up.
- No all-maps precompute; cache fills as maps are visited/warmed; unseen downstream maps degrade to
  fully-connected (map-level behavior).
- TSV assumes portal names are simple ASCII tokens (no tab/`;`/`>`/`=`/`|`) — true for MapleStory.
  Invalidation keyed to nav version only, not the Map.wz portal set (clear the dir if portals change
  without a nav bump).
- The illustrative `A>B>C>B>A>D` requires lower-A to NOT have a direct portal to the ramp map; the router
  returns whichever is actually shortest for the real topology.
- Tests: `BotMapPartitionTest` — 5-hop detour over hand-built partitions (re-entry via upper arrival,
  unreachable at maxHops=4, and pruned when the only via-map is danger-gated), serialize round-trip, and
  per-arrival derivation straight from `canReach`.
