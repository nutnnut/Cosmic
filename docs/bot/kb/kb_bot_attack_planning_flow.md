---
name: Bot Attack Planning Flow
description: planAttack -> AoE/single-target/basic dispatch, hitbox sources, strike-point-anchored gate
type: project
originSessionId: 5d91e9bb-b7df-4dff-8d09-3d47fc2cda61
---
`BotCombatManager.planAttack(entry, bot, target)` is the entry point for
every bot attack decision. It tries in order: `planAoeAttack` →
`planSingleTargetSkill` → basic attack fallback. The result (`AttackPlan`)
holds the hitBox used by `isTargetInAttackRange` to gate execution.

## Hitbox sources by route

- **CLOSE basic**: `cfg.ATTACK_RANGE_X/Y/DOWN_MAX` rect at bot origin.
- **RANGED basic / fallback**: `clientProjectileHitBox(bot, facingLeft, scale)`
  — 400 px × 100 px (±50 vertical) anchored at bot, scaled by `effect.range`
  if any. Base 400 + passive bonuses (Eye of Amazon +120 max, Keen Eyes
  +200 max).
- **Skill with WZ `lt/rb`**: `effect.calculateBoundingBox(anchor, facingLeft)`.
  Anchor is **bot position by default**, **target position** for skills in
  `STRIKE_POINT_ANCHORED_AOE_SKILL_IDS` (currently `Hunter.ARROW_BOMB` =
  3101005, the Ranger version).

## Strike-point-anchored skills — the trap

Anchoring at the target means the skill's hitBox is centered on the target,
so `doesHitBoxIntersectMonster(hitBox, target)` is **trivially true**. This
let bots fire Arrow Bomb at mobs hundreds of pixels out of weapon range
(observed: ~400 px vertically above bot, far outside the projectile rect).

Fix (committed `f2fa1082e`, 2026-04-24): `isPrimaryReachableByBasicWeapon`
gates strike-point skills on the bot's basic-weapon rect:
- RANGED → `clientProjectileHitBox`
- CLOSE → `ATTACK_RANGE_X/Y/DOWN_MAX` rect
- MAGIC → ungated for now (untested; user said skip)

Applied in both `planSingleTargetSkill` and `planAoeAttack` immediately
after `calculateSkillHitBox` returns non-null.

**Why:** Strike-point hitBox proves the AoE *radius around the impact*,
not whether the projectile/blade can *reach* the impact point.
**How to apply:** Any time you see a skill flagged strike-point-anchored,
remember the AoE rect ≠ reach. The reach gate must use the bot's basic
weapon rectangle separately.

## Close-range reach = per-weapon, per-action afterimage box (faithful-random)

Since 2026-05-31, the close-range hitbox is **not** the flat `cfg.ATTACK_RANGE_X=80`
rect. It is the equipped weapon's swing box from `Character.wz/Afterimage/<weapon>.img.xml`
(`BotAttackDataProvider.NormalAttackProfile`), which is **per-weapon** (polearm/spear
~150 px, sword ~120, dagger/knuckle ~64) and **per-action** (a spear stab is long+low,
its overhead swing tall+short — `rightFacingBoundsByAction`, `calculateActionBoundingBox`).

The bot rolls one action per swing (random `sampleAttackAction`) and gates the hit on
**that action's** box, so it hits/misses like a real player and re-rolls next tick (user
chose "faithful random" over a smart action-picker). No stall: when out of reach the grind
tick navigates toward the monster (`selectGrindNavigationTarget`).

Wiring: `buildBasicAttackDataFromProfile` (basic) and `fallbackCloseRangeSkillHitBox`
(close skill without its own `lt/rb`) both use the action box; the skill `action` is
resolved **once** up front in `planSkillAttack` and threaded through `calculateSkillHitBox`.
**Skills ignore WZ `range`** here (client-unused for melee swings, e.g. Slash Blast's
`range=150` was spurious) — *except* weapons with no afterimage box (bows casting Power
Knockback as a melee hit) fall back to `max(ATTACK_RANGE_X, effect.getRange())`, keeping PK's
130 px. Degenerate close-range (out-of-ammo bow/claw meleeing) keeps the flat rect.
This fixed the "Slash Blast on a lone mob over Power Strike" bug: equal reach now → the
2×-damage single-target skill wins on DPS. Skills WITH `lt/rb` still use their own box.

## Mob bounds source

`doesHitBoxIntersectMonster` calls `BotMobHitboxProvider.getMobBounds(mob)`
which loads frame `0` of the first available group in
`{stand, move, fly}` from `Mob.wz/<id>.img.xml` and translates `lt/rb`
to world coords. v83 has no separate hitbox node — sprite bbox IS the
hitbox. Hit/attack frames are excluded because they can embed weapon
swing extents (mob 8500003 attack1 = 130×100 weapon arc, not body).

## Selection vs reach — two different gates

- `findGrindTarget(800px)` / `findFollowAttackTarget(400px)` are
  **selection** filters (radius around bot, not direction-aware).
- `isTargetInAttackRange(plan, bot, target)` is the **reach** gate using
  the plan's hitBox.

Selection over-picks vertical/distant mobs intentionally; the reach gate is
expected to drop them. Strike-point bug bypassed the reach gate — now fixed.
