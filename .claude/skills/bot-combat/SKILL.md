---
name: bot-combat
description: Use when implementing, debugging, or analyzing bot combat behavior, attack skills, attack packets, hitboxes, ammo/buff gates, or routing decisions in server.bots.*. Triggers include "bot uses wrong attack", "bot whiffs/over-attacks", "implement skill X for bot", "bot attack packet looks wrong", "verify bot packets vs real player", or any question about CLOSE_RANGE_ATTACK / RANGED_ATTACK / MAGIC_ATTACK / MOVE_LIFE packet shape in this codebase.
---

# Bot combat & attack skills

Project-scope cookbook for the bot subsystem at `server.bots.*`. Distilled from packet captures, code reading, and the implementation work for Power Knockback, Iron Arrow, Avenger, and Shadow Partner.

## When to use this skill

Triggered by any task that involves the bot's attack pipeline — picking a skill, building a hitbox, generating damage rolls, or emitting the attack packet. Includes:
- "bot keeps firing X past its range" / "bot whiffs" → start with the **Hitbox model** and **Selection vs reach gates** sections.
- "implement skill X for bot" → start with **Adding support for a new attack skill** below.
- "bot packet looks wrong / doesn't match player" → start with **Packet shapes**, then capture both with `/monitor`.
- "Shadow Partner / Soul Arrow / ammo issue" → **Ammo & buff gates** section.

## Operating principles (worth knowing before editing)

1. **The v83 client is the source of truth.** Whenever we don't already know how the client behaves, reverse-engineer it (`D:\ReverseEngineer\` IDB toolkit per `reference_reverse_engineering_toolkit`) or **capture packets from a real player** and binary-search the behavior.
2. **Reuse player code paths.** Bot damage/effect/ammo logic should call the same handlers a real player would (`RangedAttackHandler.applyRangedAttackEffects`, `CombatFormulaProvider.makeTarget`, `StatEffect.canPaySkillCost`). When something "doesn't work for bots," nine times out of ten it's a small adapter at the bot-only edge — not new physics inside the shared code. Don't fork shared paths.
3. **Client-side formulas belong in the bot.** Damage rolls, stance bytes, projectile cosmetic IDs, and packet field encoding are client responsibilities, so we keep them under `server.bots.*` (mainly `BotAttackExecutionProvider` and `BotCombatManager`), never in `StatEffect` or the shared damage handlers.
4. **Surgical edits.** Don't refactor `AbstractDealDamageHandler` or `PacketCreator.addAttackBody` unless the user is explicitly asking for it. Bot-side adapters are the right level.

## File map

### Bot side (this is where edits usually land)

| File | What it does |
|------|---|
| `server/bots/BotCombatManager.java` | Central planning + execution. Holds `AttackPlan`, `AttackRoute`, `planAttack` → `planSkillAttack` / `planBasicAttack`, `calculateSkillHitBox`, `clientProjectileHitBox`, `fallbackCloseRangeSkillHitBox`, `effectiveHitCount`, ammo gate, the skill-id selection caches `attackSkillId` / `aoeSkillId` / `attackSkillIds`. |
| `server/bots/BotAttackExecutionProvider.java` | Per-attack data: basic-attack data, weapon route resolution, `determineSkillRoute`, force-close skill IDs, the close-range packet field mimic, and `applyAttackRoute` (the bridge into the shared damage handlers). |
| `server/bots/BotManager.java` | Tick loop. `tickCore` is the main attack site (`isTargetInAttackRange`, `attackMonster`, the local-opportunity attack path inside `attemptLocalOpportunityAttack`). |
| `server/bots/build/*Builds.java` | Per-job SP plans. New attack skills must already appear here at non-zero level or the bot won't learn them. |
| `server/bots/combat/BotAttackDataProvider.java` | Resolves animation/action names from item WZ data per weapon. |
| `server/bots/combat/BotAttackTiming.java` | Hit delay / cooldown math from skill action frames. |

### Shared player-side code (read but rarely modify)

