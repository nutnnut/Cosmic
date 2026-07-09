# Perf/LOD session handoff — 2026-07-09 (combat-slice gate + O(1) observer + deadlock fix + scroll/chaos DP)

Supersedes `perf-2026-07-06-stage3-handoff.md`. Goal unchanged: **2000+ bots on this 6-core box**.
Owner-approved levers: unobserved-map LOD (`docs/bot/unobserved-lod-design.md`) + upstream edits when a
non-bot path is the proven bottleneck.

## TL;DR — what landed this session (all committed on `dev`, off `master`, nothing pushed)

```
82d82f22b Bot chaos: only gamble on offense gear (ATT, or INT+MATT)
593ce61c8 Bot scroll-DP: stop the curve-cache thrash (bucket baseScore key + raise cap)
c9ba4f4da Fix AB-BA deadlock: updateActiveEffects must take prtLock before effLock
502bb23e2 Bot scroll-DP: coarsen curve resolution to cut redundant solves
c0b442136 docs(bot): verified A/B for combat-slice gate + O(1) observer counter
79463d782 Bot LOD: skip the live-combat slice for abstract-grind bots
98a3351c1 MapleMap: O(1) isObservedByPlayer via write-through observer counter
```

### Verified numbers (live A/B, ~655-726 bots, 0 observed maps, 8GB heap)
1. **combat-slice gate + O(1) observer** (`98a3351c1` + `79463d782`): real process CPU **2.50 → 1.32
   cores (-47%)** at higher pop (655→714). `common-combat-buffs` **1.59 → 0.04 cores (-97%)**. Two parts:
   - `isObservedByPlayer()` was a per-broadcast `chrRLock` O(N) scan on the movement/attack hot path;
     now an O(1) `AtomicInteger observerCount` (write-through at add/removePlayer + `setHiddenFromBots`).
   - abstract-grind LOD1 bots (unobserved, calibrated kills) now **skip the live-combat slice**
     (tickMobDamage sweep, tickBuffs, tickSupportHealing, magic-guard, recovery) via the existing
     `abstractGrindEligible` gate in `runCommonTickSystems`. Corrects the prior "combat-buffs is only
     wall-clock" note — removing the work dropped REAL CPU by 1.18 cores.
2. **AB-BA deadlock fix** (`c9ba4f4da`): at 714 bots the server HUNG (0 CPU, `/api/live` timeouts). Two
   tick threads deadlocked on one Character's `prtLock`/`effLock`: a bot buffing party member C
   (`registerEffect`: prt->eff->chr) vs C's own map-change tick (`updateActiveEffects`: held eff, reached
   prt via `getPartyMembersOnSameMap`). `updateActiveEffects` was the lone `eff->prt` inverter; hoisted
   `prtLock` above `effLock` to match the convention. **Not scroll-related; surfaced during the scroll
   A/B.** Probability scales with bot count -> was a hard 2000-bot blocker. Server now stable 10+ min.
3. **Scroll resolution + cache-thrash** (`502bb23e2` + `593ce61c8`): coarsened the curve memo key
   0.001->0.1, relative fixed-point epsilon, bucketed the cache key's `baseScore` (was
   `doubleToLongBits(baseOffenseValue(bot,...))` -> a distinct curve per bot -> cache overflow -> wholesale
   clear), raised cap 4096->32768. **These did NOT move scroll-dp** (stayed ~1.0 core): the population is
   low-level gear (thousand-meso, sparse integer scores) so resolution is a near-no-op, and the solves are
   demand-bound not cache-bound. **Kept anyway** — they're correct scaling/robustness fixes (help
   high-level gear + prevent cache pathology). Lesson (again): per-op/cache tuning ≠ demand reduction.
4. **Chaos gate** (`82d82f22b`) — the actual scroll-DP lever: chaos gamble now only runs on offense gear
   (`watk>0 || (int>0 && matk>0)`), gated BEFORE the per-equip market-quote + stat-convolution.
   **`scroll-dp` 0.995 -> 0.207 cores (-79%)**, `chaos-scan` 0.597 -> 0.203, `tick-total` 1.85 -> 1.04.
   The scroll DP was chaos-dominated all along (chaos evaluated every owned piece incl. pure-DEF junk).
   Owner's domain rule: chaos only worth it on ATT items or magic weapons with both INT and MATT.

## Current CPU picture (726 bots, 8GB, real process CPU ~1.0-1.4 cores / 17-23%)
Confirmed by a 90s JFR profile (`jcmd <pid> JFR.start settings=profile`, analyzed with `jfr print
--events jdk.ExecutionSample`): **the cost is bot code, NOT upstream.** Packets (`net.*`) = 5 samples;
`client.*` (Character/inv) ~4%; `server.maps` ~3%; GC 0.68%. The consumers, post-fixes:
- DECIDE_POOL scroll/grind DP: now ~0.2-0.4 core (was ~1.0 pegged; chaos gate did it).
- Bot tick threads: ~1.0 core across 6 workers.
- **Nav graph builds at RUNTIME** (bursty ~2s each, `bot-nav-graph-warmup` thread) — see open items.

