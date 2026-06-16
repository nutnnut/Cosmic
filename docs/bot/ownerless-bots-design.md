# Ownerless bots: autonomous job + AP/SP decisions

Status: shipped 2026-06-15. Scope: make a fully-autonomous (ownerless) bot resolve the three
owner-chat *choice* points by itself so it can progress from a fresh level 1 without a human in the
loop. Companion bots with a present, online owner are unchanged.

## Problem

Every bot decision that is a *choice* — 1st/2nd job, AP build, Hero SP variant — was routed to the
owner via chat and waited for a typed reply. With no owner to answer:

- `BotBuildManager.buildJobPrompt` called `parkIfAutopilot` at the lv8/10/30 milestone; with no
  online owner that parks the bot in town **forever** (`parkAutopilotForJobDecision` →
  owner-inactive safe mode). The bot never leaves Beginner.
- `BotBuildManager.autoAssignAp` **no-ops** when `entry.apBuild == null`, so AP is never spent.

A fresh ownerless bot therefore soft-locked at level 8–10 with unspent AP, still a Beginner.

## The ownerless model (reuse, don't reinvent)

Ownerless bots are **self-owned**: `entry.owner == entry.bot`. That already makes
`BotManager.isAutopilotActive(entry)` true and `BotManager.hasOnlinePlayerOwner(entry)` false — the
existing independent-play spine the `@botme` takeover path uses. We add a single gate:

```java
isOwnerless(entry) == BotManager.isAutopilotActive(entry) && !BotManager.hasOnlinePlayerOwner(entry)
```

which is true for a self-owned bot *or* an autopilot bot whose owner is offline (the same condition
that otherwise soft-locks). Supervised and autopilot-with-online-owner bots keep the owner-chat path.

## Part 1 — Autonomous job picker

The deterministic 3rd/4th job advance already auto-runs for autopilot bots via
`BotStarterKitManager.advanceJob` (`changeJob` + `handleJobAdvance`, no NPC/quest). The 1st/2nd
advancements are real *choices*, so they only needed a class **chosen** instead of typed.

- `BotBuildManager.buildJobPrompt`: at lv10 (Beginner→1st) and lv30 (1st→2nd), if `isOwnerless`,
  pick a class and `scheduleAutoAdvance(entry, target)` (same deferred path as 3rd/4th), returning
  `null` instead of the owner prompt. Ownerless bots skip the lv8 mage-only early choice and pick
  from all five classes at lv10.
- Topology SSOT: `BotStarterKitManager.firstJobChoices()` / `secondJobChoices(Job)` mirror the
  option sets `buildJobPrompt` offers the owner.
- Weights: `BotManager.cfg.JOB_WEIGHTS` (EnumMap, uniform default) biases the 1st-job pick;
  2nd-job picks are uniform within the branch. `pickWeightedJob` / `weightedPick` in `BotBuildManager`.

## Part 2 — Autonomous AP resolver (reactive, owned-weapons, adaptive)

The allocation mechanism already exists (`ApBuild(primary, secondary, secondaryTarget)` +
`autoAssignAp` + `reallocateAp`). The missing piece was picking `secondaryTarget` with no owner.

**Why a minimum-secondary policy is correct.** `BotEquipManager.rawPhysicalMax` is
`ceil((mult·primary + secondary)/100 · watk)`: the primary stat carries the weapon multiplier
(~3.4–4.6×), the secondary carries ×1. A secondary AP point is therefore ~70–78% wasted damage —
worth spending **only to clear a weapon's equip requirement**, and only when the weapon it unlocks
out-DPSes staying on a cheaper weapon with that AP back in the primary.

**Algorithm** (`BotEquipManager.recommendSecondaryTarget` → pure `chooseSecondaryTarget`):
for each candidate weapon, fund its secondary requirement first (`need = max(floor, reqSec −
gearSecondary)`), put the rest of the freely-movable AP into primary, score DPS with the SSOT
`rawPhysicalMax × 1000/cycle`, and take the secondary target of the max-DPS wieldable weapon. Never
below the job floor, never below what keeps the **currently-equipped** weapon wearable (anti-strand).