| File | What it does |
|------|---|
| `net/server/channel/handlers/AbstractDealDamageHandler.java` | Parses incoming attack packets, validates damage, fires effects. Server-side Shadow Partner halving and autoban headroom (`maxattack * 2`) live here. |
| `net/server/channel/handlers/RangedAttackHandler.java` | Ranged-attack-specific effects: ammo consume, Shadow Partner doubling of `bulletConsume`, Soul Arrow / Shadow Claw skip. |
| `net/server/channel/handlers/CloseRangeDamageHandler.java`, `MagicDamageHandler.java` | Close-range and magic equivalents. |
| `tools/PacketCreator.java` `addAttackBody`, `closeRangeAttack`, `rangedAttack`, `magicAttack` | Authoritative packet body layout. Single source of truth for byte order on broadcasts (the `0xBA` / `0xBB` / `0xBC` opcodes). |
| `server/StatEffect.java` | WZ skill effect: `mobCount`, `attackCount`, `bulletCount`, `bulletConsume`, `range`, `lt`/`rb` bbox, MP/HP/skill-cost. `calculateBoundingBox(anchor, facingLeft)` and `canPaySkillCost`. |
| `client/BuffStat.java` | All buff stat bits. `SHADOWPARTNER`, `SOULARROW`, `SHADOW_CLAW`, `STANCE`, `ELEMENTAL_RESET`, etc. |
| `constants/skills/*.java` | Skill IDs by job. Always resolve IDs through these constants — never inline a numeric literal. |
| `wz/Skill.wz/*.img.xml` | Raw WZ skill data. Use to verify `lt`/`rb`, `mobCount`, `range`, `bulletConsume`, etc. |

### Test files (run these on any combat change)

```
src/test/java/server/bots/BotCombatManagerTest.java       (~58 tests, main coverage)
src/test/java/server/bots/BotAttackDataProviderTest.java
src/test/java/server/bots/BotManagerTest.java
```

## Attack packet opcodes

Captured from packet logs (`logs/monitored-packets-*.log`) and verified against `tools/PacketCreator` / `RecvOpcode` / `SendOpcode`.

| Direction | Opcode | Name | Meaning |
|-----------|--------|------|---------|
| In (client→server) | `0x2C` (44) | `CP_UserMeleeAttack` | Player swung a melee attack. Includes Power Knockback even though caster has a bow. |
| In | `0x2D` (45) | `CP_UserShootAttack` | Player fired a ranged attack (Double Shot, Iron Arrow, Avenger, basic shot). |
| In | `0x2E` (46) | `CP_UserMagicAttack` | Player cast a magic attack. |
| Out (server→others) | `0xBA` (186) | `CLOSE_RANGE_ATTACK` broadcast | Render the swing on other clients. |
| Out | `0xBB` (187) | `RANGED_ATTACK` broadcast | Render the shot. |
| Out | `0xBC` (188) | `MAGIC_ATTACK` broadcast | Render the magic attack. |
| Both | `0xBC` (188) | `MOVE_LIFE` | **Same opcode as MAGIC_ATTACK broadcast, different direction.** Mob movement updates, including knockback. Bots do NOT broadcast mob movement; that ownership stays with real players. |

`MOVE_LIFE` is shared between knockback and natural mob movement; capture logs near knockback will be noisy. We do not have a knockback implementation yet — when adding one, only emit MOVE_LIFE for mobs that were actually displaced by the swing.

## Packet body shape (the `addAttackBody` layout)

```
int  characterId
byte (numAttacked << 4) | numDamage      // count byte: high = mobs hit, low = dmg lines per mob
byte 0x5B                                  // sentinel
byte skillLevel                            // 0 for basic attack
int  skillId                               // only present when skillLevel > 0
byte display
byte direction
byte stance                                // 0x80 = facing left
byte speed
byte 0x0A                                  // sentinel
int  projectile                            // 0 for melee, ammo item id for ranged
for each mob:
    int   oid
    byte  0x00
    int   damage1
    int   damage2  // present only when numDamage > 1 (Shadow Partner, Double Shot, etc.)
    ...
int  0  // trailing zeros (rangedAttack appends an extra writeInt(0))
```

