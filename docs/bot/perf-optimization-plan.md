# Bot perf optimization plan — 615-bot stress capture (2026-07-04)

Status: **MEASURED, NOT IMPLEMENTED.** This doc is the plan of record for the follow-up
implementation session. KB summary: `docs/bot/kb/kb_bot_perf_stress_hotpaths.md`.
Owner directive: **assume warm caches everywhere except the nav-graph build** (the only cold
cache worth optimizing — long builds × many map/stat variants). Prefer lossless / no-behavior-change
optimizations; lossy simplifications are acceptable when nobody observes the bot and the win is big.

## 1. Measurement setup

- Branch `dev-economy`, live server (started 14:30 same day), **~615 bots** (scheduler 40x,
  target 600), **0 real players**, 6-core Windows box, JDK 21.
- `GRAPH_VERSION` freshly bumped to **70** → nav disk cache cold: 715 graph files at capture
  vs ~9,700 accumulated at v66 → ~90%+ of (map × movement-profile) graphs still to build.
- Artifacts:
  - 60 s BotPerformanceMonitor window: `logs/bot-perf/bot-perf-1783151267251.csv` (+`.html`)
    via `POST /api/settings {"cmd":"perflog","seconds":"60","html":"true"}` (values must be
    JSON **strings** — the endpoint's parser rejects bare numbers with `bad seconds`).
  - 12 × jstack samples @4 s apart (scratchpad, summarized below).
  - `jstat -gcutil`, process-CPU delta sampling, `/api/botdebug` routeCache, cache-dir census.

## 2. Headline numbers

| Metric | Value |
|---|---|
| Process CPU | ~4.5 of 6 cores |
| Bot tick work (`tick-total`) | **3.25 cores**, 13.5k ticks/s (615 bots × ~22/s), avg 0.24 ms |
| Nav-graph warmup threads | 2 (normal+fast), **both pegged** the whole capture |
| Graph build throughput | ~15 new graphs / 30 s across both threads ⇒ ~4 s/graph incl. WZ map load |
| DECIDE_POOL (`bot-grind-advisor`, 1 thread) | **pegged 12/12 jstack samples** in `BotScrollManager.bestChaosPlay` → `BotScrollValuer.costFrom`; **0** `scroll-scan`/`chaos-scan`/`autopilot-decide` completions recorded in 60 s |
| GC | ~1% of wall time steady; old gen ~93% full; sporadic 1.2–1.4 s max spikes visible across sections |
| TimerManager | 6 workers on 6 cores, shared bot ticks + all game timers |
| routeCache | 0 hits / 17 misses since boot (barely exercised) |
| Stale nav cache dirs | v10–v69 ≈ **3.9 GB** dead disk, never cleaned |

Top instrumented sections (60 s window, share of `tick-total`; sections NEST — grind-dispatch
wraps combat, step-movement-core wraps nav-resolve+move — do not sum naively):

| section | cores | share | cps | avg ms |
|---|---|---|---|---|
| move-ground | 0.551 | 16.9% | 8,057 | 0.068 |
| tick-grind-dispatch | 0.286 | 8.8% | 1,887 | 0.152 |
| common-combat-buffs | 0.261 | 8.0% | 6,775 | 0.039 |
| nav-resolve | 0.171 | 5.3% | 9,303 | 0.018 |
| combat-plan | 0.119 | 3.6% | 1,702 | 0.070 |
| combat-target-search | 0.113 | 3.5% | 3,689 | 0.031 |
| common-mob-damage | 0.105 | 3.2% | 13,549 | 0.008 |
| potion-recovery-scan | 0.072 | 2.2% | 8,635 | 0.008 |
| pathfind-* (all live A*) | <0.11 combined | ~3% | — | — |

## 3. Ranked optimization candidates

### P0 — Scroll/chaos valuation DP saturates the decide thread (warm; biggest pathology)

**Evidence.** The single `DECIDE_POOL` thread spent the entire capture inside
`bestChaosPlay` (`BotScrollValuer.costFrom`/`productionCost`/`reproductionValue`,
`BotScrollManager.chaosOutcomeMeanValue`/`equipMarketQuote`), and **no** off-tick section
completed in 60 s ⇒ single valuations run for minutes and everything behind them
(grind/party decides, scroll plans) starves. This is the cold-decide GC-storm class of bug
(see `kb_bot_cold_decide_gc_storm`) reborn as a warm always-on cost.

**Root causes** (`BotScrollValuer.java`, `BotScrollManager.java:1769-1867`):
1. `equipMarketQuote` builds a **fresh** `reproductionValue` curve per call — the per-curve
   memo dies with the quote. Curves are deterministic per
   (itemId, tuc, scroll-spec set, cleanBaseCost) and bots share item types.
2. `productionCost` runs `RESTART_ITERS = 120` full DP passes (each rebuilding the memo)
   to solve a 1-D fixed point that converges geometrically — an epsilon exit
   (|ΔD| < 1 meso, say) should land in ~5–15 iterations. Numerically identical to tolerance.
3. `chaosOutcomeMeanValue` Monte-Carlo queries the curve at **128 fractional band scores**
   per piece → nearly every sample is a distinct rounded key → a fresh full DP each.
   Precompute the curve on a fixed band grid (e.g. 0.05 band) once and interpolate: MC is
   already sampling noise, so grid interpolation is semantically negligible (borderline lossy,
   flag in review).
4. Boxed `HashMap<Long,Double>` memo churn (allocation pressure on a 93%-full old gen).

**Plan (lossless first):**
- Process-wide curve cache keyed (itemId, tuc, scrollSpecs hash, round(cleanCost)) in
  `BotScrollManager` (SSOT — same cache serves scroll plans, chaos plays, market quotes).
  Invalidate never (WZ static) or on consensus-price epoch if `marketReproSpecs` embeds live
  prices — **check `marketReproSpecs`/`scrollPriceMeso` volatility first**; if prices drift,
  key on a coarse price epoch (e.g. refresh curve when inputs move >10%).
- Epsilon early-exit for the restart fixed point (`RESTART_ITERS` becomes a cap).
- Curve-on-grid + interpolation for the MC (or quantize MC sample scores to the grid).
- Coalesce scans: skip scheduling a scroll/chaos scan if one is already queued for that bot
  (unbounded FIFO on a single thread today).
- Keep `scroll-scan`/`chaos-scan` labels; add one around a single curve build so the CSV can
  prove the fix.

**Verify:** chaos-scan completions appear in a 60 s CSV with sane avg (<100 ms); a triggered
`grind profile` run shows decide latency back to ~20 ms warm; DECIDE_POOL no longer pegged in
jstack.

### P1 — Nav-graph cold build (the one cold cache that matters)

**Evidence.** Both warmup threads pegged; ~4 s per graph (incl. on-demand WZ map load);
~9k graph files to converge ⇒ **days** of 2-core background churn after every
GRAPH_VERSION bump. jstack top frames: jump/flash-jump launch-window expansion
(`expandFlashJumpLaunchWindow`, `isValidFlashJumpLaunchX`, `addJumpEdges`) and landing sims
(`resolveAirCollision`, `landingAtX`, `findGroundCollision`, `simulateLanding`) plus
`StrictMath.atan` from `slopeYAt`.

**Plan:**
1. **(lossless, also helps live physics)** `BotPhysicsEngine.slopeYAt` (line ~2750) computes
   `atan/atan/cos/cos` per call, but α/β are **per-foothold constants**. Cache
   `cos(alpha)`/`cos(beta)` per Foothold (field or identity map), keep the exact expression
   `cos(alpha) * (s4 / cos(beta))` with the cached values → bit-identical int truncation.
   Hits both graph build and `move-ground` (P2).
2. **(lossless, measure first)** Use `GraphBuildReport` phase timings (already collected —
   `LAST_BUILD_REPORTS`) to attribute build time across drop/jump/FJ/rope phases and the
   jump-landing cache hit rate before deeper micro-work. Consider exposing the report via a
   web endpoint if not already readable.
3. **(lossless, ops)** Offline bulk prebuild: enumerate (mapId, speed, jump) keys from the
   v66/v69 cache dir filenames, build+persist v70 graphs in a batch run (graphs are
   deterministic; the disk cache is portable). Could run off-hours or on another machine.
   Kills the multi-day churn after every version bump.
4. **(lossless, conditional)** Additional build threads only with demonstrated idle headroom —
   the box runs ~4.5/6 cores under load; adding builders now would steal from ticks. Gate on
   measured idle (e.g. 2→4 threads when process CPU < 60%). P0 landing frees ~1 core.
5. **(lossy, biggest lever — owner decision)** Quantize movement profiles for graph keying
   (e.g. speed/jump to steps of 5–10): v66 held ~9,700 files for ~700 maps ⇒ ~14 profile
   variants per map. Quantizing collapses most variants (est. 3–5× fewer builds). Behavior
   change: a bot navigates on a slightly-off profile graph; the nav stack **already** runs on
   closest-profile fallback while the exact graph builds, so the risk envelope is known and
   bounded. Needs owner sign-off + a nav regression pass.
6. **(lossless, trivial ops)** Delete stale `cache/bot-nav/v*` dirs ≠ current version at boot
   (~3.9 GB reclaimed).

### P2 — `move-ground` 0.55 core (warm; biggest on-tick section)

Phase attribution unknown (physics vs fallback steering vs packet build). **Plan:** add 3–4
sub-labels inside `BotMovementManager` ground step (collision query, steering, packet
build/broadcast), re-capture, then optimize the dominant slice. P1.1 (`slopeYAt` constants)
likely shaves a visible chunk for free.

**Lossy / no-observer option (big, needs design):** unobserved-map tick thinning. With 0
players online, ~all 615 bots simulate full 22 Hz physics + movement packets for nobody.
Candidate: on maps with no real player, run movement/physics at 1/2–1/4 cadence (or larger
integrator steps), restore full cadence the moment a player enters the map (map-enter hook
already exists). Combat/loot/economy outcomes must stay exact — only kinematic fidelity and
packet emission degrade. Estimated win: 1–1.5 cores at current population. Risks: movement
state machines and stuck-detection assume ~50 ms steps; launch/jump execution windows are
tick-granular (see bot-nav skill: outcome envelopes) — needs a careful design pass, listed
here as the single biggest lossy lever, NOT a quick win.

### P3 — `common-combat-buffs` 0.26 core (warm, lossless)

`tickBuffs` runs 6.8k cps (every other tick per bot) and rescans buff state each time.
**Plan:** deadline-driven — track per-bot `nextBuffCheckAtMs` = min(next expiry, next rebuff
window) and early-return until due. Lossless (same rebuff timing to the tick). Verify by CSV
share dropping to noise.

### P4 — per-tick rescans that should be dirty-flagged/deadline-driven (warm, lossless)

- `potion-recovery-scan` 0.072 core @ 8.6k cps: rescans the USE bag per tick; cache the
  recovery-potion classification, invalidate on inventory mutation (chokepoints exist in
  `BotInventoryManager`).
- `common-mob-damage` 0.105 core @ 13.5k cps: decay timers polled every tick per bot →
  min-deadline early-return.
- Same pattern check for `common-skill-cache`/`common-potion-check` (already cheap, skip
  unless trivially co-fixed).

### P5 — grind target search / combat plan (~0.4 core combined, warm)

`combat-target-search` 3.7k cps mob scans + sort; `combat-plan` per-attack hitbox work.
Possible: per-map mob spatial bucketing, target stickiness across ticks. **Defer** — medium
effort, medium win; re-rank after P0–P4 land (their savings change the denominator).

### P6 — instrumentation gaps (cheap, do alongside)

- Long-running off-tick calls are invisible until they RETURN (`recordSince` at end) — the
  chaos-scan saturation produced an *empty* CSV section while pegging a core. Add an
  in-flight/currently-running readout to `/api/perf` (thread → active section + elapsed).
- `autopilot-decide`-style labels for the scroll DP internals (curve build count, cache hits).

## 4. Explicitly NOT worth optimizing (measured)

| Path | Why not |
|---|---|
| `broadcast-move` | 763k packets in 60 s for 0.02 core — already cheap; packet emission is not the cost |
| live `pathfind-*` (all variants) | <0.11 core combined; committed-route architecture works |
| `quest-scan`/`pickstartable`/`active-mobs` | throttled fine (0.04 core total) |
| `grind.*` decision path | warm ~20 ms/decide (prior fix holds — `project_grind_advisor_perf`); the decide THREAD is the problem (P0), not the decide math |
| GC tuning | ~1% steady-state; P0 removes the dominant allocator; revisit only if old-gen stays >90% after |
| `tick-idle`, `stuck-detect`, potion-share, afk/social/trade common ticks | all <0.02 core |
| Cold caches other than nav graphs | owner directive: assume warm (boot warmers + DB indexes already landed) |

## 5. Suggested implementation order (later opus-level session)

1. **P0** curve cache + epsilon fixed-point + MC grid + scan coalescing (`BotScrollValuer`,
   `BotScrollManager`) — frees the decide thread AND ~1 core; biggest correctness-adjacent risk
   is cache keying vs live scroll prices, so check `marketReproSpecs` volatility first.
2. **P1.1** `slopeYAt` per-foothold trig constants (one file, bit-identical).
3. **P3 + P4** deadline/dirty-flag conversions (mechanical, verify with CSV).
4. Re-capture 60 s CSV + jstack → re-rank; then **P2** sub-instrumentation, **P1.2/1.3**
   build-report-driven graph work + offline prebuild tool.
5. Owner decisions queued: **P1.5** profile quantization (lossy), **P2** unobserved-map tick
   thinning (lossy) — both big levers, both need sign-off.
