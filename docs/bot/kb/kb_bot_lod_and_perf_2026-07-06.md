# LOD scaling + perf hot-paths (2026-07-06)

Durable facts from the 2000-bot perf push. Session state + resume point:
**`docs/bot/perf-2026-07-06-stage3-handoff.md`** (read that to continue). LOD design of record:
`docs/bot/unobserved-lod-design.md`.

## LOD build state (all committed on `dev`)
- **Stages 0+1** = observer substrate + kill calibration, behaviorally **INERT** (label only).
- **Stage 2** = LOD1 motion-plan movement (skip per-tick nav/physics) + cross-map timed warps +
  observation transitions + Y-band gate. Cadence still 50ms; combat still real.
- **Stage 3 (NEXT, not built)** = abstract grind + the 500ms cadence flip = the ~10× wakeup win.
  Cadence and combat are COUPLED: you cannot drop LOD1 to 500ms while combat is real (a grinder
  would attack 10× slower and crater exp/loot). So the cadence flip MUST wait for abstract grind.

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
   - The remaining `getAllMonsters` callers that need the actual list (not a count) are the
     residual convoy risk; a real root fix would be a **monster-type index on MapleMap** so the
     scan stops iterating non-monster objects (shared non-bot code — rule #2 caution).
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

## Measurement gotchas
- The advisor **ramps ~6–8 min after boot** and is **bursty** — capture perflog DURING a burst
  (poll `bot-grind-advisor` `cpu=` delta, fire when >0.3 cores) or you read ~0.
- perflog tick sections NEST under tick-total; off-tick threads (advisor) are separate/absent —
  cross-check with `jstack` thread `cpu=` deltas.
- A running server + an IntelliJ partial recompile = "undef class" that silently voids captures.
  Build+run the jar yourself for clean attribution.
- `/api/settings` value-SET format is not `{"NAME":"val"}` (that returns `unknown cmd`); the
  perflog CMD form `{"cmd":"perflog",...}` works. Real setter format still TBD.