**Reading hex by hand:** every int is little-endian 4 bytes. Skill 3001005 (Double Shot) is `0x002DCAAD` → bytes `AD CA 2D 00`. Cross-check skill IDs by computing `id = b0 + b1*0x100 + b2*0x10000 + b3*0x1000000`.

**Count byte:** `0x12` means 1 mob × 2 dmg (Double Shot). `0x61` means 6 mobs × 1 dmg (Iron Arrow). `0x52` means 5 mobs × 2 dmg (Avenger + Shadow Partner). Confusion-prone — always remind yourself which nibble is which.

## Bot attack planning flow

```
tickCore (BotManager)
 ├─> grindTarget = selectTarget(...)
 ├─> attackPlan  = BotCombatManager.planAttack(entry, bot, target)
 │    ├─> planAoeAttack          (uses entry.aoeSkillId)
 │    ├─> planSingleTargetSkill  (uses entry.attackSkillId)
 │    └─> planBasicAttack        (fallback; can return null since 2026-05)
 ├─> if (BotCombatManager.isTargetInAttackRange(attackPlan, bot, target)
 │       && canUseAttackPlanNow(entry, weaponType, attackPlan))
 │     → BotCombatManager.attackMonster(entry, bot, attackPlan)
 │          ├─> CombatFormulaProvider.resolveDamageProfile()
 │          ├─> CombatFormulaProvider.makeTarget() per mob (rolls damage lines)
 │          └─> BotAttackExecutionProvider.applyAttackRoute(route, attack, bot)
 │                ├─ CLOSE  → CloseRangeDamageHandler.applyCloseRangeEffects
 │                ├─ RANGED → RangedAttackHandler.applyRangedAttackEffects
 │                └─ MAGIC  → MagicDamageHandler.applyMagicAttackEffects
 └─> else: jump / idle / retreat / walk
```

**Key invariant since 2026-05:** `planAttack` can return `null`. Every dereference of `AttackPlan` outside `attackMonster` must null-guard or it'll NPE the tick loop.

## `AttackRoute` selection

`BotAttackExecutionProvider.determineSkillRoute(bot, skillId)` in priority order:
1. `FORCED_CLOSE_RANGE_SKILL_IDS` (`Hunter.POWER_KNOCKBACK`, `Crossbowman.POWER_KNOCKBACK`) → CLOSE. These are bow-class skills the v83 client casts as melee. Add new bow-class melee skills here.
2. `isRangedSkill(skillId)` (a small allowlist for things like `Aran.COMBO_SMASH` on a polearm) → RANGED.
3. WAND/STAFF → MAGIC if `isMagicAttackSkill(skillId)` (job family 2/12/22), else CLOSE.
4. Otherwise `determineWeaponRoute(weaponType)` — BOW/CROSSBOW/CLAW/GUN → RANGED, else CLOSE.

Common mistake: skills like Iron Arrow / Avenger are **plain RANGED with no custom hitbox** — they just rely on `mobCount` from WZ to pierce through the standard 400 px projectile rect. No special-casing needed, the existing flow handles them once they're in the build.

## Hitbox model

`BotCombatManager.calculateSkillHitBox(effect, bot, target, route, skillId)`:

| Case | Source |
|------|--------|
| Skill has WZ `lt/rb` | `effect.calculateBoundingBox(anchor, facingLeft)`. **Anchor is the bot** by default. **Anchor is the target** if `isStrikePointAnchoredAoeSkill(skillId)` returns true. |
| No bbox, CLOSE route | `fallbackCloseRangeSkillHitBox` — rect of `ATTACK_RANGE_X` (80) × `ATTACK_RANGE_Y` (50) + `ATTACK_DOWN_MAX` (20), or `effect.getRange()` if larger. |
| No bbox, RANGED route, skill in `PIERCE_LINE_PROJECTILE_REACH` | Per-skill thin/tall projectile band (Iron Arrow 4-px line, Avenger 60-px tall). |
| No bbox, RANGED route, otherwise | `clientProjectileHitBox` — 400 px × 100 px (±50 vertical) at bot, scaled by `effect.range / 100` if set, plus passive `Eye of Amazon` / `Keen Eyes` bonuses. |
| No bbox, MAGIC route | Same as ranged. |

