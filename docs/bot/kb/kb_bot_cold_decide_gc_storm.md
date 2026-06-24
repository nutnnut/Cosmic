---
name: kb_bot_cold_decide_gc_storm
description: "60-bot CPU freeze (80-90% all cores) = cold BotGrindAdvisor decide storm at boot -> GC stop-the-world, NOT the formulas; fix = warm-gate; + PartySearchEchelon lock-inversion crash"
metadata: 
  node_type: memory
  type: project
  originSessionId: 70a4dbac-b543-4746-9398-1d8e5e5795d8
---

Experimental-branch symptom: ~60 bots online froze the server at 80-90% CPU across all cores; master was fine at 10-15%.

**Root cause (NOT the scoring formulas).** `BotPerformanceMonitor` (`!botperfdebug on`) proved it: a COLD `BotAutopilotManager.decide()` pass = avg 637ms / **max 10,121ms**; the SAME pass WARM = **15.7ms** (40x). The tell was four unrelated sections (`autopilot-decide`, `grind.build`, `grind.gear`, `grind.ownedbar`) all hitting near-identical 7-10s maxes simultaneously = stop-the-world GC. The cold decide allocates heavily (Monte Carlo rolls + per-quest/gear HashMaps); 60 bots firing decides on the SINGLE-threaded `DECIDE_POOL` before the boot warm finished = GC storm that pegs all cores. `BotGrindAdvisor.warmGrindData` (thread `bot-grind-data-warmup`) existed but `cachesWarmed` was set true when warm STARTED, not finished, and nothing waited — so decides raced it cold. The experimental quest/equip-valuation commits (`computeRewardGain`/`equipValue`) are cheap once warm; do NOT simplify them.

**Fix (committed on experimental):** `BotGrindAdvisor.buildCandidates()` (the single chokepoint for solo `recommend`, party `candidatesFor`, advice, export) now calls `awaitWarm()` — blocks the decide thread on a `CountDownLatch` released in `warmGrindData`'s finally. 60s cap falls back to cold lazy-load if warm hangs; no-ops when warm never started (tests, gated on `cachesWarmed`). Verified: post-fix log shows `autopilot-decide`/`grind.*` never cross threshold, `tick-total` core 0.35-0.46 steady, no multi-second maxes.

**Monitor change:** `BotPerformanceMonitor.maybeLog` now also reports sections clearing a cumulative floor (5 ms/s) and SORTS BY TOTAL CPU, not max spike — the old max-only gate hid cheap-per-call high-frequency paths (e.g. a quest scan x60 bots). Profiling hooks added: `quest-scan`/`quest-pickstartable`/`quest-reward-gain`/`quest-active-mobs`/`autopilot-recover`/`autopilot-decide`.

**Separate bug surfaced & fixed:** `PartySearchEchelon` (`net.server.coordinator.partysearch`) threw `IllegalArgumentException: Illegal Capacity: -1` every ~15s. Lock inversion: `echelon` is a plain `HashMap`, but `attachPlayer`/`detachPlayer` took the shared READ lock while mutating it, so concurrent bot party-search (P4 dynamic party-up) corrupted `size()` negative -> `new ArrayList<>(-1)`. Fix: all three methods mutate, so replaced the `ReadWriteLock` with a plain exclusive `ReentrantLock`.

Profiling primer: `!botperfdebug on`; report `core=` = fraction of one CPU core, `cps=` calls/sec, `max=` worst single call. Always-on stall warnings (>250ms) name the dominant tick phase. Related: [[project_grind_advisor_perf]] (cold-cache tax was already known; this is the boot-race manifestation under 60-bot load).
