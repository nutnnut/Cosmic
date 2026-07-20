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

Known remaining costs (facts, not tasks):

- Grind-advisor/scroll-manager mob profiling constructs full `Monster` objects
  (`MonsterStats.copy` reflection field-copy) just to read stats (`farmContext`/`profileFor`).
- Sibling gear offers run the full equip optimizer from `checkBotStatus`; `Quest.getInstance`
  is a synchronized-map hit on several bot paths.
- The `-Xmx700m` seen on a java process on this box belongs to IntelliJ's JPS compile daemon, not
  the game server — check the command line before attributing heap limits.
