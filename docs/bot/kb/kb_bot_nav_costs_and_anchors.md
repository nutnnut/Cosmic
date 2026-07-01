---
name: Bot Nav Graph - Cost Model, Anchors, Rope-Grab Semantics
description: Edge cost rules, anchor sampling strategy, rope-grab gating, same-region portal admission. Verified 2026-05-07 on graph version 41.
type: project
originSessionId: dd804ca4-394e-49cf-9e91-31f4b2696524
---
# Bot Nav Graph — Cost Model & Anchor Sampling

Verified on `experimental` branch as of GRAPH_VERSION 41 (2026-05-07).

## A* cost model

`BotNavigationManager.findPath` (line 781) runs A* with PER-EDGE cost plus per-node `intraRegionTravelCost`.

### Intra-region travel (`BotNavigationManager.intraRegionTravelCost`, line 1107)
- **Foothold region**: `dx_pixels * 1000 / walkVelocityPxs` (ms)
- **Rope region**: `dy_pixels * 1000 / CLIMB_SPEED_PXS` (ms)
- Heuristic uses the same function (line 1122) — admissible.

### Edge costs (`BotNavigationGraphProvider`)

| Edge type | Cost source | Notes |
|-----------|-------------|-------|
| `WALK` | `estimateWalkCost(start, end, profile)` line 2080 | walk speed × distance |
| `DROP` | `estimateLandingTimeMs` (gravity sim, cached in `JumpLandingCache`) | ms to fall |
| `JUMP` | `launchWindow.landingTimeMs` from simulated trajectory | includes air time + post-landing stability ticks |
| `CLIMB` (canGrab, line 1556) | `cfg.TICK_MS` (= 50 ms) | rope grab at same-Y. **Was 0 before 51db6a874 — caused tie-break loops** |
| `CLIMB` (canTopGrab, line 1562) | `cfg.TICK_MS` | grab from just above rope-top |
| `CLIMB` (canTopStep aka top-step-off, line 1692) | `cfg.TICK_MS` | rope-top → ground above |
| `CLIMB` (canTopStep down-jump, line 1568) | `estimateDownJumpRopeGrabTimeMs` | bot above rope, drops + grabs |
| `CLIMB` (canJumpGrab, line 1583) | `launchWindow.landingTimeMs` | running jump-grab from ground |
| `CLIMB` (rope-exit walk-off, line 1632) | `estimateRopeJumpLandingTimeMs` | jumping off rope onto ground |
| `CLIMB` (rope-to-rope, line 1662) | `estimateRopeJumpGrabTimeMs` | air-cross between two ropes |
| `PORTAL` | base **0** (addPortalEdges); chain-through-exit charged 250 ms in A* | a single teleport is instantaneous (edge.cost=0, SAME as v46 — graph content unchanged, NO version bump). The chain cost is path-dependent, applied in runSearch — see below. edge.cost IS charged generically in findPath A* (BotNavigationManager line ~889: `current.cost + intraRegionTravelCost + edgeCost`). |

**Two distinct portal anti-oscillation mechanisms (added together for the "takes portal twice" bug). Planning cost == runtime cost == 250 ms by design:**
1. **Planning cost (path-dependent, in `runSearch` A*)**: portal base edge.cost = 0. `SearchState` carries a `boolean viaPortal` (true when the state was reached by a PORTAL edge); it's part of the dedup key so a region reached both via-portal and not is explored under both costs. A viaPortal state's `point` IS the previous portal's exit. When relaxing a PORTAL edge: `enteredThroughExit = current.state.viaPortal && current.state.point.equals(edge.startPoint)`; `edgeCost = (isPortal && enteredThroughExit) ? PORTAL_USE_COOLDOWN_MS : edge.cost`. So 250 ms is charged ONLY when the bot lands on a portal and re-enters it without walking — i.e. the "return to old position" round-trip AND co-located A>B>C hops. A>B>walk>C>D is NOT charged (walking off the exit makes the entry points differ → stays free). Breaks the original cost-0 tie without penalising legitimate portals.
2. **Runtime cooldown** `PORTAL_USE_COOLDOWN_MS = 250L` (BotNavigationManager) — after `tryExecutePortal` succeeds, sets `entry.portalUseCooldownUntilMs = now + 250`; `tryExecutePortal` returns null while `now < portalUseCooldownUntilMs` (checked at top of the method, the single chokepoint for both `tryExecuteEdge` PORTAL case and `tryExecuteCommittedEdgeAfterGroundMovement`). Portal-ONLY gate: does NOT block movement/attacks/other actions. While on cooldown the committed PORTAL edge is retained (reuseCommittedEdge, `startRegionId==edge.fromRegionId`), bot idles ≤250 ms at the portal and retries — below the 500 ms stuck threshold (and ENABLE_UNSTUCK=false by default). Absolute-timestamp idiom (System.currentTimeMillis), like `alertedUntilMs`. Field `BotEntry.portalUseCooldownUntilMs`. NOT part of graph cache.

