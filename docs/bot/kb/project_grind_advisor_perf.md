---
name: project_grind_advisor_perf
description: "BotGrindAdvisor perf investigation — the \"dozens of seconds\" is cold cache tax, not the algorithm; instrumentation + offline harness + in-game profiler built"
metadata: 
  node_type: memory
  type: project
  originSessionId: e6f72366-ca57-43dc-9d98-e83515dabf97
---

Goal (2026-06-14): make BotGrindAdvisor scale to 100+ bots. User flagged "dozens of seconds for a party of 6". Measured offline (BotGrindProfileTest reconstructs 6 real Orbis party chars from live `cosmic` DB via ItemFactory.loadItems + skills, no Mockito spy — spy breaks final inventory/skills fields on JDK21; set expRate reflectively) + advisor review.

**Diagnosis (measured, not guessed):** the decision algorithm is NOT the bottleneck.
- WARM party-of-6 `partyInputs`+`planPartyBest` = ~20ms (hot caches); full-world chat "where to grind" ~1s warm.
- Pathfinding/travelWeight = 2.5% (falsified as suspect; `pathfind*` labels are travel-EXECUTION only, never fire in the decision).
- The cost is one-time COLD process-global cache warming on the single `BotGrindAdvisor.DECIDE_POOL` thread. CORRECTED composition (clean offline validation 2026-06-15): the DOMINANT tax is **ItemInformationProvider equip-stat loading** (full-world cold 16.5s -> warm 86ms; ~15s is ii), NOT mob loads. LifeFactory mob stats are only ~1.5s (966 mobs). gearDropsByMob = 1 DB query. `warmCachesAsync` (BotManager.java:327) only warms BotSpawnIndex + BotWorldGraph. Earlier "mob loads = 70-80%" was wrong — it conflated cold-minus-warm (all caches) with mobs.
- Filtered party path (Orbis 21 maps) cold is small: live profiler logs showed 222-272ms/bot (Leroy/Mage), mostly shared/warmed-once. The 16.5s is the FULL-WORLD path (chat "where to grind" / quest recommend), on-demand only.
- SHIPPED & VALIDATED (offline harness: full-world cold candidatesFor 16.5s -> 0.26s after warm; 167 bot tests pass): `BotGrindAdvisor.warmGrindData()` 3rd boot daemon (MIN_PRIORITY, in warmCachesAsync) pre-loads, off-thread: all grindable mob stats + mob/map names, the gearDropsByMob DB table, the scroll catalog (`BotScrollManager.warmScrollCatalog` SSOT hook -> scrollsByCategory, the single dominant cold cost), and the droppable-equip ii catalog (getEquipById/getEquipmentSlot/getEquipLevelReq per drop). NOT all ~10k equips (getEquipById ~7ms/equip = ~80s; bagged gear still lazy-loads). warmGrindData itself ~20s on the daemon (off the decision path). User chose (2026-06-15) "warm ii + make touched caches thread-safe, fast first decisions across the board".
- Thread-safety: converted to ConcurrentHashMap (null-audited — all put non-null): LifeFactory.monsterStats; MonsterInformationProvider.mobAttackInfo/mobAttackAnimationTime/mobNameCache; ItemInformationProvider.equipCache/equipStatsCache/equipLevelReqCache/scrollReqsCache/equipmentSlotCache/untradeableCache; BotGrindAdvisor.mapNameCache (already CHM). LEFT as HashMap (store null): ii.nameCache/msgCache (getString can put null). This also fixes pre-existing latent races (these were unguarded HashMaps written concurrently by the bot system).
- 100 bots: steady-state hot ≈ 0.3–1s (fine); first re-decide wave after restart cold ≈ 7–30s (reproduces the pain). CPU contention (1 decide thread vs ~100 tick threads) could compound but is NOT reproducible offline.

**Bucket flips with scan size:** tiny filtered scan (13 mobs) → `grind.ownedbar` dominates (fixed slots×bag cost); full-world (687 mobs/258 items) → unlabeled gearProspects scan + roll dominate, ownedbar collapses to ~1%.

**Likely LOSSLESS fix (pending in-game confirmation):** extend warmCachesAsync to also pre-load off-thread at boot (parallel): all grindable mob stats, gearDropsByMob, droppable-equip ii stats. Dynamic-choice logic (proximity/exp/density/drops) untouched. If profiler shows run1≈run2 both large under load → it's contention → cap concurrent decisions / lower cadence instead.

**Artifacts in repo:** `grind.*` BotPerformanceMonitor labels in BotGrindAdvisor (build/kill/gear/roll/score/ownedbar/blend); offline `BotGrindProfileTest` (gated by `-DgrindProfile`, run `mvn test -Dtest=BotGrindProfileTest -DgrindProfile=1`); in-game chat command **"grind profile"** → `BotGrindAdvisor.exportGrindProfile` runs the real party decision TWICE under live load, writes run1(cold)-vs-run2(warm) + per-member + live-bot-count + self-diagnosis to logs/bot-grind/grind-profile-<name>.txt. See [[project_bot_independence_infra.md]].
