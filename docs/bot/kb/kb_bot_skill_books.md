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

Needed book drops join the grind advisor's existing progression prospects. Their value scales with
the planned cap levels unlocked and the book's application chance; ordinary drop probability and
attainability remain owned by `BotGrindPlanner`.

Surplus books are USE-shelf market goods valued through the existing belief/farm-cost price path.
The owner, same-map party members, and trusted stable/crew siblings get first refusal through the
normal loot-offer trade window (one copy, even from a stack); real players accept manually. Remaining
stacks can enter hired-merchant stalls as one-book bundles. A missing planned book is itself a market
trip reason, and FM browsers buy only missing books below their replacement-cost/belief ceiling.