**Multi-portal sets (A→B→C→A):** both mechanisms handle them correctly. Planning: positive per-chain cost + A* gScore dedup means no infinite/useless cycling; a legitimate required chain is still chosen (250/hop is small vs a cross-map shortcut). Runtime: chains complete, ≤250 ms idle per hop, usually masked by inter-portal walk time.

### Cost-zero traps (HISTORY)

If any edge type has cost=0, A* can find equivalent-cost paths that include "useless" detours through that edge type. Even with PriorityQueue tiebreaking, equal-cost paths can pop in either order.

**Leroy 2026-05-07T081138 incident**: `CLIMB r=31 → r=163` (rope-grab) cost=0 + `CLIMB r=163 → r=31` (step-off) cost=0 + `PORTAL+DROP` matched the direct `PORTAL+DROP` cost. A* chose the rope-loop variant; bot oscillated between rope and foothold for 10+ s.

**Lesson**: any edge representing a real time cost should have non-zero cost, even if small. `cfg.TICK_MS` (50 ms) is the minimum semantically meaningful value (one physics tick).

## Anchor sampling (`BotNavigationGraphProvider.anchorPoints`, line 1866)

Anchor points are X positions on a region where the simulator is invoked to discover jump/drop edges. Per-region order:

1. `leftPoint` (region.minX)
2. `edgeInset` insets (≈ 8 px from edges) — `addAnchor(... ENDPOINT_ANCHOR_SPACING_PX)`
3. `jumpInset` insets (≈ ticksToApex × walkStep from edges)
4. **Interior loop**: every `walkStep` px from `minX+edgeInset` to `maxX-edgeInset`, dedup=0 (added 2026-05-07, commit af0ee4547)
5. Segment endpoints (slope joins)
6. `centerPoint` (when wide enough)
7. Thirds (when very wide)
8. `rightPoint` (region.maxX)
9. Feature Xs filtered to region range

`ENDPOINT_ANCHOR_SPACING_PX = 10` is the **dedup tolerance** in `addAnchor`, NOT a sampling step.

### Why dense interior sampling matters

`addJumpEdges` (line 1013) is per-anchor harvest: it simulates from each anchor with each `stepX ∈ {-walkStep, 0, +walkStep}` and lets the simulator decide the landing region. `expandJumpLaunchWindow` then binary-searches the launch window via `findJumpBoundary`. **Each contiguous launch window needs at least ONE anchor inside it to be discovered.**

Narrow launch windows (e.g., 7-15 px) sit in regions' interior for jumps over an "above-platform" or onto small intermediate platforms. Without interior densification at walkStep spacing, those windows have zero seeded samples and the edges go entirely undiscovered.

**Verified case (Clawer 2026-05-07T062632, map 103000000 r=114 base profile)**:
- r=114 → r=132 (intermediate platform): launch window x∈[940,946], 7 px wide.
- r=114 → r=137 (target through r=132): launch window x∈[948,962], 15 px wide.
- Without interior densification: zero anchors landed in either window → both edges missed → A* fell back to a 4-edge climb detour.

## buildFeatureXsByRegionId (line 1778)

Projects "above" features down to their below-region as feature anchor X's. For each:

- **Rope**: bottomY-1, topY-1, topY-2*JUMP_Y_THRESH → project rope.x down to region below at each Y.
- **Portal**: position.x, MAX_SNAP_DROP probe → project to region below.
- **Other in-map portal**: target portal's position.
- **Region endpoints (`projectRegionXsToRegionBelow`)**: leftPoint, rightPoint of EVERY non-rope region (changed 2026-05-07, was previously gated by `width ≤ 64`). Center is still projected only for narrow regions.

Projection uses `findRegionIdBelow(point.y + 1)`. The X is added to whichever region sits directly below at that X. **Caveat**: if the projected X falls outside the region-below's X range (e.g., r=132's right endpoint at x=886 falls outside r=114 which starts at x=935), the X is added to whatever IS at that X (here r=137), not the actually-relevant launch region. This is why **interior densification** is the more robust mechanism — projection alone doesn't always seed the correct region.

## Same-region portals (admitted 2026-05-07)