### Two traps to remember

1. **Strike-point anchor trap.** When a skill's bbox is anchored at the target, `doesHitBoxIntersectMonster(hitBox, target)` is trivially true — the bbox is *around* the impact point. Use `isPrimaryReachableByBasicWeapon(bot, target, route)` to gate reach separately. Strike-point skill set currently = `{Hunter.ARROW_BOMB}`. Don't add pierce-line skills (Iron Arrow, Avenger, Power Knockback) to this set — they don't anchor at the target.
2. **Selection vs reach are two different gates.** `findGrindTarget(800px)` / `findFollowAttackTarget(400px)` are radius-based *selection*; they happily over-pick distant or vertical mobs. The *reach* gate is `isTargetInAttackRange(plan, bot, target)`, which uses the plan's hitbox. Selection over-pick is by design — the reach gate drops them.

### Pierce-line projectile vertical reach (measured)

Per-skill vertical extents from real-player captures (`PIERCE_LINE_PROJECTILE_REACH` in `BotCombatManager`):

| Skill | hit Δy | miss Δy | reach above feet | `(yAbove, yBelow)` |
|-------|--------|---------|------------------|---------------------|
| Iron Arrow (3201005) | +17 | +41 | ~30 (thin line at launch height) | `(32, -28)` → 4-px band centered ~30 above feet |
| Avenger (4111005 / 14111002) | +56 | +65 | ~60 (tall sprite ~±30 around launch) | `(60, 0)` → 60-px tall, feet to ~60 above |

Positions in MapleStory v83 packets are **at feet**. Projectile launches at ~`player.Y - 30` (mid-body). `yBelow` can be negative (band entirely above feet).

Default `clientProjectileHitBox` is `(50, 50)` — the band crosses feet. Mob body intersection still uses `BotMobHitboxProvider.getMobBounds(mob)` (full mob `lt/rb`), so tall mobs slightly below feet are still clipped where their head crosses the projectile line.

## Ammo & buff gates

In `planSkillAttack`, before building the hitbox:

```java
int ammoCost = Math.max(effect.getBulletCount(), effect.getBulletConsume())
        * shadowPartnerHitMultiplier(bot, route);
if (ammoCost > 0 && route == AttackRoute.RANGED
        && countAmmo(bot, weaponType) < ammoCost) return null;
```

- `bulletCount` (default 1) = visible projectile count per cast. Drives `numDamage` for skills like Double Shot (`bulletCount=2`).
- `bulletConsume` (default 0) = ammo consumed per cast. Distinct from `bulletCount`. Avenger has `bulletConsume=3, bulletCount=1` — fires 1 visible star but consumes 3.
- `countAmmo(bot, weaponType)` returns `MAX_VALUE` while `SOULARROW` (bow/xbow) or `SHADOW_CLAW` (claw) is active — those buffs skip ammo deduction entirely (see `RangedAttackHandler:228`).
- `shadowPartnerHitMultiplier(bot, route)` returns 2 on RANGED if `BuffStat.SHADOWPARTNER` is active, else 1. Both `bulletConsume` and the `numDamage` packet field double under SP (verified at `RangedAttackHandler:234` and `AbstractDealDamageHandler:854`).

## Damage rolls & Shadow Partner

`CombatFormulaProvider.makeTarget(bot, monster, hits, skillId, profile, hitDelay)` already routes `hits > 1 && SHADOWPARTNER` → `rollWithShadowPartnerPhysical` / `rollWithShadowPartnerMagic`. The second half of damage lines roll at ~50%. Bots get this for free — the only client-side change needed for SP support is doubling `hits` upstream (already wired in `planBasicAttack` and `planSkillAttack`).

SP doubling currently only applies to RANGED in the bot (`shadowPartnerHitMultiplier` early-outs on other routes). Melee/magic SP doubling for thief skills (e.g. NightLord Triple Throw is ranged-claw so it covers itself) can be enabled per-route or per-skill if needed.

## Adding support for a new attack skill

Workflow when a user says "make the bot use skill X":

