# LOD scaling + perf hot-paths (2026-07-06)

Durable facts from the 2000-bot perf push. Session state + resume point:
**`docs/bot/perf-2026-07-06-stage3-handoff.md`** (read that to continue). LOD design of record:
`docs/bot/unobserved-lod-design.md`.

## LOD build state (all committed on `dev`)
- **Stages 0+1** = observer substrate + kill calibration, behaviorally **INERT** (label only).
- **Stage 2** = LOD1 motion-plan movement (skip per-tick nav/physics) + cross-map timed warps +
  observation transitions + Y-band gate. Cadence still 50ms; combat still real.
- **Stage 3 slice 1 = BUILT** (`1ef845878`): abstract grind + the 500ms cadence flip = the ~10× wakeup
  win. A covered unobserved (LOD1) grinder stops real combat/nav and emits calibrated kills via
  `MapleMap.damageMonster` (exp/drops/quests/spawn bookkeeping full-fidelity), then drops to 500ms.
  SSOT gate `BotManager.abstractGrindEligible` shared by `cadenceForLod` + the grind dispatch so a bot
  at 500ms is never left running real 50ms combat. Coarse per-kill MP charge (casters still drink MP
  pots). Cadence and combat are COUPLED: you cannot drop LOD1 to 500ms while combat is real (a grinder
  would attack 10× slower and crater exp/loot) — that's why the flip lives with abstract grind.
  Verified at 30x (420 bots): combat-target-search 0.5+→0.002 cores, tick-abstract-grind 0.04–0.1 core
  for the WHOLE LOD1 grind pop, real exp/meso/loot flowing.
- **Stage 3 slice 2 (NOT built)** = exact resource honesty (attacks-per-kill MP, ammo, HP/pot from the
  danger model; honor break/leech in abstract grind for rate fidelity). Slice 1 charges only a coarse
  1-cast MP/kill; LOD1 bots take no real damage so they don't buy HP pots (economy gap to close).

## Perf hot-path traps (reusable)
1. **`MapleMap.getAllMonsters()` / `getMonsters()` / `getMapObjectsInRange()` are O(ALL map
   objects)** — they take `objectRLock` and walk `mapobjects.values()` (monsters + drops + npcs +
   summons) with a per-object distance calc, then allocate a list (getAllMonsters copies twice).
   Called per-bot across ~10 combat/targeting paths per tick, this convoys on `objectRLock` into
   multi-hundred-ms → multi-second tail spikes on crowded maps. Two lessons:
   - **Don't recompute a MAP-GLOBAL fact per-bot.** "Is any monster alive?" was answered per-bot
     by a full `getAllMonsters().stream()` scan; replaced with the O(1) lock-free SSOT
     `MapleMap.getSpawnedMonstersOnMap()` (AtomicInteger, incremented on spawn / decremented in
     removeKilledMonsterObject). Fix cut common-combat-buffs −66% (commit 7fa267df3).
   - **DONE (`481309326`): monster fast-index on MapleMap.** `getMonsters()`/`getAllMonsters()` now
     read a write-through `oid->Monster` ConcurrentHashMap (maintained under objectWLock at
     addMapObject/spawnAndAddRangedMapObject/removeMapObject) instead of walking ALL objects under
     objectRLock. Lock-free, no all-objects walk. Killed the monster-scan convoy: abstract-grind pick
     tail 965→44ms, passive-loot tail 586→45ms at 30x. NOTE: `getMapObjectsInRange` (items/npcs/etc.)
     still walks all objects — index items too if item scans become the bottleneck.

## The combat-buffs stall was GC, not a lock (2026-07-06, big one)
The multi-second `common-combat-buffs`/`common-systems` tick spikes (1.4–1.9 cores, max 4–6s) that
looked like an objectRLock convoy were **GC stop-the-world**. Tell: the tick-stall log attributes them
across EVERY unrelated phase (tick-autopilot, quest-scan, fm-errand, abstract-grind) — a lock convoy
hits one lock's callers, STW hits everyone. `jstat -gcutil` at 473 bots on **-Xmx4096m**: old gen
**94% full**, 778 concurrent GC cycles → young-GC evacuation failures = the multi-second STW.
- **Fix = heap, not code** (`52b91b8df`, launch.bat): `-Xms8g -Xmx8g -XX:+UseG1GC
  -XX:InitiatingHeapOccupancyPercent=40`. At 8GB/465 bots: old gen 94→41%, concurrent GC 778→10,
  combat-buffs mean 1.43→0.32 cores (−78%), tick-total 2.28→1.70, stalls ~40–130/30s → ~10 over the run.
- Live retained set **~1.23GB old gen (~2.6MB/bot)** at 465 bots → projects ~5.3GB at 2000 → fits 8GB.
- Residual few stalls are `tick-autopilot`/advisor (the scroll-dp single-thread DECIDE_POOL serial
  ceiling, ~1 core) + fm-errand narration. Metaspace sits ~98% (auto-grows, FGC=0 — not urgent).
- **Lesson:** before chasing a "lock convoy" in bot code, check `jstat` — a cross-phase stall spread is
  GC. The box has 32GB; the 2–4GB heap was a launch-flag oversight.
