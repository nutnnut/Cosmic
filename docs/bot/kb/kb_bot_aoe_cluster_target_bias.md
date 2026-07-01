---
name: kb-bot-aoe-cluster-target-bias
description: AoE-aware bias in BotCombatManager target selection — closes the gap where bot picks a lone close mob over a slightly-farther AoE-clearable cluster
metadata: 
  node_type: memory
  type: project
  originSessionId: 67fe2526-fb86-46db-9238-fdcc0ea15f91
---

Added 2026-05-19 (`ab019dc5c`). The bot's per-target DPS scorer (`selectBestAttackPlan`) already picks an AoE plan over basic for the same target, but the **target selection** step (`findGrindTarget` / `findFollowAttackTarget` / `scoreTargetRegions`) was distance-only — it didn't know AoE skills exist. A close lone mob would beat a 3-mob cluster slightly farther away even when an AoE swing would clear the cluster for more total DPS and less contact damage.

## Mechanism

`BotCombatManager.aoeClusterBonus(entry, target, candidates)` returns a non-negative score discount, subtracted from `grindTargetScore` in every caller (`scoreLocalTargets`, `scoreTargetRegions`, the `findFollowAttackTarget` inner loop). Returns 0 when:
- `entry == null`
- `entry.aoeSkillId == 0` (no AoE skill learned)
- `entry.aoeSkillMobs <= 1` (skill hits only 1 mob)
- target or candidates null/empty

Otherwise counts other live candidates within `AOE_CLUSTER_RADIUS_PX = 150` of the target, capped at `entry.aoeSkillMobs - 1`. Bonus = `neighbors * AOE_CLUSTER_BONUS_PER_MOB (200)`.

## Constants & tuning

- `AOE_CLUSTER_RADIUS_PX = 150` — middle ground between PK arc width (130) and bbox-skill effective reach. Conservative enough to not over-claim hits.
- `AOE_CLUSTER_BONUS_PER_MOB = 200` — sits in the same range as `grindTargetScore`'s `dx` term, so a full 5-mob cluster (cap = 6 - 1) claws back ~1000 px of distance penalty. Won't override the much larger `!nearSameLevel (+600)` or `!sameFoothold (+1200)` penalties, so the bot still avoids vertical commits / cross-foothold detours just for a cluster.
- Cost: O(M²) on the existing candidate list, M typically <10 mobs in `GRIND_SEEK_RANGE`. Negligible vs A*/physics.

## Why a heuristic instead of per-anchor plan enumeration

Alternative considered: in `planAttack`, enumerate N nearby anchor mobs, call `planSkillAttack` for each, pick the best plan across anchors. Cost ~3-5× current `planSkillAttack` calls per tick → 10-20% total tick cost increase. The heuristic gets the same outcome for ~1% cost as long as the cluster-radius approximation matches the actual AoE hitbox shape — true for PK (arc 150), Arrow Bomb (bbox ~120), and most v83 AoE skills.

If a future skill has reach significantly larger than 150 (or strike-point anchored at the target, where the AoE bubble is centered elsewhere), revisit and either widen `AOE_CLUSTER_RADIUS_PX` per skill or fall back to per-anchor enumeration.

## Test coverage

`BotCombatManagerTest.shouldPreferAoeClusterAnchorOverLoneCloseMobWhenBotHasAoeSkill`:
- Lone mob at dx=60, 3-mob cluster anchor at dx=160 + neighbors at dx=190/220.
- Without `aoeSkillId`: lone mob wins on distance.
- With `aoeSkillId = Hunter.POWER_KNOCKBACK`, `aoeSkillMobs = 6`: cluster anchor wins (bonus = 2 × 200 = 400 > distance delta 100).

## When this might mislead

The bonus is per-mob distance density — it doesn't know about footholds inside the cluster radius or whether mobs are actually reachable. If the cluster is on a foothold the bot can't reach, the bot will still aim toward it. The `!sameFoothold` and graph-path-cost penalties usually offset this, but worth keeping in mind when debugging.

See also: [[kb-bot-attack-planning-flow]], [[kb-power-knockback-packet-structure]].
