---
name: kb-power-knockback-packet-structure
description: "Power Knockback (3101003 / 3201003) is a melee skill on bow classes — packet opcodes, WZ shape, and notes for the upcoming bot knockback (MOVE_LIFE) implementation"
metadata: 
  node_type: memory
  type: project
  originSessionId: 67fe2526-fb86-46db-9238-fdcc0ea15f91
---

Captured 2026-05-18 while diagnosing bot bowman "long-range piercing shots". The bot was routing Power Knockback as RANGED (400 px projectile box) when on the v83 client it is a close-range melee swing. Fix committed `e0279c55d`.

## Packet routes — verified from `monitored-packets-power-knockback.log`

Player Bowgurl using Crossbowman.POWER_KNOCKBACK (3201003):
- **Incoming attack:** opcode `0x2C` = `CP_UserMeleeAttack` (`CLOSE_RANGE_ATTACK`). Same opcode as basic melee.
- **Server broadcast:** opcode `0xBA` = `CLOSE_RANGE_ATTACK` (PacketCreator `closeRangeAttack`). Format is identical to the regular close-range body in `PacketCreator.addAttackBody` — `0x5B` sentinel, skillLevel, 4-byte skillId, display/direction/stance/speed, `0x0A`, projectile (zero), then per-mob `(oid, 0x00, damage…)`. NO trailing `writeInt(0)` (that's only the ranged variant).
- The `numAttackedAndDamage` byte packs high nibble = mob count, low nibble = damages per mob. Power Knockback has 1 damage per mob, hits up to `mobCount` mobs — e.g. `0x31` = 3 mobs × 1 hit.

`Hunter.POWER_KNOCKBACK = 3101003`, `Crossbowman.POWER_KNOCKBACK = 3201003` (constants/skills).

## WZ shape (`Skill.wz/310.img.xml` skill 3101003)

- `weapon = 45` (BOW restriction)
- `mobCount`: 2 at lvl 1–5, 3 at lvl 6+
- `damage`: 105 → 160 (per level, ~5%/level)
- `range`: **130** — this is the *melee horizontal reach*, NOT a projectile range. Used as the close-range box width once we route it as CLOSE.
- `prop`: 13 → 55 (% knockback chance — bot ignores this for now)
- `mpCon`: 8
- **No `lt` / `rb` node** — no skill bbox; falls into `fallbackCloseRangeSkillHitBox`.

## Knockback side effect — MOVE_LIFE (0xBC) — DEFERRED

User explicitly deferred knockback implementation. Key notes for the future pass:
- Knockback travels via **opcode 0xBC = `MOVE_LIFE`**, which is the *same opcode* used for ALL mob movement updates. Per user (2026-05-18): "MOVE_LIFE opcode for knockback is shared with general mob movement (not implemented for bot, all handed off to real player, no plan to make bot own mobs movement) and so monitored packets are noisy".
- Bots therefore do not own mob movement state — they don't broadcast MOVE_LIFE for natural movement. When we implement knockback, it must be specifically a bot-originated push (probably an explicit handler call after the attack lands), not by hooking general mob movement.
- The `prop` value gates per-hit RNG (% chance per mob in the swing).
- Real-player Power Knockback bursts in the log show: attack 0xBA → multiple 0xBC packets from clients within ~50ms, each per knocked mob. So the knockback packet is multi-target and time-correlated to the attack.

## Bot-side integration points (current code, 2026-05-18)

- `BotAttackExecutionProvider.FORCED_CLOSE_RANGE_SKILL_IDS` — central set; add new bow-melee skills here so route + reach are consistent.
- `BotAttackExecutionProvider.determineSkillRoute` — short-circuits to CLOSE before any weapon-route logic.
- `BotCombatManager.fallbackCloseRangeSkillHitBox` — caller (`fallbackSkillHitBox`) already gates on `route == CLOSE`; removed the redundant `determineBasicAttackRoute != CLOSE` precondition so bow bots can pick up the melee rect.
- Reach gate: `Math.max(cfg.ATTACK_RANGE_X (80), effect.getRange() (130))` = 130 horizontal. Vertical uses `ATTACK_RANGE_Y` / `ATTACK_DOWN_MAX` like other melee.
- Ammo gate (`countAmmo`) only blocks RANGED route, so no false negative for CLOSE Power Knockback.

## When implementing knockback

1. Hook the attack-lands path (close-range damage handler or bot-side post-attack) to roll `prop` per mob and push them.
2. Send MOVE_LIFE updates ONLY for the mobs actually knocked, with the new mob position derived from skill direction (facing) + a small dx. Do not start broadcasting MOVE_LIFE for normal mob motion — only push the displacement caused by the knockback.
3. Re-test with `monitored-packets-power-knockback.log` as the reference player capture — replicate the 0xBA → 0xBC sequence per landed mob.

## Direction byte trap — fixed 2026-05-19 (`c3615a966`)

After routing PK as CLOSE via `e0279c55d`, bots started **crashing watching players** mid-grind. Root cause: the `direction` byte in the 0xBA broadcast.

- Real player PK 0xBA broadcast carries a **swing body action id** (e.g. 0x09) — the v83 client picks a melee swing animation for PK even though the equipped weapon is a bow.
- PK has **no `action` field in WZ** — `Skill.resolveAnimationAction(...)` returns null. Bot then fell through to `sampleWeaponAttackAction(bot, BOW)` which uses the **ranged** basic-attack spec → sampled "shoot1"/"shoot2" body action id.
- Result: bot broadcast 0xBA (close-range packet) containing skillId 3101003 **and** a shoot animation id. v83 clients can't reconcile a melee-skill broadcast with a ranged shoot animation → crash on observing client.

Fix: in `BotAttackExecutionProvider.resolveSkillAttackAction`, when `skill.getId()` is in `FORCED_CLOSE_RANGE_SKILL_IDS`, sample from the degenerate close-range spec (`provider.getBasicAttackSpec(weaponType, true)`) — same swing animations a bow uses when point-blank. Produces a swing body action id matching the real-player packet.

**Generalization:** any future bow/crossbow/claw skill that broadcasts as melee on the v83 client must (a) be in `FORCED_CLOSE_RANGE_SKILL_IDS` and (b) have its action sourced from the degenerate spec. The two changes are tightly coupled — the `direction` byte must match the broadcast opcode, otherwise watching clients crash.

See also: [[kb-bot-attack-planning-flow]], [[kb-v83-client-combat-internals]], [[kb-bot-aoe-cluster-target-bias]].

## Damage formula (commit e3888f7f6)
PKB + ALL degenerate close attacks with ranged weapon (bow/xbow swing, claw punch, gun bash) use
the weak mismatched-weapon formula via CombatFormulaProvider.resolveDegenerateDamageProfile:
bow/xbow (DEX*3.4+STR)*WAtk/150, claw (LUK+STR+DEX)*WAtk/150, gun (DEX+STR)*WAtk/200; mastery
fixed 10% (min factor 0.09 = 0.1*0.9, ayumilove "Mastery is 0.1 at all levels"); NO crit
(DamageProfile.noCrit honored in roll + estimate paths); PKB damage% composes on top. Dispatch:
BotCombatManager.resolveAttackDamageProfile (route CLOSE + isDegenerateCapableRangedWeapon).
Source: docs/formulas/dmg_formula_ayumilove.txt lines 124-131. ALSO verified there (line 97):
magic MAX=((M^2/1000+M)/30+INT/200)*SpellAtk -> +1 INT ~ 1.07-1.09x +1 gear MATK at M=300-600,
so MATK_WEIGHT=1.0 stat-equivalent is formula-accurate.
PRE-EXISTING test-env failures (NOT from this change, fail on clean HEAD): BotCombatManagerTest
x6 "missing real WZ skill"/mob-touch-sweep + CombatFormulaProviderTest x3 skill-damage scaling
(LuckySeven 675/450, DragonRoar 765/319, ScalePhysical 1300/500) - real-WZ skill data not
loading in that JVM combination; investigate separately.