1. **Resolve the constant.** Find `constants.skills.<Job>.<NAME>` (never inline numeric IDs). If the constant is missing, add it from WZ.
2. **Read the WZ entry** at `wz/Skill.wz/<prefix>.img.xml`. Note: `weapon`, `lt`/`rb`, `mobCount`, `bulletCount`, `bulletConsume`, `range`, `mpCon`, `damage`. The presence/absence of `lt/rb` determines whether the skill has a custom bbox.
3. **Check the build plan** in `server/bots/build/*Builds.java`. The bot must learn the skill at non-zero level or `recomputeAttackSkills` will skip it. Add it to the appropriate job tree if needed.
4. **Verify `isActiveAttackSkill` accepts it.** It needs `damage > 0`, an MP/HP cost (or beginner-skill flag), and `skillType` not 1 or 3. If the skill costs no MP and isn't a beginner skill, it'll be filtered out — add it to a whitelist or remove from the build.
5. **Check `determineSkillRoute`.** If it's a melee skill on a bow/crossbow/claw class, add it to `FORCED_CLOSE_RANGE_SKILL_IDS` (`BotAttackExecutionProvider`). If it's a non-projectile skill that uses the ranged packet on a melee class, add it to `isRangedSkill`. **Critical:** any skill in `FORCED_CLOSE_RANGE_SKILL_IDS` will auto-sample its action from the **degenerate** close-range spec — required so the 0xBA broadcast carries a swing body-action id and doesn't crash watching clients. Confirm the degenerate spec actually has swing actions for that weapon type before adding.
6. **Check the hitbox path.** If `lt/rb` is missing, `fallbackSkillHitBox` runs. If you measure (see **Sampling packets** below) that the skill has a tighter/wider projectile sprite than the default 400×100, add a per-skill entry to `PIERCE_LINE_PROJECTILE_REACH`.
7. **Add `canUseAttackSkillWithWeapon` cases** if the skill is restricted to a specific weapon subtype (spear/polearm pattern).
8. **Add tests** in `BotCombatManagerTest` mirroring an existing skill of the same family.
9. **Capture a player packet** of the skill in action and compare byte-for-byte to the bot's `[OUT]` broadcast.

## Sampling packets for verification

When you need ground-truth client behavior — vertical reach, projectile shape, opcode, count byte, anything new — capture from a real player.

### How to capture

In-game admin command: `/monitor <characterName>` toggles `MonitoredChrLogger` for that character. Both incoming (`smash-<name> <op>(0x<hex>)-...`) and outgoing (`[OUT] <name> <op>(0x<hex>)-...`) packets land in `logs/monitored-packets.log` with timestamps.

For a controlled experiment, monitor yourself, fire the skill under specific conditions, then save the relevant log lines to a named file in `logs/` (we keep these as references: `logs/monitored-packets-*.log`).

### Binary-search a numeric threshold

To find a Y-reach / X-reach / damage threshold, capture hit packets at small positive deltas and no-hit packets at large deltas, then halve. The `count` nibble in `numAttackedAndDamage` tells you immediately whether a given mob was inside the projectile rect — if count is N, exactly N mobs were eligible.

### Reading positions

- Player end position is the last `(x, y)` pair before the trailing zeros. Both shorts, little-endian, signed.
- Per-mob position is the `(x1 y1 x2 y2)` pair shortly after the mob `oid`. v83 stores mob position at the mob's **feet**.
- Distance/range field is two bytes I haven't fully decoded — usually safe to ignore for reach analysis.

### Reference captures already in `logs/`

| File | What it documents |
|------|-------------------|
| `monitored-packets.log` | Player Double Shot reference. |
| `monitored-packets-bot-illegal-piercing-attack.log` | Bot mis-routing Power Knockback as RANGED. |
| `monitored-packets-power-knockback.log` | Player Power Knockback (close-range broadcast 0xBA). |
| `monitored-packets-iron-arrow.log` | Player Iron Arrow (6 mobs × 1 dmg pattern). |
| `monitored-packets-hermit-avenger-shadowpartner.log` | Player Avenger with Shadow Partner toggled mid-log; shows the count byte going from `0x51` to `0x52`. |
| `monitored-packets-{ironarrow,avenger}-*-{hit,nohit}.log` | Per-Δy reach measurements used to fit `PIERCE_LINE_PROJECTILE_REACH`. |

