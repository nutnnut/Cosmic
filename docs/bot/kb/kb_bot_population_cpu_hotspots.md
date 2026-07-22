# Population-scale CPU hot spots (steady-state, hundreds of bots)

How to catch these: `jcmd <pid> JFR.start settings=profile duration=120s filename=...` then
`jfr view hot-methods` / aggregate `jdk.ExecutionSample` stacks by the first `server.bots.*` frame.
The in-repo `/api/perf?durationMs=30000` window samples in a clear-proof window and reports
`attributedCpuMs` vs `unattributedCpuMs` (process CPU vs the summed thread-entry sections
`tick-total`/`decide-pool-task`/`graph-warmup-task`); the periodic 15s console report logs the same
`bot-perf cpu>` coverage line. A large unattributed remainder means CPU outside every instrumented
bot entry point — non-bot server work, GC/JIT, or a bot path missing a section — and that is when
the JFR view is the right tool. (Historical trap, fixed: the endpoint used to race the monitor's
internal 15s auto-clear, so a 30s window could snapshot milliseconds after a wipe and report
near-empty garbage counts — sections looked idle while the process burned 3+ cores.)

**`System.nanoTime()` costs ~15µs PER CALL on this host** (QEMU VM, emulated HPET timer;
`System.currentTimeMillis()` is ~6ns — 2300× cheaper). Verified with a 2M-call loop
(`NanoBench`): 15041ns/call. Every "cheap" `nanoTime` pair around a section is really ~30µs of
clock reads, which at thousands of bot ticks/sec turns instrumentation into the workload:
- With monitoring ON, the ~25 gated `if (perf) System.nanoTime()` pairs per tick cost ~2 cores at
  500 bots. That is the price of a perf window on this box — keep captures short and never leave
  the monitor enabled; the numbers it reports for sub-ms sections are dominated by clock latency
  (a uniform ~15µs avg across trivial sections is the tell).
- With monitoring OFF, any UNCONDITIONAL `nanoTime` on a tick path still bleeds real CPU. All
  per-tick timing now routes through `BotPerformanceMonitor.start()/recordSince()/startStallPhase()`,
  which use nanoTime only while the monitor records and the ms clock (negative-token convention)
  for the always-on stall trace. Never add a bare `System.nanoTime()` to a per-tick/per-section
  bot path — use those hooks.

**The monitor itself used to be the biggest hot spot while enabled:** `BotPerformanceMonitor.record`
funneled every sample (~25 per bot tick, tens of thousands/sec at population scale) through one global
`synchronized` lock — enabling monitoring TRIPLED process CPU (0.34→1.0 core at 147 bots) and inflated
every `common-*` section to a uniform ~15µs of lock contention. Any perf capture taken before the
lock-free rewrite (ConcurrentHashMap + LongAdder/LongAccumulator; the periodic report is the only
synchronized path) both overstated totals and flattened per-section attribution — distrust old CSVs.
JFR (`settings=profile`) was the tool that caught this; it stays the ground-truth cross-check.

Hot-spot classes found at 372 bots (~3.2 cores) and their structural fixes, all in place:

- **Approach-point picking ran one A\* per candidate foothold sample.**
  `BotTravelManager.approachCandidates` samples every foothold near the target (dozens of points)
  and probed each with `findPathForApproachProbe` — ~24% of all CPU, triggered per taxi-hop start,
  shop visit, and town-loiter pick. Reachability only depends on the candidate's *region*, so the
  pick now memoizes the per-region verdict (`reachMemo`, shared across the close and widened
  rings): one search per distinct region (typically 1–5) instead of per point. Near-lossless: with
  a capped edge budget the first-probed candidate's verdict stands for its region, which was
  already order-dependent before.
- **`BotNavigationGraphProvider.peekGraph(map)` linearly scanned the whole graph cache.**
  Physics calls it per ground sample (`resolveWalkRegionLookup`), and the cache holds one entry per
  (map, movement profile) visited — ~9% of CPU iterating a ConcurrentHashMap. Now O(1) via the
  `ANY_GRAPH_BY_MAP` index maintained by the single put/remove seam (`putGraph`/`removeGraph`).
  If you add a new `GRAPHS` mutation, go through that seam or the index goes stale.
- **`SkillFactory.getSkillName` re-parses `String.img` XML on every call.** The buff tick labels
  every cast decision (including per-tick failure notes) with the skill name — ~6% of CPU parsing
  the same XML forever. `BotCombatManager.skillLabel` now memoizes (`SKILL_LABELS`); the upstream
  `getSkillName` is left uncached (upstream-diff rule), so treat any *new* per-tick call to it as
  a bug.
- **`tickOpportunisticGrab` called `map.getNPCById(...)` once per indexed quest.** Each call is a
  full map-object scan under the object read lock (~5% CPU plus `Quest.getInstance` churn from the
  per-quest gate checks). Now one sweep collects the NPC ids within trigger radius, and only quests
  whose start NPC is actually nearby hit the quest gate.
- **The pre-travel low-supply gate walked the USE inventory every poll.**
  `defaultLowOnSupplies` now uses `countPotionsCached` (~1s TTL), same as the combat fragility
  probe. Freshness-sensitive callers (restock, chat status, pot-share) still use exact counts.