`addPortalEdges` line 1746 used to skip portals where `from.id == to.id`. That skip was removed in commit 51db6a874. Same-region portals (e.g., portal-pair within one wide platform) are now self-loop edges that A* consults whenever `walk-to-entry + portalCost + walk-from-exit < direct walk`.

**Patrol/roam interaction**: `BotCombatManager.findGrindCandidatesForPatrol` line 754 builds `adjacentIds` from `graph.getOutgoing(patrolId)`. Self-loop portal edges add `patrolId` to its own adjacent set — harmless duplicate, no new region IDs exposed. Bots in patrol/grind/roam modes will use intra-region portals automatically when shorter via A*.

## Rope-grab semantics — AI-committed only

Two attach paths in the bot, both gated:

1. **Airborne grab** (`BotMovementManager.successfullyGrabbedRope` line 362):
   - Triggers ONLY from airborne CONTINUE state.
   - Gated by `entry.climbUpIntent` (line 363).
   - Skips ropes in `entry.blockedRopeGrab`.

2. **AI-committed climb-entry** (`BotNavigationManager.tryExecuteClimbEntry` line 463 → `startClimbing` line 1216 → `BotPhysicsEngine.attachToRope` line 796):
   - Fires when AI commits a CLIMB edge with rope as `toRegionId`.
   - Requires `canExecuteClimbEntryFromCurrentPosition` (one of: at rope grab range, attach from top platform, grab from top platform with drop).

**There is no proximity-based auto-grab from grounded state.** The Leroy oscillation was NOT physics-driven; it was AI re-committing a stale rope-grab CLIMB edge each tick because of cost=0 path-tie. Cooldowns on `attachToRope` would NOT have helped — they'd block legitimate AI grabs without addressing the tie-break root cause.

## reuseCommittedEdge guards (`BotNavigationManager.reuseCommittedEdge` line 299)

