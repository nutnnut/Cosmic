# Perf session handoff — 2026-07-05 (continue with opus)

Goal: 2000+ bots (was ~600 ceiling). Owner greenlit lossy simplification when no player
can observe (design: `docs/bot/unobserved-lod-design.md`). Lossless plan of record:
`docs/bot/perf-optimization-plan.md` — **P0/P1.1/P1.6/P3/P4 now IMPLEMENTED (uncommitted
in working tree), NOT yet live-verified.**

## Live numbers (this session, before fixes)
- 217 bots, 0 players: process ~1.56/6 cores ⇒ ~7.2 mc/bot, linear with the 615-bot
  capture ⇒ 2000 bots ≈ 14.5 cores. Lossless alone insufficient; LOD lever mandatory.
- Capture: `logs/bot-perf/bot-perf-1783262480314.csv` (+.html), 60s @ 217 bots, server
  up only ~17min (nav caches cold — nav-resolve 24% likely cold-start noise, re-check warm).
- DECIDE_POOL pegged: `bot-grind-advisor` 623s CPU / 1012s uptime in BotScrollValuer.costFrom.
- NEW regression found: common-magic-guard 25.4% of tick-total (fair-lock contention).

## Landed in working tree (all compiled, unit tests green, NO commit yet)
1. **P0 scroll DP** (BotScrollValuer, BotScrollManager, BotScrollValuerTest): prior commit
   ddb12be6a had already built curve cache/coalescing/epsilon mechanism but was
   self-defeating: RESTART_EPSILON_MESO 1e-9→1.0 (real early-exit, ~10-40 iters vs 120);
   cache keys were exact price bits → thrash on every consensus drift → now geometric
   ~10% price buckets (priceBucket helper ~BotScrollManager:1276). itemId deliberately
   NOT in key (curve pure fn of baseScore/tuc/scrolls/baseCost). 3 test asserts relaxed
   to 5-meso tolerance (documented). MC-grid item skipped — already exact convolution.
2. **magic-guard** (BotCombatManager:3151, flag built at :647): root cause = fair
   ReentrantLocks (effLock AbstractCharacterObject:49, chrLock Character:332) probed
   via getBuffedValue every tick by EVERY bot; only magicians can cast it. Fix:
   entry.hasCriticalSurvivalBuff boolean gate + ~1s probe throttle
   (entry.nextMagicGuardCheckMs) for magicians.
3. **P3 tickBuffs** (BotCombatManager:686, nextBuffDeadline :735): deadline-gated on
   entry.nextBuffCheckAtMs (nearest nextBuffAt); party-support bots keep 1s floor;
   invalidation: rebuildSkillCacheIfNeeded + @support re-enable (BotChatManager:898).
4. **P4a potions** (BotPotionManager:159 countPotionsCached, caller BotCombatManager:2460):
   the 2460cps was isFragile, not recoveryPotions; 1s-TTL cache (no clean mutation
   chokepoint exists); freshness-sensitive callers keep exact countPotions.
5. **P4b mob-damage** (BotCombatManager:388): runSweep check hoisted above HP lock read.
   mobHitCooldownMs left as tick-count timer on purpose.
6. **P1.1 slopeYAt** (BotPhysicsEngine:2794-2815): per-Foothold cos(α)/cos(β) cache,
   WeakHashMap identity-keyed (mirrors COLLISION_INDEX), bit-identical math. WATCH:
   synchronizedMap global lock touched on every call from 6 tick + 2 build threads —
   if move-ground doesn't improve warm, suspect this lock first.
7. **P1.6** (BotNavigationGraphProvider:81-129): boot-time delete of stale
   cache/bot-nav version dirs (walkFileTree, no link-follow), ~3.9GB.
   New BotEntry fields: hasCriticalSurvivalBuff/hasPartySupportBuff (:175-176),
   nextBuffCheckAtMs/nextMagicGuardCheckMs (:772-773).

## In flight RIGHT NOW: subagent "lod-stage01" (opus)
Implementing Stage 0+1 of `docs/bot/unobserved-lod-design.md` in the SAME working tree:
- Stage 0: permanent kill-rate calibration instrumentation (measured kills/hr EMA vs
  killsPerHour prediction per (map,mob,job,levelband), TSV persistence, web endpoint) —
  owner ordered calibration because killsPerHour is a rough planning estimate.
- Stage 1: effectivelyObserved predicate (isObservedByPlayer MapleMap:3134 + 1-portal-edge
  pre-warm), !hidebot GM tier (GM observes raw LOD1 without promoting; flag inside
  isObservedByPlayer), BotEntry.lod + 10s hysteresis, retask() (BotEntry.task un-final;
  respect kb_bot_double_register_botpop_race locking), 4 cfg toggles next to
  POPULATION_MULTIPLIER (BotManager:190): SIMPLIFY_UNOBSERVED_BOTS_PHYSICS/_TRAVEL/
  _GRIND/_CADENCE, default true, runtime-flippable via /api/settings, all-false =
  existing behavior. Stage 1 alone must change NO behavior (cadence stays 50ms until
  Stage 2 exists — explicit safety note in its brief).
If its report never arrived: inspect git diff for its files before assuming state.

## Next steps (in order)
1. **Owner must restart the server** (IntelliJ debug session PID 16740; agent was
   permission-denied from killing it). target\classes currently = lossless fixes only
   (until lod-stage01 compiles) — restarting soon gives clean attribution.
2. After ~15-20min warm: 60s capture (`POST /api/settings {"cmd":"perflog","seconds":"60","html":"true"}`
   — values must be JSON strings; POST blocks ~60s) + jstack of bot-grind-advisor.
   Expect: DECIDE_POOL unpegged, chaos-scan completions present, common-magic-guard ≈0,
   common-combat-buffs ≈0, move-ground down. Re-rank; check nav-resolve warm.
3. Review + land lod-stage01's work; commit everything on dev in reviewed slices.
4. Implement LOD Stage 2 (motion-plan movement + timed warps) then Stage 3 (abstract
   grind using Stage-0 calibration) per design §5. Stage 4 polish + 2000-bot botpop test.
5. Re-measure at high population; target ≤3 cores at 2000 bots.

## Gotchas for the next session
- Perf CSV sections NEST (don't sum); minutes-long off-tick calls show as EMPTY sections
  (record-on-return) — cross-check jstack.
- /api/settings perflog: JSON string values only; curl POST blocks for the window.
- Server runs from target\classes under IntelliJ debugger — mvn compile hot-swaps
  nothing; restart required to pick up classes.
- Working tree has ~10 modified files from 4 agents — review before committing;
  git status list is in this doc's "Landed" section + lod-stage01's files.