## Common pitfalls (things that bit us)

1. **`planAttack` can return null since 2026-05** — every external dereference of `AttackPlan` needs a null check. `isTargetInAttackRange` and `isCloseRangeRoute` are the usual offenders in `BotManager.tickCore`.
2. **`bulletConsume` vs `bulletCount`** — Avenger consumes 3 stars even though it visually fires 1. Use `max(bulletCount, bulletConsume)` for ammo gating.
3. **`canPaySkillCost` doesn't check ammo** — it only checks MP/HP. The ammo gate is separate.
4. **WZ `range` is dual-meaning** — for ranged projectile skills it's a percentage scale on top of the 400-px base. For melee skills (Power Knockback) it's the absolute arc width. Always check `route` before interpreting.
5. **The `0x5B` and `0x0A` sentinels in attack packets are not optional** — they're parsed and validated. Don't reorder fields.
6. **Real-player packet opcodes for melee vs ranged are different** — Power Knockback uses `0x2C` even though the bow is equipped. Don't infer the route from weapon alone; check the actual incoming opcode when reverse-engineering.
7. **Strike-point hitbox is NOT a reach gate** — it's the AoE radius. Bots fired Arrow Bomb at mobs hundreds of pixels out of weapon range until we added the basic-weapon reach gate.
8. **Bots don't own mob movement** — `MOVE_LIFE (0xBC)` for knockback or natural mob motion is the real player's broadcast. Don't add a bot path for it without a deliberate plan.
9. **Direction byte must match the broadcast opcode — wrong combo crashes watching clients.** For a skill in `FORCED_CLOSE_RANGE_SKILL_IDS` (Power Knockback today), the 0xBA broadcast's `direction` byte must be a **swing** body-action id, NOT a ranged shoot id. `resolveSkillAttackAction` therefore samples from the **degenerate** close-range spec (`getBasicAttackSpec(weaponType, true)`) for those skills — the same spec a bow uses when point-blank. Sampling the ranged spec landed `shoot1`/`shoot2` action ids in a melee broadcast and crashed observing v83 clients mid-grind (fix `c3615a966`).
10. **Target selection used to be AoE-blind.** `findGrindTarget` / `findFollowAttackTarget` scored by distance only, so a close lone mob beat a slightly-farther AoE-clearable cluster even with an AoE skill equipped. `grindTargetScore` now subtracts `aoeClusterBonus(entry, target, candidates)` — per-mob bonus (200) for each other live mob within `AOE_CLUSTER_RADIUS_PX (150)`, capped at `aoeSkillMobs - 1`. Heuristic, not per-anchor plan enumeration — see `kb-bot-aoe-cluster-target-bias` for trade-offs.

## Test before/after every combat edit

```
cmd //c "cd /d D:\GameServers\Maplestory\Cosmic && C:\ProgramData\chocolatey\lib\maven\apache-maven-3.9.14\bin\mvn.cmd test -Dtest=Bot*Test > tmp\mvntest.log 2>&1 && echo DONE"
```

Then check `tmp\mvntest.log` for `Tests run:` and `BUILD SUCCESS / FAILURE` lines. The `cmd //c` wrapper is required — direct `mvn` invocation from bash fails with ClassNotFoundException on this box.

`BotEquipOptimizerTest.itemReqMustBeMetWithoutItsOwnStatContribution` is currently failing on master too — flag if it's a problem, but don't treat its red as caused by your combat changes.

## Where authoritative knowledge lives outside this skill

- KB: `kb_bot_aoe_cluster_target_bias`, `kb_bot_cleric_heal_architecture`, `kb_bot_alert_stance_emulation`.
- `D:\ReverseEngineer\` — IDB disassembly toolkit for verifying client-side facts. See `reference_reverse_engineering_toolkit`.
- `CLAUDE.md` at the project root — general behavioral guidelines (simplicity, surgical edits, push back when warranted).
