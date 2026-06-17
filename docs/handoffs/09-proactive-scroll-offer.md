# 09 — Proactive scroll offer (give useless-to-self scrolls to who can use them)

**Status:** scoped. Read `README.md` first. Touches scroll relevance + trade flow. Any order (independent).

## Goal
A bot holding a scroll **useless to itself** but **useful to a party member/owner** should proactively
**offer** it (e.g. a bow-attack scroll → archer, a claw scroll → assassin, INT/LUK scrolls → a mage).
Use the existing scroll-value SSOT to decide "useless for self" / "useful for recipient" — **no class
hardcoding**; the SSOT already knows dynamically.

## SSOTs + the important nuance
- **Useless-to-self (stat-based, EXISTS):** `BotInventoryManager.isIrrelevantEquipScroll(bot, itemId)`
  (`BotInventoryManager.java:2159`) — checks the scroll's stats against `BotEquipManager.relevantStatsFor(job)`.
  **GAP:** it's stat-only, so a **bow-attack** scroll (WATK, which IS relevant to a warrior) is NOT flagged
  even though a warrior has no bow to use it on. So `isIrrelevantEquipScroll` alone is too weak.
- **Stronger "useless-to-self" (category-aware):** the scroll **planner** only builds scroll options for
  equips the bot actually owns/can wear (`BotScrollManager.buildOptions` per owned equip). A scroll that
  improves **no owned/wearable equip slot** is effectively useless. Use this (or extend
  `isIrrelevantEquipScroll` to also require an applicable equip CATEGORY the bot can wear) so wrong-weapon
  scrolls are caught, not just wrong-stat ones.
- **Useful-to-recipient:** the inverse — run the same relevance check against the candidate recipient's
  job/gear (`relevantStatsFor(recipient.getJob())` + can the recipient wear the scroll's equip category /
  does it improve their gear). Pick the recipient for whom it's most useful.
- **Trade flow (EXISTS):** `BotInventoryManager.startScrollReviewTrade` / `startTradeSequence` (the
  scroll-review trade already hands gear+scroll to a recipient; reuse the recipient resolution —
  `commanderOrOwner` for admin, else owner; for party, pick the best-fit member).

## Implement
- Scan the bot's USE inventory for equip scrolls that are useless-to-self (category-aware, above).
- Find the best recipient among the owner + party/cohort members for whom the scroll is useful.
- Offer via the existing trade (don't reimplement trading). Rate-limit / don't spam; humanlike, ASCII chat.
- Only offer when genuinely useless to self AND useful to someone present — otherwise leave it (the
  irrelevant-scroll SELL path already exists for true junk: `BotInventoryManager` sell-trash).

## Verify
- WZ-free tests: a bow-att scroll is useless to a warrior (category) and useful to an archer; an INT
  scroll useless to STR jobs and useful to a mage; recipient selection picks the best fit. Compile.
  No nav/graph suites (rule #4).
