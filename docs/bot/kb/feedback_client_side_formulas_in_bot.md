---
name: Client-Side Formulas Belong in Bot Code, Not StatEffect
description: When a game formula is computed client-side for real players, replicate it in bot code — do NOT change the server-side authoritative path (StatEffect, damage validators)
type: feedback
originSessionId: 37859365-409b-49e5-aa83-f7625e0630f4
---
Rule: values that the real client computes and sends (damage rolls, cast stance/direction bytes, timing fields) must be replicated in **bot code** (BotCombatManager, CombatFormulaProvider, BotAttackExecutionProvider), never by changing the shared server path.

**Why:** StatEffect and the damage handlers are authoritative for real players and are tested/calibrated. Touching them to "fix bots" risks changing behaviour for actual players. The user explicitly said 2026-04-22: *"StatEffect.java should not be touched, upstream code for player is tested and working correctly, either your formula is incorrect or bug is in bot code, try not to modify stateffect. ... or if value is calculated client-sided it should be done in bot related code."*

**How to apply:**
- Before editing `StatEffect.java` or a `*Handler.java` damage path, ask: is the field something the real client sends, or something the server decides? If the former, the fix is in bot code.
- When adding a formula overload (e.g. Heal target multiplier), default the new parameter so existing callers and tests are unaffected. Example: `CombatFormulaProvider.DEFAULT_HEAL_TARGET_COUNT = 2` keeps pre-change numerics for non-Heal tests.
- Cite the client-side source (Ayumilove compilation, SouthPerry, monitored-packets capture) in the code comment so future maintainers know why bot code diverges from the server validator.
- Packet byte encoding must match a **real capture**, not be guessed. `logs/monitored-packets-*.log` is ground truth for bot-emitted packets.

**Applies to:**
- Heal damage formula (CombatFormulaProvider.resolveMagicDamageProfile)
- Heal cast packet stance/direction bytes (BotCombatManager.sendHealAttack)
- Attack stance/direction bytes (BotAttackExecutionProvider)
- Any future: buff cast broadcast, summon spawn packet, item-use animation

**Does NOT apply to:**
- Party heal amount (`StatEffect.calcHPChange`) — server-authoritative; bot uses same path via `fx.applyTo(bot)` and the formula is calibrated for real players. Low bot maxHP → low heal is correct behaviour, not a bug.
