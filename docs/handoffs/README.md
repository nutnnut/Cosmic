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
| Bot→mob hit chance / accuracy | `CombatFormulaProvider.calculateMobHitChance` / `getTotalAccuracy` | `server/combat/CombatFormulaProvider.java:114,146` |
| Skill per-cast item consumption (rocks) | `StatEffect.itemCon`/`itemConNo` (add getters) — rocks `4006001`/`4006000` | `server/StatEffect.java:147,496,951` |
| What gear the bot can FARM | `BotGrindAdvisor.gearProspects` / `gearDropsByMob` | `server/bots/BotGrindAdvisor.java:~580` |
| Active farm-a-specific-item | farm-item autopilot / `BotGrindAdvisor.recommendFarmItem`, `entry.autopilotFarmItemId` | `server/bots/BotAutopilotManager.java`, `BotGrindAdvisor.java` |
| Potion donor-share (mirror for rocks) | `BotPotionManager.requestPotShare` / `selectPotDonor` | `server/bots/BotPotionManager.java:528,600` |
| Useless-scroll-to-self (stat-only; extend w/ category) | `BotInventoryManager.isIrrelevantEquipScroll` | `server/bots/BotInventoryManager.java:2159` |
| Scroll-plan valuation (run OFF-thread) | `BotScrollManager.buildBestPlan` via `scheduleScrollPlan` → `DECIDE_POOL` | `server/bots/BotScrollManager.java` |
| Party exp level-gap cutoff | `EXP_SPLIT_LEECH_INTERVAL`/`LEVEL_INTERVAL` = 5; `Monster.distributePartyExperience` | `config.yaml:306`, `server/life/Monster.java:549` |

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
> penalty + Sleepywood block). `05-self-preservation.md` is the **combat-side remainder** — also DONE now.

**Also shipped later in the session (don't redo):** quest commitment (map+target bias, SSOT
`BotQuestManager.activeQuestMobIds`) · opportunistic quest grab · combat self-preservation
(`BotDangerAssessment`, `isFragile`-gated + **anti-freeze give-up** `BotManager.applyDangerRetreatGiveUp`)
· **job advance walks to the class town instructor** (`BotStarterKitManager.jobChangeNpcFor`/`tickJobErrand`,
shared `BotTravelManager.tickApproachNpc`) · **accuracy-aware grinding** (kill-time factors hit chance in
`BotGrindAdvisor.killSeconds`; `BotCombatManager.lowAccuracyPenalty`; AP **DEX floor for ~25% hit**
`BotBuildManager.accuracyDexFloor`) · **grind-target commitment** (`cfg.GRIND_TARGET_COMMIT_MS`, stops
retarget thrash while approaching) · **scroll valuation moved off the tick thread**
(`BotScrollManager.scheduleScrollPlan` → `BotGrindAdvisor.DECIDE_POOL`) · crossbowman SP-leak fix (FOCUS).
Known follow-ups (NOT done): other 2nd-job builds (warrior/mage/thief) likely share the SP under-allocation
leak (fix with the class-appropriate 1st-job filler, like FOCUS for archer); `BotCombatManagerTest` has 6
pre-existing WZ-data failures unrelated to this work.

## Items
**01–05 are DONE (shipped on `experimental`).** Open items are **06–09** below.
- `01-name-generator.md` — ✅ DONE (`BotNameGenerator`).
- `02-appearance-and-per-bot-config.md` — ✅ DONE (`BotAppearance` + `bot_config` table).
- `03-mob-dodge-while-walking.md` — ✅ DONE (dodge on committed WALK edges).
- `04-talk-quests.md` — ✅ DONE (`BotQuestManager` talk quests).
- `05-self-preservation.md` — ✅ DONE (touch-danger targeting + bounded proactive retreat).
- **`06-summoning-rock.md`** — OPEN. Sparing skill-level/TTK-scaled use of Shadow-Partner-type rock
  buffs + request rocks like potions when low. Touches buff casting + supply share.
- **`07-party-level-gap-leech.md`** — OPEN. Higher bots idle (do no damage) when the party level gap
  approaches the exp-share cutoff (verified 5) so lower bots catch up. Touches party/cohort + combat gate.
- **`08-scroll-opportunity-cost.md`** — OPEN, CENTRAL. Read `docs/bot/scroll-opportunity-cost.md` (design).
  Scrolled-potential valuation + per-scroll opportunity cost + ACTIVE farmable-base steering.
- **`09-proactive-scroll-offer.md`** — OPEN. Offer useless-to-self scrolls to a party member who can use
  them (category-aware, not stat-only). Independent of the others.

**Conflict note for 06–09:** `08` (scroll) and `09` (scroll offer) both touch `BotScrollManager`/
`BotInventoryManager` — don't run them in parallel in the same worktree (or sequence them). `06` (buff/
supply) and `07` (party/combat-gate) are mostly isolated from the scroll pair and from each other,
though `06` and `07` both read combat/skill code — coordinate if parallel.
