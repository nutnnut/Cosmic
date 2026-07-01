---
name: Bot Cleric Heal / Buff Cast Architecture
description: File:line map of bot heal/buff flow, packet encoding, formulas, and known gaps
type: project
originSessionId: 37859365-409b-49e5-aa83-f7625e0630f4
---
# Bot Cleric Heal & Buff Cast — Architecture Map

## Tick flow (BotManager.tick → runCommonTickSystems, line ~1155)
Order inside `runCommonTickSystems` (BotManager.java:1349):
1. `BotCombatManager.tickActionLock(entry)` — decrements `attackCooldownMs` (BotCombatManager.java:737)
2. If `runAiTick`:
   1. `rebuildSkillCacheIfNeeded` — cache heal/attack/buff skill ids per job (BotCombatManager.java:330)
   2. **`tickSupportHealing`** (top priority, BotCombatManager.java:467) — sets `attackCooldownMs` on fire
   3. `tickBuffs` (BotCombatManager.java:393) — also gated by `attackCooldownMs > 0`
   4. `BotBuffManager.tick`
3. `tickActionLocked(entry)` (BotManager.java:1627) — returns true if `attackCooldownMs > 0`; caller early-exits and **skips the follow/grind attack planner** (BotManager.java:1197+ and 1250+). This is what makes heal "top priority".

## Heal skill cache
`rebuildSkillCacheIfNeeded` (line 330): any skill returning true for `isHealSkill(id)` (line 1741 — `Cleric.HEAL` or `SuperGM.HEAL_PLUS_DISPEL`) is stored in `entry.healSkillId` and **excluded** from attack/aoe caches.

## Heal cast path — `tickSupportHealing` (BotCombatManager.java:467)
1. Guards: `attackCooldownMs>0` / `!supportHealsEnabled` / `!following&&!grinding` / `healSkillId==0` / on cooldown → return.
2. Resolve `fx = skill.getEffect(lvl)`.
3. Compute `healBounds = fx.calculateBoundingBox(botPos, facingLeft)` — WZ lt/rb box (StatEffect.java:1212). Cleric.HEAL lv30 = `lt=(-300,-200) rb=(300,200)`, symmetric under facing flip.
4. `selfNeedsHeal = needsHeal(bot)` — HP < `SUPPORT_HEAL_TARGET_RATIO=0.9f × maxHp`.
5. `hasPartyMemberInBoundsNeedingHeal(bot, healBounds)` (line 1710) — iterates `bot.getPartyMembersOnSameMap()`, filters by `healBounds.contains(memberPos)` (NOT `cfg.SUPPORT_RANGE`). **This alignment fixes the infinite re-cast loop.**
6. `getUndeadMobsInHealRange(bot, fx)` (line 558) — undead monsters inside the same bounds, capped by `fx.getMobCount()`.
7. If no heal needed and no undead → bail.
8. `fx.canPaySkillCost` / `fx.applyTo(bot)` — StatEffect broadcasts party heal via `isPartyBuff` loop internally (StatEffect.java:1150).
9. Resolve animation timing via `BotAttackExecutionProvider.resolveSkillAttackTiming` → set `attackCooldownMs` to the larger of skill animation (~600ms from `skill.getAnimationTime()`) and weapon fallback.
10. **Always** call `sendHealAttack` (even with 0 undead) so the cast packet / animation plays. Previous code only sent when undead present.

## Heal packet layout — `sendHealAttack` (BotCombatManager.java:524)
Built by hand (not via planAttack) because `fx.applyTo` already paid MP; going through `attackMonster → canUseSkill` would fail MP re-check.

Key fields that must match a real cleric packet (reference: `logs/monitored-packets-cleric-heal-only.log`):
| field | value | why |
|---|---|---|
| `skill` | `2301002` | Cleric.HEAL |
| `numAttackedAndDamage` | `(undead<<4)\|1` | 1 damage line per undead |
| `numAttacked` | undead count | can be 0 (no mob → animation still plays) |
| `display` | `0` | from `CloseRangePacketFields.display()` |
| `direction` | `41` (0x29) | `bodyActionId("alert2")` — BotAttackDataProvider.java:185 |
| `stance` | `0x80` left / `0x00` right | `CloseRangePacketFields.facingMask()` — close-range style even for magic route |
| `rangedirection` | same as stance | mirrors direction byte in packet |

Uses `mimicCloseRangePacketFields("alert2", "alert2", facingLeft)` — BotAttackExecutionProvider.java:161.

