---
name: kb_bot_perf_stress_hotpaths
description: "615-bot stress capture (2026-07-04): DECIDE_POOL pegged for minutes in chaos/scroll valuation DP (BotScrollValuer, 120 fixed-point iters × 128 MC curve queries, no curve cache); nav-graph v70 cold rebuild = days of 2-core churn; move-ground/tickBuffs/per-tick rescans ranked; full plan docs/bot/perf-optimization-plan.md"
metadata:
  type: project
---

2026-07-04 stress capture on dev-economy: ~615 bots, 0 players, 6 cores. **Plan of record
(ranked candidates + explicit not-worth list, NOT yet implemented):
`docs/bot/perf-optimization-plan.md`.** Measurement: 60s BotPerformanceMonitor CSV
(`logs/bot-perf/bot-perf-1783151267251.csv`), 12× jstack, jstat. Owner scope rule: only cold
cache worth optimizing is the **nav graph build**; all other caches assume warm.

**Headline:** tick work = 3.25 cores (13.5k ticks/s); process ~4.5/6 cores; both graph-warmup
threads pegged (~4s/graph, ~9k graphs to rebuild post-GRAPH_VERSION-70 bump = days of churn);
the single `bot-grind-advisor` DECIDE_POOL thread pegged 12/12 jstack samples inside
`BotScrollManager.bestChaosPlay` → `BotScrollValuer.costFrom` with **zero** off-tick section
completions in 60s — individual scroll/chaos valuations run for MINUTES and starve all
grind/party decides behind them (warm cousin of [[kb_bot_cold_decide_gc_storm]]).

**Why the DP is slow (P0):** fresh `reproductionValue` curve per `equipMarketQuote` (memo dies
per call, no process-wide cache despite curves being deterministic per item type);
`RESTART_ITERS=120` full DP rebuilds for a geometrically-converging fixed point (epsilon exit
→ ~10); `chaosOutcomeMeanValue` MC queries 128 *fractional* band scores so ~every sample is a
distinct memo miss = a full DP; boxed HashMap<Long,Double> churn. Fix = shared curve cache +
epsilon exit + curve-on-grid interpolation + scan coalescing.

**Nav build hot spots (P1):** jump/flash-jump launch-window expansion + landing sims dominate;
`BotPhysicsEngine.slopeYAt` recomputes per-foothold-constant atan/cos per call (cache cosα/cosβ
per foothold = bit-identical, also helps live move-ground). Offline bulk prebuild from prior
version's cache-dir key census kills the post-bump churn. Lossy lever (owner call): quantize
movement profiles in the graph key (~14 profile variants/map at v66). Stale `cache/bot-nav`
v10–v69 dirs = 3.9 GB, never cleaned.

**Warm on-tick ranks:** move-ground 0.55 core (needs sub-instrumentation before optimizing),
tickBuffs 0.26 (→ deadline-driven), per-tick rescans (potion-recovery-scan, mob-damage decay →
dirty-flag/deadline), target-search+combat-plan ~0.4 deferred. Big lossy lever needing design:
unobserved-map tick thinning (0 players ⇒ nobody sees 22Hz fidelity; est 1–1.5 cores).

**Not worth:** broadcast-move, live pathfind-* (<0.11 core total), quest scans, grind decision
math (warm 20ms, [[project_grind_advisor_perf]]), GC tuning (~1%), tick-idle/stuck-detect.

**Gotchas learned:** `/api/settings` perflog needs JSON **string** values
(`{"seconds":"60"}` — bare ints → `bad seconds`); BotPerformanceMonitor only records a section
when the call RETURNS, so a minutes-long off-tick call shows as an EMPTY section while pegging
a core — cross-check with jstack; perf CSV sections nest (grind-dispatch ⊃ combat,
step-movement-core ⊃ nav-resolve/move) so shares don't sum.
