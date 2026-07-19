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

Known remaining costs (facts, not tasks):

- **Chaos-play scroll valuation dominates the decide thread at scale.** At 570 bots, 43% of all
  process CPU was `BotScrollValuer.costFrom` recursion under
  `maybeChaosPlay → bestChaosPlay → chaosOutcomeMeanValue → equipMarketQuote → reproductionValue →
  productionCost` on the single `bot-grind-advisor` (DECIDE_POOL) thread. It is instrumented
  (`chaos-scan`, nested in `decide-pool-task`) — the broken sample window hid it. Single-threaded,
  so it pegs at most one core, but it starves every other decide-pool task behind it.
- Grind-advisor/scroll-manager mob profiling constructs full `Monster` objects
  (`MonsterStats.copy` reflection field-copy) just to read stats (`farmContext`/`profileFor`).
- Sibling gear offers run the full equip optimizer from `checkBotStatus`; `Quest.getInstance`
  is a synchronized-map hit on several bot paths.
- The `-Xmx700m` seen on a java process on this box belongs to IntelliJ's JPS compile daemon, not
  the game server — check the command line before attributing heap limits.