Layered retire conditions in evaluation order:
1. `edge == null` or `targetRegionId < 0` → return null.
2. `!isEdgeUsable(graph, bot, edge)` → return null.
3. `entry.climbing && isRopeEntryEdge(graph, edge)` → return null (don't re-grab while already on rope).
4. `startRegionId == edge.toRegionId && !inAir && !climbing` → return null (edge complete and bot grounded).
5. `!inAir && !climbing && startRegionId == targetRegionId && edge.toRegionId != startRegionId` → return null (target snapped back to current region; old transition edge is stale).
6. `startRegionId == edge.fromRegionId` branch:
   - With `!inAir && !climbing && previousTargetRegionId != targetRegionId && edge.toRegionId != targetRegionId` → return null.
   - Otherwise → return edge (reuse).
7. `entry.climbing && (startRegionId < 0 || startRegionId != edge.toRegionId)` → return edge.
8. `entry.inAir` arc-keep branches for DROP/JUMP/rope-exit-jump.

The guard at #5 was added to break stale follow-target loops. Together with the cost-model fix (51db6a874), oscillation cases tied to equal-cost paths through ropes are blocked at the planning layer rather than via reuse logic.

## Cache invalidation

- `GRAPH_VERSION` constant at line 31 — bump when edge format or cost model changes. Disk cache is keyed at `cache/bot-nav/v{N}/`.
- Per-profile caches: each `(mapId, speedStat, jumpStat)` produces a separate graph file.
- Recent bumps:
  - 39 → 40 (af0ee4547): anchor densification + endpoint projection widening.
  - 40 → 41 (51db6a874): non-zero CLIMB cost + same-region portal admission.
  - Portal "take portal twice / return to old position" fix landed with NO version bump (stayed 46): graph portal edge.cost is 0 in both v46 and final. The fix is purely in runSearch (the `SearchState.viaPortal` enter-through-exit chain charge) + a runtime cooldown — neither touches serialized graph content. (Intermediate session attempts bumped to 47/48 with a flat per-edge PORTAL_COST_MS=100/0 but were reverted.)
  - 46 → 47 (382aa8938, 2026-06-11): forbidFallDown footholds stay SOLID during the down-jump grace window (live stepAirborne + simulateLanding + simulateRopeGrabCore — client semantics; previously a legal down-jump from above fell THROUGH a forbidden platform stacked below). Landing predictions change → rebuild.
  - 47 → 48 (fb44c9cad): info/fs reinterpreted as slipperiness (was bogus speed scale): walk step on fs maps back to full speed, runways ×1/fs.
  - 48 → 49 (48bcab404): down-jumps capped at DOWN_JUMP_MAX_DROP_PX=300 (client probes a bounded range below for a landing — Orbis 860px tower-rim drops were never flagged forbidFallDown). CONFIRMED EXACT: CUserLocal::FallDown @ 0x0094c4f8 probes Y+0x12c (300px) - see docs/bot/physics-client-audit.md.

## Graphgen perf: foothold collision/ground-probe index (0e73cfc75, GRAPH_VERSION 50)

Ellinia build 107.6s -> ~5s solo (edge set byte-identical, 5151). Two hot spots in airborne
physics shared by graphgen AND runtime: (1) wall/ceiling checks recomputed the full
collidable-wall classification over ALL footholds EVERY tick whenever the nav graph wasn't
cached — i.e. always, during builds; (2) every ground probe used FootholdTree.findBelow
(tree walk + LinkedList + sort + 2 atan + cos per sloped foothold), ~50M times per big map.
Fix: BotPhysicsEngine per-tree index (WeakHashMap keyed by FootholdTree IDENTITY — synthetic
test maps can't poison): collidable wall/from-below lists + 64px column buckets with
findBelowIndexed/pointBelowIndexed replicating findBelow/calcPointBelow math VERBATIM (trig
chain + int truncation = pixel-exact). GOTCHAS hit: Foothold.compareTo is a NON-TRANSITIVE
partial order — TimSort throws contract violations on 32+ elements (use stable insertion sort
per bucket; the tree never hit it because per-query xMatches lists are tiny);
FootholdTree.getAllFootholds() REBUILDS its list per call (recursive collect — never call it
on a hot path, even as a null guard); Mockito-stubbed maps/trees take a cached UNINDEXABLE
verdict and fall back to the original map.getPointBelow/tree.findBelow seams (physics tests
stub those).

## Jump launch variation (652b7733d)

Launch X selection was cached per edge and fired frame-perfectly → a borderline arc missed
identically on every retry. Now: (1) the cached launch X/deepen-count CLEARS when the jump
fires (one-shot — each retry re-rolls a fresh random point in the window), (2) at readiness the
bot rolls 0-2 extra walk steps carried PAST the selected X before firing (window- and
region-guarded; vertical and rope-anchored jumps skip). Fields: BotEntry.navJumpLaunchDelaySteps,
reset in clearNavigationState. Combined with navGraph identity invalidation + nearest-bucket
profiles, the overshoot-loop bug class has three independent layers.

## Stale-arc loop root cause: cross-graph committed edges (fixed 20856d3dc; clamp attempt 382aa8938 reverted)

Observed: edge stepX=-6 executed at walkStep 9 under Haste → jump overflew its landing window every
attempt → walk-back-and-rejump loop ("erratic movement"). Root cause chain: Haste flips the profile →
refreshMovementProfile clears nav state and warms the exact graph ASYNC → planning immediately re-runs
on `peekBestGraph`'s CLOSEST-profile fallback (e.g. s100, walkStep 6) → commits a -6-calibrated JUMP
edge → exact s140 graph finishes → nothing invalidated the committed edge on instance swap → executed
at full s140 walkStep 9.

FIX (user requirement: keep movement intent-based, never force artificial launch speeds):
- `BotEntry.navGraph` stores the graph instance the committed nav state was planned against;
  `BotNavigationManager.resolveTarget` clears nav state whenever the served instance differs
  (exact build finished, different closest fallback, rebuild). Edges never execute cross-graph.
- `resolveAirVelocityX` stays sign-only ±walkStep (full speed, like holding the arrow key) —
  correct because within ONE graph every directional jump edge is calibrated at exactly ±walkStep
  of that graph's profile. An interim clamp to the edge's |launchStepX| was REVERTED on user
  request (physics-bending: launch speed not derived from inputs).
- `BotMovementProfile.bucketStat` rounds to the NEAREST 5 (was floor): 144% → 145 graph,
  worst-case stat-vs-physics drift halved.
- Shop approach picks also race the map-change graph warmup: `BotEntry.shopTargetGraphChecked`,
  unvalidated picks re-done in tickShopVisit once peekBestGraph != null (reachability filter
  needs a graph); plus 500px interaction fallback for fenced booths (SHOP_FALLBACK_DIST).

## Test entry points

- `BotNavigationGraphProviderTest`: rebuilds graphs and asserts edge presence/cost.
- `BotNavigationManagerTest`: end-to-end findPath + resolveTarget assertions.
- `kerningGraph()` supplier (test) → map 103000000 base profile.
- Both regression cases live in BotNavigationGraphProviderTest: `shouldDiscoverJumpEdgesAcrossWideIntermediatePlatform`, `shouldNotPathThroughRopeOscillationLoop`, `shouldAssignNonZeroCostToSnapClimbEdges`.