## OPEN ITEMS (ranked)
1. **Nav runtime graph builds** — the other 100%-spike source. Log shows `Built bot nav graph map
   (107000403) in 1960ms` at runtime though boot "loaded 5841 maps from cache". Likely the disk cache is
   keyed by (map, speed, jump) so varied bot movement profiles miss it -> rebuild storms as bots enter
   maps. Investigate the cache key / pre-bake common speed/jump. NAV TERRITORY — read `.claude/skills/
   bot-nav/SKILL.md`, respect GRAPH_VERSION rules, skip nav/graph tests unless touched.
2. **dropBuffStats latent deadlock (2nd inversion, NOT fixed)** — `dropBuffStats` holds `chrLock` then
   reaches `prtLock` via `isActive->getPartyMembersOnSameMap` (chr->prt), vs `registerEffect`'s prt->chr.
   Rarer (conditional on `bestApplied` + same-char race) but a real 2000-bot risk. Recommend a proper
   global **prt -> eff -> chr** lock-ordering audit of `client.Character` before the 2000-bot run.
3. **Job-advance-stuck bug** — dozens of bots endlessly retry routing to the Magician instructor (npc
   1032001, map 101000003) from Amherst (`route-reachable=false`); ERROR-spams the log and burns decide
   cycles. Pre-existing travel/nav routing issue. Visualize: `/mapgraph?id=1000000`.
4. **Remaining scroll demand** (lower priority now): the planner samples ~120 distinct target-scores per
   scan (`BotScrollPlanner.valueOf`/`evApply`, key `round(v*1000)`); cache the plan RESULT per bot or
   coarsen/cap the planner sampling if scroll-dp climbs again.
5. **2000-bot test** — still blocked by the pop plateau (~726 at mult 85) + the managed_bot pool (~1000).
   Grow the pool (`POPULATION_AUTOGEN_MAX`, currently 20/sweep) and investigate the plateau.
6. **Stage 3 slice 2** (pending, pre-session): exact resource honesty for abstract grinders (attacks/kill
   MP, ammo, HP/pot via the danger model). Abstract bots take no damage so don't buy HP pots (economy gap).

## Measurement playbook (this session's A/B loop — repeat it)
1. Stop server: `Stop-Process -Id <pid> -Force` (find it: newest `java` by StartTime).
2. Rebuild jar: `mvn package -DskipTests -q` (jar is locked while running — stop first). Build via
   `cmd //c "C:\ProgramData\chocolatey\lib\maven\apache-maven-3.9.14\bin\mvn.cmd ..."` (see
   `~/.claude/.../reference_build_tools.md`; Java = Corretto jdk21.0.4_7).
3. Launch (background, run `java` DIRECTLY not launch.bat — its `pause` hangs):
   `java -Xms8g -Xmx8g -XX:+UseG1GC -XX:MaxGCPauseMillis=150 -XX:InitiatingHeapOccupancyPercent=40
   -Dwz-path=wz -jar target/Cosmic.jar`
4. Warm ~10 min at mult 85 (~726 bots): loop `POST /api/settings {"cmd":"pop","mult":"85","sweep":"true"}`
   every 60s. The grind advisor RAMPS 6-8 min in and scroll-dp/chaos are BURSTY — warm before capturing.
5. Capture: real CPU = `Get-Process` `TotalProcessorTime` delta over 60-90s (GROUND TRUTH; the perflog
   "cores" are WALL-CLOCK, cross-check). Sections: `POST /api/settings {"cmd":"perflog","seconds":"30",
   "html":"false"}` -> `logs/bot-perf/*.csv`. Deadlock: `jstack <pid> | grep -i "Found.*deadlock"`.
   Whole-process profile: JFR (above). Population/observed maps: parse `/api/live` (maps->bots/players).
6. Gotchas: PowerShell 5.1 `if` is NOT an expression (`Write-Output (if...)` errors); jstack via `>` writes
   UTF-16 (use `iconv -f UTF-16LE` or PS `Out-File -Encoding utf8`); Python is `py` (not python/python3);
   the `pop` cmd returns an empty body but DOES set. Stale background "Launch server" tasks report exit 127
   when you kill the server you started — expected, ignore.

## Server state at handoff
Running: PID 1156 (may differ next session — re-find it), 8GB heap, mult 85 (~726 bots), chaos-gate build
(HEAD `82d82f22b`). Tree clean. NO deadlock. If restarting, follow the playbook above.
```
