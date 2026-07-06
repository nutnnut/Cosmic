# Perf/LOD session handoff — 2026-07-06 (Stage 3 slice 1 + monster index + 8GB heap LANDED)

Supersedes `perf-2026-07-05-handoff.md`. Goal unchanged: **2000+ bots on this 6-core box**
(was ~600 ceiling). Owner-approved lossy lever = unobserved-map LOD
(`docs/bot/unobserved-lod-design.md`).

## UPDATE (2026-07-06 pm, testing at 30x = ~465 bots, then ~1000): three big landings
1. **Stage 3 slice 1** (`1ef845878`): LOD1 abstract grind + 500ms cadence flip — THE ~10× wakeup lever.
   Real combat/nav collapsed for the unobserved pop (combat-target-search 0.5+→0.002 cores).
2. **MapleMap monster fast-index** (`481309326`): killed the getAllMonsters O(all-objects) objectRLock
   convoy (abstract-grind/passive-loot tails 900+/580ms → ~45ms). Owner OK'd upstream perf edits.
3. **8GB heap** (`52b91b8df`, launch.bat was -Xmx2048m): the multi-second "combat-buffs" stalls were
   **GC, not locks** (old gen 94% full at 4GB → evac-failure STW). 8GB+G1 IHOP=40: old gen 94→41%,
   combat-buffs 1.43→0.32 cores. See `kb/kb_bot_lod_and_perf_2026-07-06.md` "combat-buffs stall was GC".
**Next: (a) Stage 3 slice 2 (exact resource honesty); (b) grow the managed-bot pool (currently ~1000)
toward 2000 to run the real 2000-bot botpop test; (c) scroll-dp advisor demand-reduction (the residual
~1-core serial ceiling).** Original Stage-1/2 detail below (still accurate).

## Committed on `dev` (all compiled, tree clean; branch `dev`, off `master`)
```
874e111a3  combat-buffs: drop last getAllMonsters scan from tickBuffs (rockBuff)  <- dev tip
0a0839eca  Merge Stage 2 (LOD1 motion-plan + timed warps + transitions + Y-gate)
cd1940947  scroll-DP: coarsen costFrom memo to 0.1-stat resolution (+perf section)
7fa267df3  combat-buffs: O(1) map-monster liveness gate (tickBuffs 27%→12%)
6090784c7  LOD substrate (Stage 0+1): observer predicate + kill-rate calibration
a5f70d1b8  Bot perf: lossless decide-pool & tick hotspot fixes (P0–P4)
6db7317bf  (previous head)
```
(+ a docs commit with this handoff/KB.) Nothing pushed (owner asked commit-only).
`stage2-implementation-plan.md` is committed in the LOD/Stage-2 slices;
`stage3-implementation-plan.md` ships with the docs commit.

## What each landed change does + VERIFIED numbers (live, ~200–230 bots, 0 players)
- **Lossless P0–P4** (`a5f70d1b8`): P2 magic-guard fair-lock gate **verified** (common-magic-guard
  25.4%→0.24%). P0 scroll DP epsilon+price-bucket, P1.1 slopeYAt trig cache, P1.6 boot cache
  purge, P3 buff/P4 potion+mob-damage deadlines. `BotScrollValuerTest` green.
- **LOD substrate Stage 0+1** (`6090784c7`, co-authored lod-stage01): behaviorally **INERT**
  (`stage2CoarseTickReady`-style gate). effectivelyObserved (isObservedByPlayer + 1-portal-edge
  pre-warm) + BotEntry.lod + 10s hysteresis + retask() + SIMPLIFY_UNOBSERVED_BOTS_* toggles;
  `!hidebot` GM; Stage-0 kill calibration (BotKillCalibration, `/api/killcalib`, TSV persist).
- **combat-buffs O(1)** (`7fa267df3`): tickBuffs line ~696 answered a map-global "any monster
  alive?" per-bot via `getAllMonsters().stream()` (objectRLock walk of ALL map objects) → lock
  convoy → 700–2477ms spikes. Replaced with `MapleMap.getSpawnedMonstersOnMap()` (O(1) atomic
  SSOT). **Verified −66% section avg** (0.544→0.186 cores), −25% tick-total. NOTE: rare deep
  stalls remain — see §Combat-buffs.
