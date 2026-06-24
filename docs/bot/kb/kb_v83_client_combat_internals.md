---
name: v83 Client Combat Internals (from Angel.idb)
description: Verified facts about how the actual v83 client handles attack hitboxes, ranges, and mob bounds
type: project
originSessionId: 5d91e9bb-b7df-4dff-8d09-3d47fc2cda61
---
Facts verified by disassembly of `D:\GameServers\Maplestory\Cosmic\tmp\`Angel.idb`
on 2026-04-24. Use the toolkit at `D:\ReverseEngineer\` (see
`reference_reverse_engineering_toolkit.md`) to reproduce / extend.

## `CMob::GetHitPoint` @ `0x00664603`

Signature (effective): `POINT GetHitPoint(Rect attackBox)` returned via
out-param at `[ebp+8]`.

Behavior:
1. Load mob's body rect into locals (`sub_664559` populates from current
   animation frame — likely `m_ptLT/m_ptRB` of the mob object).
2. Clamp `attackBox` to that body rect (bounded inc/dec on each side).
3. Call `IntersectRect` (`[0xbf04a8]`).
4. Return midpoint of intersection, or `{0,0}` if no overlap.

**Implication:** v83 client uses rect-vs-rect attack vs mob-body. The bot's
`doesHitBoxIntersectMonster(hitBox, mobBounds)` matches this model.

14 verified callers:
- `CSummoned::TryDoingAttackManual` ×2
- `CUserLocal::Update` ×2
- `CUserLocal::TryDoingMeleeAttack`
- `CUserLocal::TryDoingShootAttack` ×2
- `CUserLocal::TryDoingMagicAttack` ×3
- `CUserLocal::SetDamaged` (mob → player **touch** path)
- `CVecCtrl::IsFreeFalling`
- `CUserRemote::OnMeleeAttack`
- `CUserRemote::OnBodyAttack`

## Mob body rect source

v83 Mob.wz has **no dedicated hitbox node**. Per-frame `lt/rb` IS the
hitbox. The client uses the *current* animation frame; bot caches frame 0
of `stand/move/fly` (close enough — typical drift 1–10 px).

Attack/hit frames may embed weapon swing extents — exclude them from any
caching. Example: mob 8500003's `attack1` frame has `lt=(-65,-100)
rb=(65,0)` (130×100 arc), not its body.

## Ranged attack range

`CUserLocal::TryDoingShootAttack` @ `0x009537d5` contains **zero
hardcoded range constants** (no 400/50/-5 anywhere in body). The 400×100
rectangle is built from `CharStats` upstream. All 4 ranged weapon types
(BOW/CROSSBOW/CLAW/GUN — codes 0x2d/0x2e/0x2f/0x31) use
`projectilerange=400` in the client. **No per-weapon range reduction.**

Passive range bonuses come from WZ skill `range`:
- Eye of Amazon (3000002): per-level [15..120], max +120
- Keen Eyes (4000001, 4001001): per-level [25..200], max +200
- WindArcher EoA / NightWalker Keen Eyes (1300/1400 books) match.

## Melee hitbox

`CActionMan::GetMeleeAttackRange` @ `0x00414440` reads weapon afterimage
`lt/rb` per action — fully WZ-driven. There is NO hardcoded melee range.
`GetWeaponAfterImage` @ `0x0040ac78` sources the data.

## What is NOT in the client (anti-misconception)

- No circular distanceSq check for ranged attacks (HeavenMS-style 600000
  threshold is a **server anti-hack**, not the client gating model).
- No "attack range" constant — every range is rect-based and either WZ
  (skill/weapon) or stat-derived (`CharStats::projectilerange = 400`).
