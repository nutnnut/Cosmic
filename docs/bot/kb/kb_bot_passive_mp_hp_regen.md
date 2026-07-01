---
name: kb_bot_passive_mp_hp_regen
description: "Bot passive HP/MP regen (no client to send HEAL_OVER_TIME) — 10s tick, base+skill, Improved MP Recovery calibrated to a captured packet"
metadata: 
  node_type: memory
  type: project
  originSessionId: d853fdb6-1aeb-4464-ab0a-c2eb5ab7e5e7
---

Bots have no real client, so they never send the client→server natural-recovery packet
`HEAL_OVER_TIME` (RecvOpcode `0x59`; handler `HealOvertimeHandler` = `skip(8); healHP=readShort; healMP=readShort`, every ~10s when standing). Server has NO general natural regen for anyone — players self-heal client-side. So bot regen is bot-specific by necessity (no player code to share).

Implementation (already scaffolded on experimental branch, recalibrated this session):
- `BotPotionManager.tickPassiveRecovery` (called from BotManager common tick), fires every `cfg.MP_RECOVERY_INTERVAL_MS = 10_000` when not HP+MP full; `addMPHP(hp,mp)`.
- `calculatePassiveMpRecovery` = `BASE_MP_RECOVERY(3)` + warrior/DawnWarrior MP-rec skills + `getMagicianMpRecoveryBonus`. Skill bonuses only when `isStandingStillForRecovery` (moving = base only, matches client giving less while moving).
- `calculatePassiveHpRecovery` = `BASE_HP_RECOVERY(10)` + Warrior IMPROVED_HPREC.

Improved MP Recovery (Magician passive 2000000) has **NO WZ `x` value** — the v83 client computes it internally (see `TryRecovery` @0x00a02e34 + amount fns 0x764c72/0x764d62/0x764f44; full reconstruction is an accumulator/fixed-point mess, not worth it). Modeled as:
  `bonus = charLevel * skillLevel / cfg.IMPROVED_MP_RECOVERY_DIVISOR(10)`
Calibrated to a captured packet (`logs/monitored-packets-lv65-mage-lv16-improved-mp-recovery-4852maxmp.log`): lv65 mage, IMR 16 → healMP `0x6B`=**107**/10s = base 3 + ⌊65×16/10⌋=104. Second data point: lv11 rogue (no IMR) = **3**/10s (confirms base 3). The old formula `(INT/10)*level` overshot ~5×.

Community-reported shape was "MP/char-level = 0.5/1.0/1.5/2.0 at skill 1/6/11/16" (≈0.4+0.1·skill per level → 130 at lv65/skill16); their flat 2/level slightly overshoots what the client actually sends (packet→1.6/level). Matched the packet, not the community number. A 2nd mage capture at a different skill level would confirm the skill-scaling slope.

Why it matters: no passive MP regen → an out-of-MP mage with no MP pots was stuck (can't cast). Pairs with the wand-melee degenerate fallback (already exists in grind mode, mirrors claw) and the MP-dry resupply errand. See [[kb_bot_use_value_shelf]], [[kb_bot_ranged_spacing_weapons]].