- **scroll-DP coarsening** (`cd1940947`): costFrom memo key 0.001→0.1 stat resolution
  (`round(a*10)`). **Verified 33× faster per DP** (156ms→4.8ms). Raises the single-threaded
  `bot-grind-advisor` decision-throughput ceiling ~33× (~6→~200 valuations/s) — the scaling
  lever. Does NOT reduce advisor CPU at 216 bots (it's demand-saturated) — see §Scroll-DP.
- **Stage 2** (`0a0839eca`, lod-stage01, 5 slices): LOD1 bots skip per-tick nav/physics via
  motion-plan lerp; cross-map hops → timed warps (BotTravelCost SSOT); transitions (freeze on
  →LOD1, foothold-snap materialize on →LOD0 via guarded `MapleMap.addPlayer` hook, the ONE
  non-bot edit). Cadence stays 50ms (real combat). Y-band gate: motion-plan only within real
  attack-Y-reach (`BotCombatManager.withinAttackYReach` SSOT) → same-level glides keep the win,
  cross-level falls through to real physics → combat fidelity preserved. **Verified: LOD1
  movement/nav collapsed** — nav-resolve −84% (0.093→0.015), move-ground −62%, step-movement
  −69%, combat-target-search −67%, pathfind-target-score −66%. Kills healthy (killcalib
  rateSamples climbing, bucket ratios 1.0–2.0, no cratering).

## OPEN ITEMS (ranked)
1. **Stage 3 — DO THIS NEXT** (peer plan `docs/bot/stage3-implementation-plan.md`, ready):
   abstract grind for LOD1 (damageMonster-based kills at calibrated Stage-0 rate + honest loot/
   resource charging) → then **flip 500ms cadence** (`stage3AbstractGrindReady=true`) = the ~10×
   wakeup win, the headline 2000-bot lever. **ZERO non-bot edits** (damageMonster/killMonster/
   BotLootEligibility called, not modified — damageMonster at MapleMap:1283 registers the damager
   so exp distributes correctly; do NOT call raw killMonster). Branch at BotManager:4237 grind
   dispatch. Also resolves the frozen-Y concern (position-independent combat → Y-gate droppable
   for LOD1). Verify per design §5 (20-bot hour LOD1-vs-LOD0 exp/drops/meso/potion + quest
   progress; perf combat-target/plan ≈0 + ~10× lower LOD1 tick rate).
2. **combat-buffs tail-stall** — see §Combat-buffs (fix in flight, verifying).
3. **scroll-DP demand-reduction** — the advisor CPU lever (§Scroll-DP). Economy-sensitive.
4. **24/198 bots LOD0 at 0 players** — likely 10s-hysteresis churn from map-hopping; confirm
   it's not an effectivelyObserved bug (1-portal-edge over-marking / party false-positive).
5. **LOD transitions** (materialize/foothold-snap on →LOD0) — CANNOT test headless (needs a
   real client login to enter a bot map). Review-verified only; owner should client-test:
   walk into a grind map, confirm no floating/underground bots + no snap glitch.
6. **2000-bot botpop test** — the actual goal. After Stage 3. Watch: advisor serial ceiling
   (see §Scroll-DP), objectRLock contention, GC (old gen ran ~94% at 230 bots).

## §Combat-buffs (item 2 detail)
Root cause of the 27% was fixed (O(1) liveness). REMAINING: recurring multi-second tail-stalls
(max 2662–3904ms, ~0.04% of tickBuffs calls, but they inflate the section to 0.5–0.8 cores in a
50s window; avg call is cheap ~0.3ms). NOT GC (FGC=0) → a **lock convoy**. The section is
high-variance — the earlier "0.186 cores" verify caught a low-stall window; more captures show
0.5–0.8. **In-flight fix (uncommitted):** removed the last `getAllMonsters` scan from the
tickBuffs hot path — `rockBuffWorthCasting` (BotCombatManager ~786) scanned all map objects under
objectRLock when a rock-buff bot (Shadow Partner) had no committed grindTarget; now it defers the
rock buff instead. **Verification (3 captures each, ~8min warm): common-combat-buffs
0.63→0.23 cores avg (−63%), tick-total ~1.62→1.11 (−31%). REAL WIN — committed.** BUT the
multi-second stalls are NOT fully gone (max still 1600–3631ms; in one capture the 3631ms max was
in a NON-combat-buffs section) → the residual is the **systemic** objectRLock convoy from the
OTHER getAllMonsters callers (BotCombatManager 393/2034/2136/2734/3015/3032, all O(all-objects)).
**Deeper root fix (follow-up): give MapleMap a monster-type index so getAllMonsters/
getMapObjectsInRange stop iterating ALL map objects** — that kills the whole convoy class, not
just the tickBuffs instance. Shared non-bot code (rule #2 — minimal, justified). Lower priority
than Stage 3.

## §Scroll-DP (item 3 detail)
`bot-grind-advisor` = single-thread `DECIDE_POOL` executor (BotGrindAdvisor:100). Bots submit
decide tasks; one thread serializes them. It runs ~0.4 cores lifetime avg, ~0.8–1.0 cores when
active (bursty; **ramps ~6–8 min in**, not at boot — a measurement trap). The gear valuation
(farmableScrolledEv→BotScrollPlanner.planBest→reproductionValue DP) is the heavy part. Coarsening
made each DP 33× cheaper but the thread stays saturated (does 32× more DPs: 6→206/s) — it is
**demand-saturated**, not per-DP-cost-bound. So per-DP cost was the wrong lever for CPU (right
lever for the *serial throughput ceiling*, which matters at 2000). To cut advisor CPU: reduce
DEMAND — cache the farmableScrolledEv RESULT per (item, farming-cost-bucket, offense-context) or
throttle re-decide cadence. At 2000 bots the advisor is a hard serial ceiling (one thread, can't
exceed 1 core) — the 33× throughput win buys headroom but demand-reduction is still needed.

## Measurement playbook (learned the hard way this session)
- Server runs from `target/Cosmic.jar` (`launch.bat` form, I use `-Xmx4096m -Dwz-path=wz`).
  IntelliJ debugger doing a partial recompile UNDER a running server = "undef class" that voids
  captures — build+run the jar yourself for clean attribution.
- perflog: `POST /api/settings {"cmd":"perflog","seconds":"NN","html":"true|false"}` (JSON STRING
  values; blocks NN s; writes logs/bot-perf/*.csv). Tick sections nest under tick-total; OFF-TICK
  threads (advisor DP) show as separate sections OR not at all — cross-check with `jstack`.
- Advisor ramps ~6–8 min AND is bursty → warm ≥8 min and capture DURING a burst (poll the
  `bot-grind-advisor` thread `cpu=` delta; fire perflog when >0.3 cores). Capturing early reads ~0.
- `jstack` = Corretto jdk21 `bin/jstack <pid>`; thread `cpu=` deltas give per-thread cores.
- The `/api/settings` value-set format is NOT `{"NAME":"val"}` (returns `unknown cmd`) — find the
  real setter format before trying to A/B a SIMPLIFY_* toggle at runtime (I couldn't, this session).

## Teammate coordination (lod-stage01)
An opus teammate `lod-stage01` authored LOD Stages 0–2 (in a worktree
`D:/GameServers/Maplestory/cosmic-stage2`, branch `stage2` — now merged) and has the Stage 3
plan ready, holding for go. It works read-only/worktree-isolated, no server (I hold the ports).
Stage 3 has zero non-bot edits so it can land on `dev` directly. Hand it the server + Stage 3 go
once combat-buffs is settled. Worktree cleanup: NO wz junction was created (safe), but follow
CLAUDE.md rule #7 regardless (`rmdir` any inner `wz` link non-recursively FIRST).
