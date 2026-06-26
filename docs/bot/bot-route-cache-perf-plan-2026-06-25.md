# Bot Pathfind Perf — 2nd-Opinion Plan (2026-06-25)

Continuation of `bot-route-cache-perf-handoff-2026-06-25.md`. Same-day live re-sample +
control-flow trace. **Net: agree with the diagnosis, disagree with the priority order.**

## Fresh live sample (`/api/perf?durationMs=10000`)

- process 2.85 cores; `tick-total` 2.40; `tick-grind-dispatch` 1.42
- `pathfind` **0.54 cores, 1156 calls/s, avg 0.46 ms** ← dominant nav cost
- `pathfind-target-score` 0.15 cores, 436 calls/s, avg 0.35 ms
- `nav-resolve` 0.60 cores; `combat-plan` 0.61
- routeCache: 764 hits / 6770 misses = **10%**

## Two corrections to the handoff's framing

1. **`runSearch` is already gated behind the committed-route SSOT.** Per-tick path is
   `resolveTarget → nextCommittedRouteEdge` (O(1) cursor, no A*). `runSearch` fires only on
   replan (route null/exhausted, target-region change, knocked-off, stale-edge give-up).
   `findNextEdge`/`routeCache` is a **rare fallback** for *uncommittable intra-region portal
   loops only*; same-region already bypasses the cache. → The position-blind cache risk the
   handoff worries about is **already mostly neutralized.** Removing `routeCache` is a
   **simplicity/SSOT cleanup, not a perf win** — don't expect cores from it.

2. **The 1156 `pathfind` calls/s are unattributed by our own choice.** `recordPathfind(caller,
   ns)` already routes `pathfind-<caller>` sections (`BotPerformanceMonitor.java:552`), and
   `target-score` is already split. The committed-route `findPath` (`BotNavigationManager.java:1434
   → 1587`) and the two fallback `findPath` calls (1292, 1304) pass `null` caller, so they all
   collapse into one bucket. **We are optimizing blind.** Labeling them is ~4 string literals.

## Step 0 RESULT — caller split landed (server restarted, 3× warm 10s samples)

~400–600 bots live (drifting down over the window). `enabled=false`; use **≤10s** `durationMs`
windows — 30s on-demand windows return empty/cold garbage.

| section | core | calls/s | avg ms | read |
|---|---|---|---|---|
| `pathfind-committed` | ~0.04 | **440–550** | **0.08** | most calls, but cheap. ~1.3 replans/s **per bot** |
| `pathfind-target-score` | **~0.075** | 167–180 | **0.43** | **most CPU.** 5× costlier per search than committed |
| `pathfind` (residual) | 0.00–0.04 | 44–91 | 0.05–0.42 | catch-all: `BotTravelManager:921` reach-filter + `BotManager:2714` retreat, both loop-searches |
| `pathfind-fallback*`, `skill-*` | 0 | 0 | — | **dead.** routeCache fallback path is not exercised |
| `pathfind-warm` | ~0 | 2 | 0.13 | one-time per graph |

Conclusions:
- **target-score is the #1 pathfind CPU sink** (per-call cost, not count) → **Step 1 confirmed as next.**
- **committed is high-count but individually trivial** (0.08 ms). 1.3/bot/s is churny but cheap; not worth chasing unless target-score is done and it still shows. No algorithm swap needed.
- **routeCache / findNextEdge fallback fires ~0 times** (5% hit rate is all warmup; 4000 misses are warm probes). Confirms Step 3: routeCache is **dead weight — pure cleanup, zero perf**.
- Absolute pathfind cores (~0.13 family) are far below the pre-restart 0.69 — that was a stressed window; compare *ratios*, not absolutes, across restarts.

## SPIKE INVESTIGATION (priority pivot — avg CPU is fine ~20-30%, spikes hit 100% → bot stutter)

~70s sample focused on `maxMs`/`slow` + slow-pathfind log. Spikes are a **different problem** from
average pathfind cost. Three classes:

