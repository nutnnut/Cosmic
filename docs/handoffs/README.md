# Bot backlog handoffs

Self-contained task docs for implementing the remaining ownerless-bot backlog. Each `NN-*.md`
is written for a **fresh agent with no conversation history**: read this README first, then your
item doc, then execute. One item per agent (they touch mostly different files — see conflicts below).

## How to use (orchestrator)
- Spawn one subagent per item doc. Prompt: "Read `docs/handoffs/README.md` and
  `docs/handoffs/NN-<item>.md`, implement it per the project rules, compile, and commit."
- **File-conflict note:** `02-appearance` edits `client/creator/BotCreator.java`; nothing else here
  touches it. `05-self-preservation` and `03-mob-dodge` both touch combat/movement — don't run them
  in parallel in the same worktree. `01-name-generator` and `04-talk-quests` are isolated.

## Build / verify (Windows, this machine)
- Compile (fast loop): `"C:/ProgramData/chocolatey/lib/maven/apache-maven-3.9.14/bin/mvn.cmd" -q -o compile`
  — exit 0 = success (it prints nothing on success). Run from repo root `D:\GameServers\Maplestory\Cosmic`.
- Targeted tests: `...mvn.cmd test -Dtest=BotBuildManagerTest,BotEquipManagerTest,...`
- Java: Amazon Corretto 21. Maven sometimes shows a spurious incremental-compile cascade with
  `-Dtest=` filtering; a full `mvn -q -o test-compile` confirms the real state.
- **Rule #4: do NOT run navigation/graph tests** (they dominate runtime). Reading nav code is fine.

## Git
- Branch: `experimental` (commit directly here). Commit only your own work; keep diffs surgical.
- Commit message footer (required):
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`

## Project rules (from CLAUDE.md — non-negotiable)
1. **Share player code, don't duplicate.** Bots must play legally. If a player counterpart exists,
   reuse it; if inaccessible, extract/refactor rather than reimplement.
2. **Minimize upstream (non-bot) diff.** Touch non-bot code only to expose/extract for bots, minimally.
3. **Smart / dynamic / adaptive / humanlike**, with randomness (delay jitter). Prefer emergent
   behavior from real calculation over hardcoded scripts.
4. Skip nav/graph tests unless you touched them.
5. Repo-scoped docs go in the repo (like this folder), not PC-local memory.
6. **SSOT** — never build a parallel implementation of something that already exists (esp. item/equip
   valuation, damage formulas). Find the one source and reuse it.
- **Verify IDs** (NPC/item/map/quest/skill) against handbook / WZ data — never guess.
- Bot chat is **US-ASCII only** (non-ASCII renders as `?`).

## The ownerless-bot model
- Ownerless = **self-owned**: `entry.owner == entry.bot`. Makes `BotManager.isAutopilotActive(entry)`
  true and `hasOnlinePlayerOwner(entry)` false. Gate: `isOwnerless = isAutopilotActive && !hasOnlinePlayerOwner`.
- Fresh ownerless bots spawn at **Mushroom Town (MapId.MUSHROOM_TOWN = 10000)** (real new-player map),
  level 1 Beginner, and auto-start solo autopilot (`BotManager.spawnOwnerlessBot`).
- `@spawnbot <name> [confirm] [autopilot]` — `autopilot` flag makes it self-owned/ownerless.

## Key SSOTs and hooks (reuse these — do not reinvent)
| Concern | SSOT | Location |
|---|---|---|
| Equip / gear valuation | `BotScrollManager.potentialValue(bot, ii, eq)` (→ `offenseValue` + scroll headroom) | `server/bots/BotScrollManager.java:932`, `:817` |
| Worn item in a slot | `BotScrollManager.wornInSlot(bot, ii, slot)`; slot id `primarySlot(ii, itemId)` | `BotScrollManager.java:544`, `:641` |
| Physical DPS | `BotEquipManager.rawPhysicalMax` × `1000/weaponCycleMs` (StatSnapshot) | `server/bots/BotEquipManager.java` |
| Job → primary/secondary stat | `BotScrollManager.mainSecondary(jobId, mageOut)` | `BotScrollManager.java` (package-private) |
| Mob touch (contact) damage vs armor | `BotDefenseDataProvider.rollPhysicalTouchDamage(bot, mob)` | `server/bots/BotDefenseDataProvider.java:~180` |
| Travel-time cost / score weight | `BotTravelCost.floodSeconds` / `scoreWeight(seconds, mapId, botLevel)` | `server/bots/BotTravelCost.java` |
| Route reachability w/ pruning | `BotWorldGraph.reachableWithin(from, hops, opts, IntPredicate blocked)` | `server/bots/BotWorldGraph.java:227` |
| Per-bot map avoid / danger-region | `BotAutopilotManager.isAvoided(entry, map)`, `isDangerRegionBlocked(bot, map)` | `server/bots/BotAutopilotManager.java` |
| Grind-target selection | `BotGrindAdvisor.recommend(entry, bot, reachablePredicate, travelWeight)` | `server/bots/BotGrindAdvisor.java` |
| Skill-cast (self buff, MP cost, packets) | `BotCombatManager.castSupportSkill(...)` (see `tryCastRecovery` for the pattern) | `server/bots/BotCombatManager.java:2507`, `:~2543` |
| Quest piggyback infra | `BotQuestManager` (Config `AUTO_QUESTS`, `QUEST_PIGGYBACK`; `tickScan`, `tickErrand`) | `server/bots/BotQuestManager.java` |
| Global tunables | `BotManager.cfg` (`Config`); combat tunables `BotCombatManager.cfg` (hot via `!botcfg`) | `BotManager.java:58`, `BotCombatManager.java:~180` |
| Char-create defaults (appearance) | `client.creator.BotCreator.createCharacter` | `client/creator/BotCreator.java` |

## Already shipped this session (don't redo; build on)
broke-shop affordability gate · pre-travel gate (SSOT `BotShopManager.canAffordPotResupply`) ·
Beginner **Recovery** skill (`BotCombatManager.tryCastRecovery`, maxHP<500 cap `cfg.RECOVERY_MAXHP_CAP`,
SP build `BotBuildManager.BEGINNER_BUILD`) · errand cooldown 10m · **death-loop breaker**
(`BotManager.respawnBot` streak + `BotAutopilotManager.onDeathLoop`, blacklist via `isAvoided`) ·
**risk-aware travel** (`BotTravelCost.travelRiskFactor` linear by level + **Sleepywood region block**
`isSleepywoodRegion`/`isDangerRegionBlocked`, ids `105######`, level gate 15) · pot **budget split**
(`BotShopManager.buyPotsCapped`) · spawn at Mushroom Town · **landing fix** (no counterstrafe/facing
flip — `BotPhysicsEngine.landOnGround`) · **shop gear buying** (`BotShopManager.evaluateAndBuyEquip`).

> Note: a partial **self-preservation** layer already shipped (the travel-side: level-scaled travel
> penalty + Sleepywood block). `05-self-preservation.md` is the **combat-side remainder**.

## Items
- `01-name-generator.md` — procedural MMO name generator (design ready).
- `02-appearance-and-per-bot-config.md` — randomize appearance + per-bot config store (verify legal ids first).
- `03-mob-dodge-while-walking.md` — wire the existing jump-apex mob dodge into travel (investigation-first).
- `04-talk-quests.md` — dynamic talk-quest completion via `BotQuestManager` (investigation-first).
- `05-self-preservation.md` — combat-side: touch-damage-aware target avoidance + proactive retreat.