2. **`bot-grind-advisor` (single-thread `DECIDE_POOL`) is DEMAND-SATURATED, not per-op-bound.**
   The gear-valuation reproduction-cost DP (`BotScrollValuer.costFrom`) was ~156ms/call because it
   memoized score at 0.001 resolution and fractional stat-gains blow up the state space.
   Coarsening to 0.1 (`round(a*10)`) made it 33× faster — but the one thread just does 32× MORE
   DPs and stays ~0.9 cores. **Reducing per-op cost raises the serial THROUGHPUT ceiling (matters
   at 2000 bots) but does NOT reduce advisor CPU** — for that you must cut DEMAND (cache the
   valuation RESULT / throttle re-decide). One thread can't exceed 1 core → hard serial ceiling at
   scale. (Supersedes the "no curve cache / 120 iters" framing in
   `kb_bot_perf_stress_hotpaths.md` — the operator cache works, ~3 builds/45s; the cost is the
   lazy per-score DP on `curve.apply`, uninstrumented until the `scroll-dp` perf section added
   this session.)

## combat-buffs WAS real CPU — abstract-grind combat-slice gate + O(1) observer counter (2026-07-06 pm, verified A/B)
Two changes, A/B'd on the live server (baseline 655 bots vs after 714 bots, 0 observed maps both):
- **`98a3351c1` — O(1) `MapleMap.isObservedByPlayer()`.** It took `chrRLock` and linearly scanned every
  character, and it is the funnel gate in `broadcastMessage` that runs on EVERY bot broadcast
  (movement/attack/stance/damage/buff). On a bot-only map (0 observers) every broadcast scanned all
  characters just to learn "nobody's watching." Replaced with a write-through `AtomicInteger observerCount`
  (maintained at addPlayer/removePlayer + `Character.setHiddenFromBots`); the method is now one atomic read.
- **`79463d782` — LOD-gate the live-combat slice for abstract grinders.** `runCommonTickSystems` ran the
  whole combat slice (tickMobDamage sweep, tickBuffs, tickSupportHealing, tryCastMagicGuard, tryCastRecovery)
  for EVERY bot incl. ~580 unobserved 500ms abstract grinders, who take no real damage and emit calibrated
  kills — so it's pure waste. Gated behind the existing `abstractGrindEligible` SSOT.
- **Verified numbers (real process CPU via Get-Process TotalProcessorTime delta, the ground truth):**
  **2.50 → 1.32 cores (−47%), at HIGHER pop (655→714).** Section `common-combat-buffs` **1.591 → 0.039
  cores (−97.5%)**, avg/call 0.585ms → 0.018ms. So that 0.585ms/call WAS genuine work (buff casts +
  `getBuffedValue` fair-lock pairs + the per-broadcast observer scan), not wall-clock noise — removing the
  calls dropped REAL CPU by 1.18 cores. Abstract grind stayed healthy (53.9k kills, no NPE/errors).
- **Correction to the wall-clock note below:** the "cores" number DOES inflate with lock-wait, but the
  underlying combat-buffs work was substantially real CPU/contention. The A/B (removing it → −1.18 real
  cores) is ground truth. Don't over-apply the "it's only wall-clock" lesson — cross-check BOTH ways.
- **New #1 CPU consumer after this: `scroll-dp` ~1.0 core** (the single-thread `DECIDE_POOL` advisor serial
  ceiling, item 2 below). That is now THE 2000-bot bottleneck — demand-reduction is the next lever.

## `BotPerformanceMonitor` "cpu_core" is WALL-CLOCK, not CPU (measurement trap, verified 2026-07-06)
`record()` stores `System.nanoTime()` elapsed per section; the CSV `cpu_core` = summed elapsed / window
across all tick threads. That includes time a thread is **blocked** (lock wait, GC safepoint) inside the
section — NOT just CPU. Proof: at 735 bots the CSV showed tick-total **3.4 "cores"** and
common-combat-buffs **2.3**, but actual process CPU (Get-Process TotalProcessorTime delta) was **2.03 of
6 cores** and GC was 1.2%. A subset (tick-total) can't exceed total process CPU, so the "cores" are
wall-clock occupancy, not load. **Always cross-check a scary "cores" number against real process CPU
before optimizing it.** common-combat-buffs looks huge because it's the highest-call-rate longer section,
so it absorbs the most lock-wait/GC-pause wall-time attribution — it's a VICTIM/BAROMETER of contention,
not itself CPU-heavy. jstack during its "spikes" shows the bot-tick (`pool-3-thread`) threads PARKED, not
in tickBuffs. Corollary: the session's "-78% combat-buffs" (8GB) and monster-index tail drops are real
*contention/stall* reductions, but don't read the "cores" as CPU. Real bottleneck for smooth 2000-bot
operation is LOCK CONTENTION (chrRLock broadcasts, remaining objectRLock item scans), not CPU (which has
headroom: ~2 cores at 735 bots) and not GC (fine at 8GB).

## Measurement gotchas
- The advisor **ramps ~6–8 min after boot** and is **bursty** — capture perflog DURING a burst
  (poll `bot-grind-advisor` `cpu=` delta, fire when >0.3 cores) or you read ~0.
- perflog tick sections NEST under tick-total; off-tick threads (advisor) are separate/absent —
  cross-check with `jstack` thread `cpu=` deltas.
- A running server + an IntelliJ partial recompile = "undef class" that silently voids captures.
  Build+run the jar yourself for clean attribution.
- `/api/settings` value-SET format is not `{"NAME":"val"}` (that returns `unknown cmd`); the
  perflog CMD form `{"cmd":"perflog",...}` works. Real setter format still TBD.