1. **Unbounded pathfind megasearches (the main one).** Single A* calls **250ms–6900ms**, up to
   **3.4M edge checks**. Many *fail* (`resultEdges=0`, `bestGoalCost=-1`) = exhausted the whole graph
   proving a target unreachable. Run **synchronously on bot-tick worker threads** → one 1.5s search
   freezes every bot on that worker. Worst offender `caller=default` (BotTravelManager:921 reachability
   loop + BotManager:2714 retreat) runs **h=0 Dijkstra** (routeSeed=0 → `hValue`=0, no goal direction).
   Per-bot searches (committed/target-score) use weighted `epsilon·intraRegionTravelCost` so prune more,
   but still spike to ~1.5s on dense maps (120000000=290r, 103000000=186r) and can't beat the unreachable
   case (no heuristic proves non-existence).
2. **Map-change stalls** — `tick-map-change max=3882ms` (one ~4s freeze). First-visit nav-graph build +
   `warmPortalRoutes` (an A* **per portal-pair, O(P²)**). Separate, one-time-per-map.
3. **GC pauses** — one window had *everything* slow at once (combat-plan 469, passive-loot 445,
   move-ground 348, 18 slow ticks). Stop-the-world GC, fed by pathfind's millions of per-node allocs.

### Fix shipped — hard search bound (root cause of #1, also relieves #3)
`runSearch` had **no bound**: `while(!open.isEmpty())` runs to full-graph exhaustion. Added
`MAX_EDGE_CHECKS = 160_000` (~100ms at ~1.6M checks/s): on exceed the loop breaks and returns
best-effort (cheapest goal reached, or empty → caller retries / picks a nearer target). Capped searches
are surfaced via the existing `Slow bot pathfind … capped=true` log (force-logged even when <250ms).
`MAX_EDGE_CHECKS` is `static` non-final → live-tunable. Tradeoff (user-chosen ~100ms): bots fail to
route the densest 186–290-region cross-map paths and fall back; everything else unaffected; movement
still planned fresh from live position.

**Verify live (after restart):** slow-pathfind `took` values should cap near ~100ms, multi-second lines
vanish; watch `capped=true` frequency + which maps/callers; confirm `tick-grind-dispatch`/`tick-total`
`maxMs` drop. Maps to watch: 120000000, 103000000, 101000000, 261000000.

### Fix shipped — best-effort partial progress on cap (committed-route only)
A capped search returns the path to the **closest reached frontier** (min raw distance-to-target) instead
of empty — but ONLY for movement callers (`committed`/`skill-walk`/`skill-jump`, via `bestEffortCaller`).
Scoring/reachability callers stay strict (empty on cap = "too far", correct ranking signal). So a bot
heading to a far-but-reachable goal walks partway and the next (nearer, cheaper) search makes more
progress, instead of stalling. `runSearch` tracks `closestState`/`closestH` per search.

### Fix shipped — directed reachability early-exit
`BotNavigationGraph.canReach(startRegion, targetRegion, skillMask)`: lazy per-source forward-BFS over
the region graph, memoized per `(skillMask, startRegion)`. `runSearch` early-exits with empty when the
target is not forward-reachable from the start for the bot's usable edges. Kills the unreachable-target
exhaustion (the `resultEdges=0`, `bestGoalCost=-1`, `capped=true` spam from high-fan-out start regions
with 500+ outgoing edges) by deciding reachability in O(V+E)-once-then-cached instead of letting A* burn
the 160k edge-check cap every tick to fail.
- **Directed**, unlike the old undirected union-find: a one-way DROP/JUMP into a region no longer makes
  it look reachable from the other side (the union-find's known directional-unreachable gap that the cap
  used to absorb).
- **Skill-filtered:** `skillMask` (bits `SKILL_TELEPORT`/`SKILL_FLASH_JUMP`, built from `skillsEnabled
  && hasTeleport/hasFlashJump`) gates the exact skill edges the bot can use — finer than the old 2-state
  base/skill split, which assumed a skill bot had both.
- **PORTAL treated as always usable** (the map is static — portals are structural links), so the
  reachable set is a *superset* of what the real per-edge-filtered search traverses. "Not reachable" is
  therefore a sound NO (safe early-exit); "reachable" just means "run the search". No TTL / portal-status
  listener needed: reachability varies only with the bot's movement profile, which is already baked into
  the graph instance (speed/jump via `GraphCacheKey`) plus `skillMask`.
