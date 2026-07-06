# Unobserved-LOD Stage 3 — abstract grind (read-only prep, NOT yet built)

Status: **PLAN ONLY** (2026-07-05). No code/build/server changes. Anchors are current on dev tip
7fa267df3 (also the base of the stage2 branch). Stage 3 lands AFTER Stage 2 is merged to dev.

Scope (design §2.3, §5 stage 3): a LOD1 (unobserved) bot stops running real combat and instead emits
**abstract kill events** at a **calibrated** rate, killing real spawned mobs through the real death
path so exp/drops/meso/quests/spawn-bookkeeping stay full-fidelity, while charging honest resource
consumption. This is the big lever: it removes combat-target-search / combat-plan / attack packets
for the unobserved population AND makes the 500ms cadence safe (no per-50ms combat), AND resolves the
Stage-2 frozen-Y-combat concern (no position-based hitbox in LOD1 anymore).

## 0. The abstract-kill entry (key finding)
`MapleMap.damageMonster(Character chr, Monster mob, int damage, short delay)` (**MapleMap.java:1283**)
is the real damage SSOT: `monster.damage(chr, damage, false)` registers the damage crediting `chr`
in the mob's damage map (so exp is distributed to it, Monster.distributeExperience at
Monster.java:603), and on lethal it calls `killMonster(mob, chr, /*withDrops*/true, delay)`
(MapleMap.java:1308) — the SAME path real player kills reach. So an abstract kill is simply:
```
bot.getMap().damageMonster(bot, mob, mob.getHp());   // exactly-lethal, credited to the bot
```
This is better than calling `killMonster` directly (raw killMonster wouldn't register the bot as a
damager, so exp would mis-distribute). **damageMonster/killMonster are existing public non-bot
methods — Stage 3 CALLS them, does not modify them ⇒ Stage 3 has ZERO non-bot source edits** (all
new code in `server.bots`). Contrast Stage 2's one MapleMap.addPlayer edit.
- Party exp nuance: one bot dealing 100% damage credits itself (and its party via party share) like a
  solo kill. Real party grinding splits by damage; abstract credits one member. Party exp sharing is
  largely party-based not damage-proportional, so this is close; verify in the §5 comparison.

