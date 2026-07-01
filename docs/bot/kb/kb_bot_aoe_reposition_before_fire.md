---
name: kb-bot-aoe-reposition-before-fire
description: AoE positioning — bot defers a single-target shot and steps into the cluster centroid when the AoE there beats fire-now DPS by a factor (bounded chase)
metadata: 
  node_type: memory
  type: project
  originSessionId: 2d6370b7-5781-43b7-bb6e-1cfbcfd2d86c
---

Added 2026-06-01 (branch `experimental`). Companion to [[kb-bot-aoe-cluster-target-bias]].
Target *selection* already biases toward clusters; this fixes the *firing-timing* gap: the bot
used to fire its single-target skill the instant one mob entered range, because `planAttack`
scores every candidate at the bot's **current position** and the AoE box at a cluster *edge*
catches only 1 mob (ties the denominator, loses on per-hit damage). It never stepped into the
cluster centroid where the AoE would catch 3+.

## Mechanism

`BotCombatManager.aoeRepositionTarget(entry, bot, primary, fireNowBest)` returns a sweet-spot
`Point` to walk to, or null = fire now. Gates (cheap → expensive, short-circuit):
1. `entry.aoeSkillId != 0 && aoeSkillMobs > 1`.
2. `fireNowBest` is single-target with room: `skillId != aoeSkillId && targets.size() < aoeSkillMobs`.
3. Geometry (no scoring yet): centroid X of live mobs within `AOE_CLUSTER_RADIUS_PX (150)` of the
   primary; clamp |centroid−bot| to `AOE_REPOSITION_MAX_DISTANCE_X (150)`; bail if within
   `AOE_REPOSITION_ARRIVAL_X (20)`.
4. Rebuild the AoE candidate via `planSkillAttack(aoeSkillId)`, then **shift its hitBox so the box
   CENTER lands on the cluster centroid** (`shift = centroidX − hitBox.getCenterX()`, clamped to
   `AOE_REPOSITION_MAX_DISTANCE_X`; bail if `|shift| <= ARRIVAL`). The bot walks to `botX + shift`.
   ⚠️ Fixed 2026-06 (`7174881d3`): the original code shifted by `centroidX − botX` (anchor onto
   centroid), but skill boxes extend *forward* from the bot and don't straddle the anchor, so that
   pushed the box past the cluster → `sweetTargets` was almost always 0 and reposition never fired.
   `collectTargetsInHitBox` at the shifted box; bail if it wouldn't catch more mobs than firing now.
5. Only now pay for scoring: `scoreAttackPlan` both plans. Preserve kill priority (bail if
   fire-now `minimumKillsFullHpTargets`). Reposition iff
   `sweetDps >= fireNowDps * AOE_REPOSITION_DPS_FACTOR (1.5)`.

`scoreAttackPlan` is position-independent (target HP + damage profile), so scoring a
translated-hitbox plan copy is valid.

## Related: in-range retarget toward a bigger cluster (`9b390024d`, 2026-06)

Reposition only handles the *current* target's cluster. The bot was also fully committed to any
in-range mob — `BotManager.shouldSearchForGrindTarget` only re-searched while *chasing* (out of
range), so an AoE bot ignored a larger cluster elsewhere. Now `shouldSearchForGrindTarget` also
returns true in-range when `BotCombatManager.isAoeBotSingleTargeting(entry, plan)` (has multi-mob
AoE but current plan is single-target). The switch is gated by `shouldSwitchToSearchedTarget`
hysteresis: adopt the searched mob only if `aoeClusterSize(searched) > aoeClusterSize(current)`
(both capped at `aoeSkillMobs`), else stay — prevents flip-flop between near-equal targets.
Non-AoE bots keep the old stay-committed behavior. Note target selection sorts by graph path cost
FIRST then `localScore` (incl. `aoeClusterBonus`), so a far-side cluster still must beat path cost.

## Commitment + wiring

`BotManager.resolveAoeReposition` manages a bounded-chase commitment on `BotEntry`
(`aoeRepositionAnchor`, `aoeRepositionDeadlineMs`): scores **once** when the commitment starts;
while committed it just returns the stored anchor (zero scoring) until arrival,
`AOE_REPOSITION_MAX_MS (800)` deadline, or target death/clear. Wired into **both** grind fire
sites in `tickCore` (the cached-movement branch and the AI-dispatch branch): when in range,
`aoeRepositionPos != null` suppresses the fire + the stand-still idle + the convenient-loot
detour, and routes `targetPos` through `selectGrindNavigationTarget` to the anchor. Suppressed
during ranged-spacing / cross-region retreats (spacing wins).

## Perf (kept cheap on purpose)

Heavy path runs only on a tick that is in range + single-target-plan + has a real cluster. The
geometry gates (steps 1–4) bail before any `scoreAttackPlan`, so the common "in range, lone mob"
tick is O(M) arithmetic. When it does score: 1 extra `planSkillAttack` + 2 `scoreAttackPlan`
(~2 `resolveDamageProfile`). vs the rejected full per-anchor enumeration (~3–5× planSkillAttack).
Covered by `BotPerformanceMonitor "combat-plan"`.

## Tests

`BotCombatManagerTest`: `shouldRepositionToClusterCentroidWhenAoeDpsBeatsSingleTarget` (positive,
centroid=200), `shouldNotRepositionWhenNoAoeSkill`, `shouldNotRepositionWhenFireNowPlanIsAlreadyAoe`,
`shouldNotRepositionForLoneMob`. Note `skillWithAttackBox` mocks `calculateBoundingBox` to a
**fixed absolute rect** (ignores anchor), so author the box bot-relative — the code's real
`Rectangle.translate(dx,0)` then produces the sweet-spot box. All 418 Bot*Test green.
