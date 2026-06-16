# 04 — Dynamic talk-quest completion

**Status:** investigation-first (its scoper didn't return), but there's a strong lead:
**`BotQuestManager` already exists.** Read `README.md` first. Isolated from the other items.

## Goal
Bots should **dynamically** decide to do worthwhile quests and complete simple **talk quests**
(talk NPC A → talk NPC B), not via hardcoded scripts. Calibrate the worth model so this map-10000
tutorial line naturally ranks as worth-doing, **in order**:
- **1031 Heena and Sera** (pure talk) — the calibration anchor; must come out "worth it".
- **1021 Roger's Apple** — expects the player to **consume an item**. If that can't be done without an
  NPC conversation script, **hardcoding that one step is acceptable** (per the owner).
- **1032 Nina's Brother Sen** — simple.
- **1035 Todd's Hunting Method** — simple.
All four are on/near start map **10000 (Mushroom Town)**, in a near-straight tutorial line.

## Investigate first (file:line; VERIFY ids/reqs — don't guess)
1. **Build on `BotQuestManager`.** It already exists with `Config.AUTO_QUESTS` and `QUEST_PIGGYBACK`
   (kill switches), `BotQuestManager.tickScan` (called from `BotManager` common tick) and
   `tickErrand` (from `BotAutopilotManager`). Read it fully — it likely already auto-starts/-completes
   some quest classes and detours for mob quests. Determine what it does NOT yet handle: **talk quests**
   and a **worth-based selection** across available quests.
2. **Server-side quest API.** Find `MapleQuest`/`Quest` and the programmatic path to start/complete a
   quest **without** running the client-NPC dialog: e.g. `Character.startQuest`, `Character.completeQuest`,
   `MapleQuest.forceStart/forceComplete(chr, npcId)`. Determine exactly what a bot calls to: (a) accept a
   talk quest, (b) turn it in at the second NPC. For talk quests there's usually no item/mob requirement,
   so a direct complete-by-id should work.
3. **Verify the 4 quests' requirements/rewards** in quest data — `wz/Quest/Check.img.xml` (start/complete
   reqs) and `Act.img.xml` (rewards), or the quest tables. Confirm 1031/1021/1032/1035 ids and what each
   needs (item to collect/consume for 1021; the rest should be talk-only). Don't guess — read the data
   (see `.claude/skills/wz-data/SKILL.md`).
4. **Travel:** talking to an NPC needs the bot at that NPC. There's NPC-walk infra
   (`BotFerryManager.walkToNpcThenAct`, `BotGachaponManager`/`BotQuestManager` errands walk to NPCs).
   Reuse it; don't reimplement pathing-to-NPC.

## Implement (reuse-first)
- Extend `BotQuestManager` to handle **talk quests**: when a worthwhile talk quest is available and its
  NPC is reachable, walk to NPC A and start it, then to NPC B and complete it (reuse the walk-to-NPC +
  errand pattern already there).
- **Dynamic selection / worth model:** score a quest's worth = reward value (exp + meso + item value,
  reusing the equip/item valuation SSOT where items are rewarded) vs effort (travel time via
  `BotTravelCost`, number of NPC hops). Calibrate constants so 1031/1021/1032/1035 clear the
  "worth it" bar for a fresh low-level bot and get done roughly in their natural order (they're cheap,
  on-map, high relative exp early). Keep it data-driven, not an explicit hardcoded list (rule #3) —
  except the 1021 item-consume step may be hardcoded if scripting is required.
- Respect the existing `AUTO_QUESTS` kill switch.

## Risks / notes
- If a quest legitimately needs an NPC script side-effect (item give/consume, e.g. 1021), prefer the
  server `forceComplete`/`completeQuest` path; only hardcode the minimal item step if there's truly no
  scriptless route.
- Bot chat is ASCII-only if you emit any quest chatter.

## Verify
- Compile. Unit-test the worth/selection scorer (WZ-free, with stubbed quest reward inputs) so the four
  calibration quests rank as expected. Don't run nav/graph suites.