Damage per undead target:
- `CombatFormulaProvider.resolveDamageProfile(bot, healSkillId, lvl, true, healTargetCount=undeadTargets.size()+1)` — new overload, file:line CombatFormulaProvider.java:215.
- Formula (client-side GMS v83, sources in `feedback_client_side_formulas_in_bot.md` if created):
  - `MAX = (INT*1.2 + LUK) * MATK/1000 * (1.5 + 5/N) * HealRate%/100`
  - `MIN = (INT*0.3 + LUK) * MATK/1000 * (1.5 + 5/N) * HealRate%/100`
  - `N = 1 + undeadCount`; `HealRate% = fx.getHp()` (WZ: 10→300 across lv1→30)
- DEFAULT_HEAL_TARGET_COUNT = 2 for other callers (algebraically matches old `(INT*4.8+LUK*4)` formula → keeps CombatFormulaProviderTest numbers stable).

## Healing amount (HP restored) — StatEffect, DO NOT MODIFY
Server-side `StatEffect.calcHPChange` (StatEffect.java:~1407) uses `maxHP × hp% / affectedPlayers`. This is tested/working for real players. Bot inherits same path via `fx.applyTo(bot)`. If the bot's maxHP is low (weak cleric), per-person heal can look small — that's correct server behaviour; don't switch to a stat-based formula here (user directive: 2026-04-22).

## Buff cast path — `tickBuffs` + `castSupportSkill` (BotCombatManager.java:393, 1576)
Currently calls only `fx.applyTo(bot, null)`:
- Server-side effect applied (stat buffs, party spread via `isPartyBuff` loop).
- `StatEffect.applyTo` broadcasts `showBuffEffect` to OTHER party members it affects (StatEffect.java:1175), and `giveForeignBuff` for buff-stat display.
- **GAP:** no cast-animation broadcast to observers who aren't party members. Real client flow sends SPECIAL_MOVE (0x5B) → SpecialMoveHandler → `effect.applyTo` (same path) plus separate cast-animation broadcast. The cast animation path for self-cast buffs from the handler side isn't obvious; needs investigation at SpecialMoveHandler.java:145 and what happens BEFORE `applyTo` is called on the real-player path (enableActions + movement stance lock?).

## Animation lock correctness
- `skill.getAnimationTime()` (Skill.java) sums WZ effect frame delays → for Heal 2301002 the effect dir has ~6 × 100ms frames = ~600ms. Matches packet log observed 583–628ms gap between consecutive Heal casts when button held.
- `resolveSkillAttackTiming` fallback to `toCooldownMs(600)` if skill animation time is 0.

## Decision-cooldown is removed (user directive 2026-04-22)
- Dropped `cfg.SUPPORT_HEAL_CD_MS`, `SUPPORT_HEAL_RATIO`, `SUPPORT_HEAL_MISSING_HP` config fields.
- `entry.nextSupportHealAt` left declared on BotEntry (unused — safe to remove later).
- Added `cfg.SUPPORT_HEAL_TARGET_RATIO = 0.90f`.
- Rate limiting is done solely by `attackCooldownMs` animation lock so casting rate matches a legit client.

## Key related files
- `src/main/java/server/bots/BotCombatManager.java` — tickSupportHealing, sendHealAttack, needsHeal, hasPartyMemberInBoundsNeedingHeal, castSupportSkill
- `src/main/java/server/bots/BotAttackExecutionProvider.java` — mimicCloseRangePacketFields, clientAttackStanceId, bodyActionId
- `src/main/java/server/bots/combat/BotAttackDataProvider.java:185` — `"alert2" → 41` bodyActionId mapping
- `src/main/java/server/combat/CombatFormulaProvider.java:215,540` — Heal damage formula, healTargetCount overload
- `src/main/java/server/StatEffect.java:1212,1407` — calculateBoundingBox, calcHPChange (heal formula — DON'T MODIFY)
- `src/main/java/net/server/channel/handlers/AbstractDealDamageHandler.java:578,673` — parseDamage incoming packet layout + Heal anti-cheat validation formula (same `(INT*4.8+LUK*4)` coefficients; bot's target-multiplier-scaled damage stays under this cap in typical cases)
- `src/main/java/net/server/channel/handlers/SpecialMoveHandler.java:51` — how real player buff/skill cast flows; useful if implementing bot buff cast animation
- `src/main/java/tools/PacketCreator.java:2363,3472,6244` — magicAttack, showBuffEffect, showForeignEffect packet builders
- `logs/monitored-packets-cleric-heal-only.log` — 22 lines, 7 consecutive Heal casts, stance=0x80 direction=0x29 proven
- `logs/monitored-packets-cleric-heal-with-attacks.log` — Heal interspersed with basic attacks; useful when extending to verify attack stance interleaving
- `wz/Skill.wz/230.img.xml` — skill data; Heal 2301002 hp scales 10→300 lv1→30, mobCount=6 all levels, lt/rb widens at lv20
- `wz/Mob.wz/2300100.img.xml` — flying mob example (`fly/0` only, no `stand/0`); triggered the BotMobHitboxProvider fallback chain