- **Best-effort redirect (unreachable target):** only the per-tick movement executor (`"committed"`)
  redirects to `nearestReachableRegion(start, skillMask, targetPos)` — the reachable region strictly closer
  to the target than the start — and paths there, so the bot walks AS CLOSE AS POSSIBLE (into NPC/portal
  interaction range for the stuck-near fallback) instead of stopping dead. The redirect region is
  known-reachable from the uncapped BFS set, so the A* resolves it without burning the edge-check cap —
  unlike the old cap-then-return-`closestState`, which on high-fan-out maps capped *before* discovering the
  nearer frontier (the 102040000 NPC-1072003 job-instructor stall: bots parked at the map-entry wall with
  `no-path` instead of walking to the reachable ledge ~262px below the NPC). Returns -1 / empty only when
  nothing reachable is closer than where the bot already stands. Every other caller gets the clean empty
  "no path": scoring/approach-probe (`null` caller) must rank it unreachable, and the `skill-walk`/
  `skill-jump` cost-comparison searches must keep their true unreachable cost (a redirected cheap partial
  would hide that walking can't reach the target and suppress the teleport route). Those still fall through
  to the existing cap-then-`closestState` best-effort path, unchanged.
- **Synergy:** reachability-miss redirects/returns *pre-search* (no full-graph scan); best-effort heads to
  the nearest reachable frontier deliberately rather than as a cap artifact. Disjoint.
- Test: `BotNavigationManagerTest.reachabilityIndexIsDirectedAndSkillFiltered`.

### Spike follow-ups (not yet done)
- **Give `caller=default` a heuristic** (1 line): travel-reach/retreat use h=0 Dijkstra; let them use the
  weighted heuristic so reachable-case searches finish in ~5-10k checks instead of burning to the 160k cap.
- **Bound/throttle `warmPortalRoutes`** (#2): O(P²) searches on first visit to 290-region maps → the
  ~4s map-change stall. Cap each warm search (already labeled `pathfind-warm`) and/or make it lazy.

## Plan (stop at the first rung that pays)

**Step 0 — Label the callers, re-sample. (tiny, do first)**
Pass distinct caller strings: `"committed"` at 1434/1587, `"fallback"` at 1304, `"same-region"`
at 1292, `"skill-probe"` at 1599. Re-run `/api/perf?durationMs=30000`. Also divide each by live
bot count → **calls/s per bot**. This single cheap change tells us whether 1156/s is (a) many
bots legitimately replanning, or (b) committed routes invalidating too often (oscillation /
knocked-off churn). Everything below is speculation until this lands.

**Step 1 — target-score dedup. (concrete, measured, do regardless)**
`graphPathCost` fires one A* per mob, but candidates collapse to a handful of target regions
(`BotCombatManager.java:~1879`, `BotNavigationManager.findPathForTargetScore:1612`). Memoize
within a scoring pass by `(graph instance, startRegionId, targetRegionId)` and group mobs by
region before searching. 0.15 cores → expect a real cut. Verify: memoized decision == uncached.

**Step 2 — only if Step 0 shows `committed` dominates:**
The guard against recomputing on unchanged target already exists, so a high `committed` rate means
**churn**, not raw demand. Root-cause the invalidation (oscillation, knocked-off thrash) — see
`kb_bot_nav_oscillation_rootcauses.md`. Do **not** swap the search algorithm to paper over churn.

**Step 3 — routeCache: demote/delete as cleanup, low priority.** Committed route is SSOT;
fallback can compute fresh from live position for the uncommittable-portal case. Reduces surface,
not cores. Bundle with Step 0 if touching the file anyway.

**Deferred (agree w/ handoff): bidirectional / CH / edge-pruning.** Premature until Step 0 names a
caller whose *per-search* cost (not count) is the bottleneck. CH = bloat for 20–290-region graphs.

## Verify after each step
`/api/perf?durationMs=30000` deltas on `pathfind*`, `nav-resolve`, `tick-grind-dispatch`;
`/api/botdebug.routeCache`; watch `Slow bot pathfind` on 120000000 / 103000000 / 600020100 / 261000000.
