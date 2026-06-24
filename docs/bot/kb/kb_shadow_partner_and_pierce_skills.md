---
name: kb-shadow-partner-and-pierce-skills
description: "Pierce-line skills (Iron Arrow, Avenger) packet shape + Shadow Partner damage-line doubling for bot ranged attacks"
metadata: 
  node_type: memory
  type: project
  originSessionId: 67fe2526-fb86-46db-9238-fdcc0ea15f91
---

Captured 2026-05-18 while implementing Iron Arrow, Avenger, and Shadow Partner support. Packets verified from `logs/monitored-packets-iron-arrow.log` (crossbowman Bowgurl) and `logs/monitored-packets-hermit-avenger-shadowpartner.log` (admin Hermit with Shadow Partner toggled mid-log).

## Pierce-line skills — they're plain ranged in v83

- **Iron Arrow** (`Crossbowman.IRON_ARROW = 3201005`) and **Avenger** (`Hermit.AVENGER = 4111005`) do NOT need a custom hitbox model. WZ has no `lt`/`rb` and no `range`; the client uses the standard 400 px projectile rectangle (`CharStats::projectilerange`) + passives. The "pierce up to N mobs" cap comes purely from `mobCount` in WZ (Iron Arrow lvl 20 = 6, Avenger lvl 30 = 6).
- Packet opcode: `0x2D` (RANGED_ATTACK in) / `0xBB` (broadcast out). Same `PacketCreator.addAttackBody` body as Double Shot — `numAttackedAndDamage` = `(numMobs << 4) | numDamagePerMob`, mobs listed sequentially as `oid(4) + 0x00 + dmg(4)…`.
- Iron Arrow / Avenger per-mob `numDamagePerMob = 1` (no shadow partner). Multiple mobs in one packet is normal.
- Avenger has `bulletConsume = 3` in WZ — consumes 3 stars per cast. Iron Arrow has neither; defaults to bulletCount=1 (StatEffect line 489) for visual + 1 arrow consumed.

## Shadow Partner doubling

- BuffStat: `BuffStat.SHADOWPARTNER` (Hermit/NightWalker line; sources: `Hermit.SHADOW_PARTNER = 4111002`, `NightWalker.SHADOW_PARTNER = 14111000`).
- In packets it appears as **`numDamagePerMob × 2`** in the count byte. Per-mob layout grows by N extra damage ints (one per extra hit). E.g. Avenger no-SP `0x51` (5 mobs × 1 dmg), Avenger with SP `0x52` (5 mobs × 2 dmg).
- Server-side validation in `AbstractDealDamageHandler` (line ~816, ~852): second half of damage lines per mob roll at ~50% (`hitDmgMax *= 0.5`); autoban headroom `maxattack * 2`.
- Server-side ammo in `RangedAttackHandler` (line ~176, ~234): `bulletCount *= 2`, `bulletConsume *= 2` when SP is active. So Avenger + SP consumes 6 stars/cast.
- Damage formula path: `CombatFormulaProvider.makeTarget` already gates on `hits > 1 && bot.getBuffEffect(SHADOWPARTNER) != null` and routes to `rollWithShadowPartnerPhysical` / `rollWithShadowPartnerMagic`. So the bot just needs to pass `hits = baseHits * 2` and SP roll generation is handled for free.

## Bot integration (committed this session)

`BotCombatManager.shadowPartnerHitMultiplier(bot, route)`:
- Returns 2 if `route == RANGED` and `bot.getBuffEffect(SHADOWPARTNER) != null`, else 1.
- Only RANGED — melee/magic SP doubling is per-skill (Triple Throw is ranged-claw so already covered; melee thief skills can be enabled later if needed).

Applied at:
1. `planBasicAttack` — basic ranged attack: `numDamage = shadowPartnerHitMultiplier(bot, basicAttackData.route())`.
2. `planSkillAttack` — `attackCount = effectiveHitCount(effect) * shadowPartnerHitMultiplier(bot, route)`.
3. Ammo gate in `planSkillAttack`: `ammoCost = max(bulletCount, bulletConsume) * shadowPartnerHitMultiplier`. The `bulletConsume` fix is also necessary regardless of SP — Avenger's 3-star cost was previously not enforced (bot would attempt to fire on 1 star). Was the cause of Avenger whiff/visual bugs even without SP.

## Worth knowing for the future

- Bots can't acquire SHADOWPARTNER naturally yet (buff is class-restricted and bot buff selection doesn't include it). User plan: admin command to apply it to all bots for fun.
- When that admin command is added, no further bot work is needed — multipliers already wired up.
- Melee + SP: thief melee skills (e.g. Drain) get SP server-side too in `AbstractDealDamageHandler:816`, but only `numDamage > 1` triggers it client-side. If a future melee-thief bot needs SP-doubled melee, extend `shadowPartnerHitMultiplier` to allow CLOSE route for thief-class skills (or keep route filter and case-split per skill).
- The `bulletConsume` fix means previously: Avenger was sometimes selected as AoE skill and silently failed at fire time when bot had <3 stars. Other skills with bulletConsume > bulletCount: probably none common in v83, but the fix is general.

## Pierce-line projectile vertical reach — measured 2026-05-18

The generic ±50 px projectile band over-claims hits for pierce skills whose actual
client sprite is much thinner (Iron Arrow) or only reaches upward (Avenger). Mob
position in v83 = **feet**, projectile launches at approximately **player.Y - 30**
(mid-body). Empirically (by binary-searching Δy where the client stopped including
a mob in the smash- packet's mob list):

| Skill | hit Δy | miss Δy | reach (above feet) | bot config (yAbove, yBelow) |
|-------|--------|---------|---------------------|------------------------------|
| Iron Arrow | 17 | 41 | ~30 (thin line at launch) | (32, -28) → 4-px band |
| Avenger    | 56 | 65 | ~60 (±30 around launch)   | (60, 0)   → 60-px tall |

Implementation: `BotCombatManager.PIERCE_LINE_PROJECTILE_REACH` map, plus signed
`yAbove/yBelow` overload of `clientProjectileHitBox`. `yBelow < 0` allowed for
bands entirely above feet. `fallbackSkillHitBox` takes `skillId` and dispatches.
`doesHitBoxIntersectMonster` still uses the mob's full `lt/rb` body rect, so tall
mobs whose head crosses the projectile line are correctly hit even when their feet
are below the bot.

**Below-feet not measured** — user did not test (depends on mob hitbox). If
future testing shows pierce-line skills can hit mobs visibly below the bot, widen
`yBelow` to a positive value. The current model (thin/tall projectiles fired
from mid-body, no downward reach beyond what mob body extents provide) matches
the captured packet logs and user intuition about projectile sprites.

See also: [[kb-bot-attack-planning-flow]], [[kb-v83-client-combat-internals]], [[kb-power-knockback-packet-structure]].
