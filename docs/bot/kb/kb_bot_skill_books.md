---
name: kb_bot_skill_books
description: "Bot fourth-job skill/mastery-book use, grind demand, cohort sharing, and market flow"
---

Bot skill-book demand is defined by `BotSkillBookManager.wantsBook`: the item must be in the valid
skill/mastery ranges owned by `ItemConstants`, map to the bot's current job, and raise the cap of a
skill present in the selected `BotBuildManager` build. Future-needed books remain wanted before the
skill reaches the book's `reqSkillLevel`, so a Mastery 30 book is not sold or given away while the
bot is still training toward level 15. `needsBookAcquisition` additionally requires that the bot
does not own a copy; grind and market demand use that form. Autonomous consumption separately uses
`canUseWantedBookNow` and the shared player eligibility rule.

Actual consumption is shared with players through `client.processor.stat.SkillBookProcessor`.
Bots consume one book per jittered attempt and immediately run SP assignment after a successful cap
increase. Do not reproduce skill-book validation or success rolls in bot code.

Needed book drops join the grind advisor's existing progression prospects, on the SAME scale as gear
rather than a parallel lens: `BotSkillBookManager.capGainFraction` returns a fraction of the bot's
worn offense, which is exactly the `GearProspect.dpsGainFraction` unit an equip drop produces, so
`BotGrindPlanner` keeps one gear term with no book-specific branch. The fraction has two SSOT-scored
halves, summed:

- **stat grants** (Maple Warrior, Sharp Eyes, Hyper Body, flat WATK/WDEF/...) — the `StatEffect`
  statup delta between the current and new cap, translated into the equip-stat vocabulary and priced
  by `BotScrollManager.equipValueFromStats`, the same scorer equips and scrolls use. Percentage buffs
  resolve against the bot's own base stats the way `Character.recalcLocalStats` applies them, so a
  stronger bot values Maple Warrior 30 more. `ACC` is deliberately not mapped: `equipValueFromStats`
  has no accuracy term either, and the grind path already prices accuracy via `accuracyHitFactor`.
- **attack power** (Genesis, Dragon Roar, ...) — damage% across the skill's lines at the new cap,
  measured against the bot's best current attack, which is a fractional DPS gain by construction.

Both are scaled by the book's application chance. A skill whose gain the offense model cannot see —
pure mitigation passives such as Achilles, whose effect lives in `getX()` rather than statups —
scores zero and is not farmed for. That is a known coverage gap, not a tuning knob; do not paper over
it with a constant. Ordinary drop probability and attainability remain owned by `BotGrindPlanner`.

Surplus books are USE-shelf market goods valued through the existing belief/farm-cost price path.
The owner, same-map party members, and trusted stable/crew siblings get first refusal through the
normal loot-offer trade window (one copy, even from a stack); real players accept manually. Remaining
stacks can enter hired-merchant stalls as one-book bundles. A missing planned book is itself a market
trip reason, and FM browsers buy only missing books below their replacement-cost/belief ceiling.