Candidates are the bot's **owned + equipped** compatible weapons (reused `collectAutoEquipCandidates`
slot `-11` pool, which already includes stat-only-blocked items). As gear supplies the secondary
stat, `need` could *drop* — but it is the **ceiling**, not a setpoint: the resolver is run at
level-up / job-advance and the target only ever **ratchets up** to unlock a better owned weapon.

Respec legality (rule #1) — mirrors real players: `maybeRecomputeAutonomousApBuild` is **additive
only**. It never reduces an already-spent stat and never autonomously respecs, exactly matching the
real-player job-advance path (`Character.changeJob`, `Character.java:1228-1271`), which **gains** AP
and SP at each advancement but never resets or redistributes them. Two consequences, both
player-accurate:
- A bot that invested secondary early to wield a weapon does **not** drift back toward pure when later
  gear covers the requirement — a scroll-less player is likewise stuck.
- Beginner auto-assign banks ~45 STR *before* the class is chosen (`Character.java:6540-6550`); for a
  mage/archer/thief that is dead weight the bot carries forward — again exactly what a real player who
  auto-assigned beginner AP then changed class is stuck with.

Reclaiming any of this (funded→pure, or the wasted beginner STR) is the **deferred NX AP-reset
feature**: a well-geared bot with spare NX dynamically decides whether a legal AP Reset is worth it.
Note: the pre-existing *owned*-bot path does reallocate once at the beginner→1st advance
(`handleJobAdvance`, when the owner has set an AP build) — that is the one non-player-accurate spot,
left as-is (out of scope); ownerless bots never hit it (their build is set after that check).

Wiring in `BotBuildManager`: `resolveApBuild` maps the job→stat SSOT
(`BotScrollManager.mainSecondary`, now package-private) to the build and calls the resolver;
`maybeRecomputeAutonomousApBuild` (called from `checkLevelUp` and `handleJobAdvance`) re-tunes the
target and reallocates only when it moved; `buildApPrompt` resolves-and-returns-null for ownerless
bots instead of prompting. **Mages park the secondary (LUK) at the floor** — magic damage and
wand/staff requirements ignore LUK.

Deliberately **not** done: aspirational/unowned-weapon stretch (build toward a Red Craven you don't
own yet). It contradicts `BotScrollManager.levelsUntilWearable`'s deliberate "never project
secondary" SSOT. Possible v2 if desired: feed the `BotGrindAdvisor` drop horizon as extra candidates
and relax `levelsUntilWearable` in tandem.

## Part 3 — Hero SP variant default

`buildSpVariantPrompt` returns `null` for ownerless Heroes after setting `entry.spVariant = "2h"`, so
`autoAssignSp` proceeds without an owner reply.

## Verified independent (no code change)

- **Resupply** (`BotPotionManager`): autopilot bots try `requestResupplyErrand` first;
  `canWalkToOwner` is false for self-owned bots, so the walk-to-owner branch is skipped.
- **Party** (`BotAutopilotManager.startParty`): `owner` is only null-checked, never dereferenced for
  logic, and is already driven with a bot-as-owner by `@botme`.
- **autoEquip** (`BotEquipManager.autoEquip`): the `owner` arg drives only a pickup notification; the
  optimization is owner-agnostic and already runs for self-owned bots.

## Verified spawn / navigation (Part 4)

- **Fresh-lv1 spawn exists**: `client.creator.BotCreator.createCharacter()` creates a level-1
  Beginner on a town spawn; `BotManager.registerSpawnedBot(player, player, player)` registers it
  self-owned (`owner == bot`).
- **Travel off the start map works**: `BotWorldGraph.reachableWithin` routes multi-hop via
  portals/taxis/ferries (ferries enabled for self-owned bots); no level or quest gate exists in
  `BotWorldGraph` / `BotTravelManager` / `BotGrindAdvisor` that would trap a fresh low-level bot.

## Part 5 — Launch trigger (auto-start on spawn)

`BotAutopilotManager.start` was otherwise reached only from owner chat commands
(`BotChatManager.java:1070/1085/2028`), so a bot with no owner to type "go autopilot" never starts
grinding, never levels, and none of the Part 1–3 autonomy fires. The self-owned auto-start already
existed for `@botme` (`BotManager.startTakeoverAutopilot` → solo, or party if partied with bots);
`@spawnbot <name> confirm` however registers the fresh bot **owner = the spawner** and follows them.

Added `BotManager.spawnOwnerlessBot(requester, botName)`: loads the offline bot online, registers it
**self-owned** (`owner == bot`), and calls the existing `startTakeoverAutopilot` so it begins solo
autopilot immediately. Surfaced via a new `@spawnbot <name> [confirm] autopilot` flag. The fresh bot
then levels under autopilot and the job/AP/SP autonomy above engages at each milestone. No change to
the central `registerSpawnedBot` or the `@botme` path.

Intended end-to-end for a fresh ownerless bot: `@spawnbot Foo confirm autopilot` → lv1 self-owned
bot → solo autopilot → travels to a grind map (no level/quest gate) → auto-picks 1st job at lv10,
auto-assigns AP/SP, advances 2nd/3rd/4th — all with no human in the loop. **Status: logic-verified
(unit tests + code trace); the live spawn→autopilot→level chain has NOT been run on a server this
session — an in-game run is still pending.**

## Known follow-ups (out of scope here)

- **NX-funded AP reset (deferred, requested)**: since the autonomous build is additive-only and never
  respecs, a bot that over-invested secondary early stays off-pure. A future feature has a well-geared
  bot with spare NX dynamically decide whether buying an AP reset is worth the DPS gain (compare the
  resolver's current target against the spent secondary, value the freed AP, weigh against NX cost),
  then respec **legally** via the reset. This is the legal path that restores the funded→pure drift.
- `BotOfferManager.mainSecondaryStats` duplicates the `BotScrollManager.mainSecondary` job→stat
  mapping — fold into one SSOT (rule #6).
- Two offense scorers diverge (`BotEquipManager.usefulStatSum` WATK×4 vs
  `BotScrollManager.offenseValue` WATK×5). The AP resolver reuses `rawPhysicalMax`/`weaponCycleMs`
  (the physical DPS SSOT) and does not add a third; unifying the two stat-sum scorers is a separate
  refactor.
- Pirate has no AP build (no owner prompt either) — `resolveApBuild` returns null for Pirate, so
  ownerless pirates leave AP on job defaults until a pirate build is added.
- Economy-sim layer (`docs/bot/economy-design.md`: ledger, price discovery, demand/WTP, market
  execution) is a separate, larger track — not required for bots to spawn and play.

## Files

| File | Change |
|---|---|
| `server/bots/BotBuildManager.java` | `isOwnerless`, `resolveApBuild`, `maybeRecomputeAutonomousApBuild`, `pickWeightedJob`/`weightedPick`, `statTypeOf`/`statOf`; ownerless branches in `buildJobPrompt`/`buildApPrompt`/`buildSpVariantPrompt`; recompute hooks in `checkLevelUp`/`handleJobAdvance` |
| `server/bots/BotEquipManager.java` | `recommendSecondaryTarget` + pure `chooseSecondaryTarget` + `WeaponCand` (reuses `rawPhysicalMax`/`weaponCycleMs`/`StatSnapshot`/`collectAutoEquipCandidates`) |
| `server/bots/BotScrollManager.java` | `mainSecondary` exposed package-private (job→stat SSOT) |
| `server/bots/BotStarterKitManager.java` | `firstJobChoices()` / `secondJobChoices(Job)` |
| `server/bots/BotManager.java` | `Config.JOB_WEIGHTS` (uniform default) |

## Tests

`BotEquipManagerTest`: `chooseSecondaryTarget` — gear-covers→floor, gear-short→req−gear,
upgrade-not-worth→floor, upgrade-worth→req, anti-strand, no-candidates→floor.
`BotBuildManagerTest`: `weightedPick` honors zero weights, `pickWeightedJob` returns valid
first/second jobs, mage `resolveApBuild` parks secondary at floor.
`BotStarterKitManagerTest`: `firstJobChoices` / `secondJobChoices` topology.

Fast loop: `mvn test -Dtest=BotBuildManagerTest,BotEquipManagerTest,BotStarterKitManagerTest`.
