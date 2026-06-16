# 05 — Self-preservation (combat-side: touch-damage avoidance + proactive retreat)

**Status:** scoped (item-9 report in hand). Read `README.md` first. Touches combat/target-selection —
do NOT run in parallel with `03-mob-dodge` in the same worktree.

## Context: what already shipped (don't redo)
The **travel-side** of self-preservation already landed this session:
- Linear per-level travel penalty (`BotTravelCost.travelRiskFactor`) — fragile bots prefer nearby maps.
- Hard **Sleepywood region block** for <15 (`BotAutopilotManager.isDangerRegionBlocked`, `isSleepywoodRegion`),
  pruned from the route flood via `BotWorldGraph.reachableWithin(blocked)`.
- Death-loop breaker (escape to town + per-bot map blacklist `isAvoided`).

**This item = the COMBAT side that's still missing:** (1) avoid/de-prioritize mobs whose touch damage
is dangerous relative to the bot's armor/HP, and (2) **proactive retreat** before near-death (today only
*reactive* exists). Premise verified by scope: **no proactive HP-based retreat exists** — only spatial
re-spacing (`BotManager.shouldRetreatFromNearbyTarget`, `~:1819`) and the reactive 40%-HP potion path
(`BotPotionManager:~436`). And `BotGrindAdvisor` target scoring has **no danger term**.

## SSOTs to reuse (rule #6 — do NOT write a parallel damage calc)
- **Touch damage after armor:** `BotDefenseDataProvider.rollPhysicalTouchDamage(bot, mob)`
  (`server/bots/BotDefenseDataProvider.java:~180`). This is the authoritative, player-matching formula
  (uses `bot.getTotalWdef()`, job/level standard PDD, mob `getPADamage()`). Use it as-is.
- Hit/miss: `CombatFormulaProvider.doesMobHit(bot, mob)` / `calculateMobHitChance` (only hits matter).
- Bot fragility inputs: `bot.getMaxHp()`, `bot.getHp()`, `BotPotionManager.countPotions(bot)` (`int[]{hp,mp}`),
  `bot.getMeso()`.

## Implement
1. **`BotDangerAssessment`** (new small class, `server/bots`):
   - `int estimateMaxTouchDamage(Character bot, Monster mob)` → wraps `rollPhysicalTouchDamage` (cache
     per (botId,mobId) if hot; invalidate on level-up/equip change — keep simple first).
   - `boolean isTouchDangerous(Character bot, Monster mob, int hitsToKill)` → `maxHp / dmg <= hitsToKill`.
     Start `hitsToKill = 3` (tunable; consider a `BotCombatManager.cfg` knob so it's `!botcfg`-adjustable,
     and let warriors tolerate fewer hits-to-kill than squishies via job, optional v2).
2. **Touch-damage-aware target selection:** in the target picker (`BotGrindAdvisor` candidate scoring
   and/or `BotCombatManager`'s per-tick target choice), **de-prioritize or skip** mobs that are
   touch-dangerous when the bot is fragile (low HP pool / no pots). Prefer a soft penalty in scoring over
   a hard skip so the bot still fights if nothing safer exists. Reuse existing target-iteration; add the
   danger term — don't fork the selector.
3. **Proactive retreat trigger:** before engaging / continuing on a dangerous mob, if HP is above the
   reactive heal threshold but the mob is `isTouchDangerous`, disengage (break target / back off) using
   the **existing** retreat/re-spacing machinery (`shouldRetreatFromNearbyTarget` / the cluster-aware
   retreat in `BotAttackExecutionProvider`) rather than new movement code. Distinct from the reactive
   40%-HP potion path — fires earlier and is danger-driven, not damage-driven.

## Composition (avoid overlap)
| Layer | Trigger | Action | Already done? |
|---|---|---|---|
| Reactive heal | HP < 40% (and Recovery for maxHP<500) | pot / Recovery | yes |
| **Proactive retreat (THIS)** | HP healthy but mob touch-dangerous | skip/disengage target | **no — build** |
| **Touch-aware targeting (THIS)** | fragile + dangerous mob present | de-prioritize in selection | **no — build** |
| Travel risk (route) | low level / dangerous region | avoid routing there | yes (shipped) |

Keep all three non-redundant (different phases: route → target select → engage → heal).

## Risks / notes
- Don't make a bot freeze forever because everything nearby is "dangerous" — the penalty should be soft,
  and the travel layer should already have steered a fragile bot to a safe map. If a bot is somewhere all
  mobs are lethal, the death-loop breaker / town retreat is the backstop.
- Humanlike: add small jitter to retreat decisions; don't flip-flop (hysteresis like the existing retreat
  hold window `retreatHoldUntilMs`).

## Verify
- Compile. Unit-test `BotDangerAssessment.isTouchDangerous` with stubbed mob/bot stats (WZ-free): a
  high-PAD mob vs a low-HP/low-WDEF bot ⇒ dangerous; geared/high-HP ⇒ safe. Test the selection penalty
  ordering. Don't run nav/graph suites.
