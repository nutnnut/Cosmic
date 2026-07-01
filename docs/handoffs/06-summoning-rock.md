# 06 — Summoning/Magic rock: sparing use + request when low

**Status:** scoped, verified. Read `README.md` first. Touches buff/skill casting + supply requests.

## Goal
Skills like **Shadow Partner** (claw Hermit) and various summons consume a **rock** per cast
(Summoning Rock `4006001`, Magic Rock `4006000`). Bots should:
1. **Use sparingly, scaled by skill level (low level = short duration = more casts = more rocks).**
   Linear trigger on time-to-kill (TTK in attacks): at **skill lvl 1**, only use when the mob takes
   **~4+** single-target attacks to kill; at **max skill lvl**, use when it **can't be 1-shot** by the
   single-target skill (TTK ≥ 2). Interpolate the TTK-attacks threshold linearly between (4 @ lvl1 → 2 @
   max). I.e. a short-duration buff is only worth a rock on fights long enough to benefit.
2. **Request rocks like potions** when low (**< 100**), only if the bot has a rock-consuming skill.
   Prioritize best donor (party member, non-user of rocks, highest count) — mirror potion sharing.

## VERIFIED facts
- **Consumption SSOT (dynamic, no hardcoding):** `StatEffect.itemCon` / `itemConNo` (`StatEffect.java:147`,
  parsed `:496-497`, enforced on apply `:951-956` — `hasItem(itemCon, itemConNo)` then `removeById`). A
  skill consumes a rock iff its `StatEffect.itemConNo > 0`; `itemCon` is which rock. **No public getter
  exists yet** — ADD `getItemCon()` / `getItemConNo()` to `StatEffect` (minimal upstream change to expose
  for bots, rule #2). Then the bot reads them off the skill's `StatEffect` — never hardcode skill ids.
- Shadow Partner = `4111002` consumes `itemCon=4006001 (Summoning Rock), itemConNo=1` (confirmed in
  `wz/Skill.wz/411.img.xml`). Other rock-consuming skill imgs: `411,1411,231,311,321,521` (verify each via
  itemCon; some are summons the bot doesn't cast yet — focus on buffs the bot DOES cast, e.g. Shadow
  Partner). Magic Rock `4006000` is the mage/other-class analog.
- Items: `4006001` Summoning Rock, `4006000` Magic Rock (handbook/Etc.txt).

## Investigate first (file:line)
- **How the bot casts buffs / Shadow Partner:** `BotBuffManager` (the rebuff loop), `BotEntry.buffSkillIds`
  / `summonSkillIds`, and `BotCombatManager.castSupportSkill` (SSOT cast path). See memory
  `kb_bot_skill_classification` (buff/summon predicates) and `kb_shadow_partner_and_pierce_skills`
  (Shadow Partner doubles ranged numDamage; it's a duration buff). Determine where a rock-consuming buff
  is (re)cast so you can gate it.
- **TTK estimate:** reuse the DPS/kill-time SSOT — `BotGrindAdvisor.killSeconds` or
  `BotCombatManager.estimateBestSkillHitDamage` / `rawPhysicalMax` vs mob HP — to compute "attacks to kill
  the current/target mob with the single-target skill." Don't write a new damage calc (rule #6).
- **Potion request/sharing to mirror:** `BotPotionManager.requestPotShare` / `selectPotDonor`
  (`BotPotionManager.java:528,600` — best donor = party member, non-recipient, highest count) and
  `BotAutopilotManager.requestResupplyErrand` (buy trip). Generalize/parallel this for rocks; prefer
  generalizing the donor-selection over a copy (rule #6). Rocks are NPC-buyable — confirm the shop/price.

## Implement
1. `StatEffect.getItemCon()/getItemConNo()` (expose the SSOT).
2. Rock-aware gate on casting a rock-consuming buff: skip the cast unless (a) the bot has ≥1 of the
   required rock AND (b) the TTK of the current/intended target clears the skill-level-scaled threshold
   (lvl1→4 attacks, max→2). Humanlike jitter ok. Don't break the existing buff loop for non-rock buffs.
3. Low-rock request: when a rock-consuming bot has < 100 of its rock, request via the donor-share path
   (mirror potions) and/or a resupply buy errand. Reuse the potion share-limit/cooldown patterns.

## Verify
- Compile. WZ-free unit tests for the TTK→threshold interpolation and the "has skill that consumes
  itemCon X" detection (stub StatEffect itemCon/No). Don't run nav/graph suites (rule #4).
