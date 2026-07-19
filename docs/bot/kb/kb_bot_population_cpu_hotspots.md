# Population-scale CPU hot spots (steady-state, ~370 bots)

How to catch these: `jcmd <pid> JFR.start settings=profile duration=120s filename=...` then
`jfr view hot-methods` / aggregate `jdk.ExecutionSample` stacks by the first `server.bots.*` frame.
The in-repo `/api/perf?durationMs=30000` window reports per-*section* timings only — work outside
instrumented sections (approach probes, physics lookups) is invisible there while still burning
cores, so cross-check its `processCpuMs` against the section totals: a big gap means the cost
lives between sections and needs the JFR view.

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

Known remaining costs, measured small at 372 bots (facts, not tasks): grind-advisor/scroll-manager
mob profiling constructs full `Monster` objects (`MonsterStats.copy` reflection field-copy) just to
read stats; sibling gear offers run the full equip optimizer from `checkBotStatus`; `Quest.getInstance`
is a synchronized-map hit on several bot paths. The `-Xmx700m` seen on a java process on this box
belongs to IntelliJ's JPS compile daemon, not the game server — check the command line before
attributing heap limits.