- **Chaos-play scroll valuation dominated the decide thread at scale.** At 570 bots, 43% of all
  process CPU was `BotScrollValuer.costFrom` recursion under
  `maybeChaosPlay → bestChaosPlay → chaosOutcomeMeanValue → equipMarketQuote` on the single
  `bot-grind-advisor` (DECIDE_POOL) thread — the broken sample window had hidden it despite
  `chaos-scan` instrumentation. Root cause: the chaos outcome cloud queried the band curve at
  hundreds of distinct 0.1-scores, and **every distinct score was a full reproduction-DP solve**
  (nothing shared across targets). Fixed by serving `equipMarketQuote`'s `bandCurve` from a
  lazily-filled integer-band grid with log-space interpolation (~maxBand solves per cold curve
  instead of ~spread/0.1), quantizing the chaos convolution to the same 0.1-score resolution, and
  evicting the repro-curve cache by ~25% segments instead of a wholesale `clear()` cold storm.
  Bounded loss: interpolation errs a few percent of local value, inside the ±30%-of-cost gambler
  appetite band and the 10% price bucketing the model already accepts; grid points stay exact.
  Also fixed there: chaos EV's `vNow` baseline now uses the fractional-band quote instead of the
  rounded integer band (the rounded baseline systematically inflated EV by convexity).
- **Grind advisor valued gear before map admission.** Level-band filtering rejected tiny/off-band
  maps only after their catalog rolls and owned-gear comparisons had already run. The cheap
  profile/spawn-point predicate now runs first, while out-of-band mobs remain in the blend of every
  admitted map.

Hot-spot classes found at ~510 bots (2026-07-22 JFR) and their fixes, all in place. Combined
result: ~508 bots cost 1.54 cores monitoring-off before, ~457 bots cost 0.47-0.66 core after
(~3.0 → ~1.2 m-core per bot, back under the 2026-07-10 baseline):

- **The cramped-bag auto-sell scan ran a full market valuation every autopilot tick.** The dominant
  chain (~35-40% of ALL process CPU): `BotAutopilotManager.tick → yieldForResupply →
  BotShopManager.shouldAutoSellTrash → classifyBagUse/rankUseShelf → useShelfKeepValue →
  scrollMarketValueMeso → farmingCostMeso → farmContext`, which constructed a full `Monster` per
  scroll (`LifeFactory.getMonster` → `MonsterStats.copy` reflection field-copy, ~10% CPU on its own)
  and ran `estimateBestSkillHitDamage` per item. A grinding bot with a full bag it can't sell stays
  cramped forever, so the scan re-ran ~3.3×/sec/bot indefinitely — and `classifyBagUse` ran twice per
  verdict (`collectSellTrashUseItems` + `crampedUseSalesAvailable`). Fixes: (1) the whole
  `shouldAutoSellTrash` verdict (including the 3-tab free-slot probes, which alone reached 5% CPU)
  rides a ~4s per-entry TTL, invalidated when a sell sequence concludes; (2) `scrollMarketValueMeso`
  (the shelf-valuation hook) caches per (bot, scroll) with a 15s TTL — belief-book prices drift on a
  seconds-to-minutes scale, so sell-ordering is unaffected; (3) `seekOverheadSeconds` memoizes per
  mob (spawn data is static).
- **Gear-suggestion probes re-ran the full equip optimizer on every status check.**
  `checkBotStatus` (spawn / grind start / greeting / level-up) ran up to three optimizer passes
  (owner rec, sibling gear, useless scroll) gated by `nextGearSuggestionAt` — which only advanced
  on a SUCCESSFUL offer. The common no-upgrade case retried the whole Pareto DP every call (~12%
  CPU with the reserve/self-keep chains). Fix: the gate advances on attempt (60s), first hit still
  wins the window; event paths (loot pickup, mode entry) still force an immediate scan by zeroing
  the gate.
- **Temple lane pins ran the O(all-bots) crowd scan every tick.** `BotTempleProgressionManager
  .grindLane` called `BotOccupancy.extraCompetitors` (walks every online character) per tick per
  lane-pinned bot — O(bots²). Fix: crowd re-check on a 2.5-4.5s jittered cadence
  (`templeCrowdCheckDueMs`); the pin bookkeeping still runs every tick.
- **`WZFiles.getFile()` built a fresh `Path` per call.** `Path.of` re-parses the string on every
  call (Windows path normalization was ~2% of CPU as a leaf), and hot bot paths hit it constantly —
  `BotAttackDataProvider.ensureCurrentCharacterRoot` calls it per body-action/timing lookup. Fix:
  the enum lazily caches its `Path` (can't be eager: `DIRECTORY` initializes after the constants).

Known remaining costs (facts, not tasks):

- Grind-advisor mob profiling still constructs full `Monster` objects (`MonsterStats.copy`
  reflection field-copy) on the DECIDE_POOL paths (`profileFor`); the shelf-valuation path no
  longer hits it (TTL caches above).
- `autoEquip` (full Pareto DP) still runs per equip pickup (`tickPassiveLoot`) and inside each
  60s-cadence gear-offer pass; `Quest.getInstance` is a synchronized-map hit on several bot paths.
- `BotWorldGraph.route` Dijkstra runs per travel hop re-plan (~3% at 500 bots with travel-heavy
  mix); `BotMovementProfile.fromCharacter` recomputes per tick (~3%); shout-trade
  `tryMatchHeardShout` equip scans cost ~2.5% during market hours.
- Post-boot, `BotNavigationGraphProvider.loadGraph` churns on the warmup executors for a while
  (bots fan out to fresh maps); it's background-thread work, not tick latency.
- The `-Xmx700m` seen on a java process on this box belongs to IntelliJ's JPS compile daemon, not
  the game server — check the command line before attributing heap limits.