## 1. Where it branches
Grind dispatch is `if (entry.grinding) { ... tickGrindMode(...) ... }` at **BotManager.java:4237**.
Stage 3 branch: when `entry.lod==LOD1 && cfg.SIMPLIFY_UNOBSERVED_BOTS_GRIND`, call a new
`tickAbstractGrind(entry, bot)` INSTEAD of `tickGrindMode` + the downstream `stepMovementCore`
(the bot doesn't move to mobs — it stands and emits kills). Guard the same "covered" way as Stage 2
(grounded, not trading/market/ferry/FM/gacha) so special states keep real behavior.

## 2. Calibrated kill-rate generator (consumes Stage 0)
Stage 0 (`BotKillCalibration`) already provides both inputs:
- A bot's OWN fresh measured rate on this (map,mob): `BotKillCalibration.freshBotRate(botCharId,
  mapId, mobId, freshWithinMs)` — use when > 0 (design: fresh own rate overrides the bucket).
- Else the bucket factor: `calibratedKph = BotGrindPlanner.killsPerHour(candidate) ×
  BotKillCalibration.bucketFactor(jobId, level/10)`. The prediction is the advisor's committed
  `killsPerHour` (already noted per plan at BotKillCalibration.notePrediction, BotAutopilotManager
  install). If no candidate handy, reconstruct killsPerHour from the mob's kill-time model or reuse
  the entry's stored prediction.
- Draw the next-kill delay as an exponential-ish jittered interval around `3600/calibratedKph`
  seconds (Poisson-like, so kills don't come metronomically). Store next-kill deadline on BotEntry.

## 3. Per kill event
On each due kill (design §2.3):
1. Pick a live spawned mob of the target type on the bot's map: iterate `bot.getMap()` monsters
   (getMonsters/getAllMonsters filtered `isAlive()` and by target mobId; PREFER `entry.activeQuestMobIds`).
   If none (spawn-limited): SKIP — the supply cap (RESPAWN_PERIOD_SECONDS) is already in the rate
   model, so skipping keeps rates honest.
2. Kill it: `bot.getMap().damageMonster(bot, mob, mob.getHp())` (§0). Exp/drops/quest counters/spawn
   bookkeeping all real.
3. Loot: after a 0.5–2s humanlike delay, run the existing eligibility + pickup path —
   `BotLootEligibility.canBotLoot / canBotTargetLoot` (BotLootEligibility.java:28/55) via
   `BotInventoryManager.tickPassiveLoot` (BotInventoryManager.java:127), with the bot's materialized
   position set near the mob's death spot so distance checks pass.

## 4. Honest resource consumption (must stay real — owner rule)
Per kill, charge what a real kill would burn:
- `attacksPerKill = ceil(killSeconds / attackPeriod)` × per-attack cost:
  - MP: `bot.addMP(-mpCostPerAttack × attacksPerKill)` (existing deduction idiom, e.g.
    BotNavigationManager.java:768 `bot.addMP(-mpCon)`; skill MP cost from the skill's StatEffect).
  - Ammo/bullets: consume via the existing ranged-consume path (the amount a ranged attack removes).
- Potions/HP: charge intake per coarse tick from the expected-damage/danger model
  (`BotDangerAssessment` / `BotPotionManager`) rather than real hits — LOD1 bots take no real damage.
- Passive regen unchanged (`tickPassiveRecovery` is already 10s-cadence — untouched).
- Buffs: rebuff on the existing deadline model (P3) via the normal StatEffect path (`tickBuffs`);
  packets go to an empty map (measured cheap). No hitboxes / target search / attack packets in LOD1.
- Death: LOD1 bots don't die (advisor prunes danger-blocked maps; expected-HP charging covers pot
  economics). Deliberate simplification (design §2.3).

## 5. The cadence flip (finally safe here)
In `cadenceForLod` (BotManager.java:978) flip `stage3AbstractGrindReady` → true. Now a LOD1 bot with
`_CADENCE && _PHYSICS && lod1MotionPlanCovered` runs at `LOD1_TICK_MS` (500ms). Combat is abstract
(deadline-driven, cadence-independent), movement is motion-plan (time-based), travel is timed-warp —
nothing per-tick needs 50ms. The `consumeAiTick` real-interval fix (Stage 2 slice 1) makes AI ticks
keep real-time pace at 500ms. Additionally exclude real-walking travel legs (`followTravelTaxiNpcId
!= 0 || followTravelFerry`) from the covered predicate so taxi/ferry approaches keep 50ms until they
too are abstracted (or accept LOD0 during those brief legs).
- This is the ~10× wakeup reduction — the headline 2000-bot win (design §3).

## 6. Frozen-Y resolution
Stage 2's frozen-Y-during-real-combat concern (missed attacks on multi-level maps) disappears: LOD1
combat is no longer position-based, so a bot on the "wrong" Y still emits calibrated kills against
live mobs of its target type anywhere on the map. (If Stage 2 shipped the interim Y-band gate, it
becomes moot and can be removed for LOD1.)

## 7. Verification (design §5 stage 3)
Pick ~20 bots; over an hour compare LOD1 vs the Stage-0 LOD0 baselines: exp/hr, drops/hr, meso/hr,
potion burn; confirm quest progress still advances (real killMonster path). `/api/killcalib` shows
the calibration the generator consumes. Perf CSV: combat-target-search / combat-plan ≈0 and tick-total
call-rate ~10× lower for the LOD1 population.

## 8. Surface summary
- **Non-bot source: NONE** (damageMonster/killMonster/BotLootEligibility are called, not changed).
- **Bot source:** `BotManager` (tickAbstractGrind, grind branch, cadence flip + covered exclusions),
  `BotEntry` (next-kill deadline + any abstract-grind bookkeeping fields), reuse of
  `BotKillCalibration`, `BotLootEligibility`, `BotInventoryManager`, `BotDangerAssessment`,
  `BotPotionManager`, `BotGrindPlanner.killsPerHour`.
- Risks: rate divergence (mitigated by Stage-0 calibration + own-fresh-rate override); party exp
  attribution (verify in §5); loot distance checks needing a materialized position (set near death
  spot); mobs of the target type absent (skip = honest per the supply cap).
