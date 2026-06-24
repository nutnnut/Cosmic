---
name: Bot Trade Dupe/Loss Audit
description: Bot-side trade race that can silently lose an item the player gives to a bot mid-farm; root cause is no tick gate on bot.getTrade() != null
type: project
originSessionId: 3a8d5752-b8f3-4728-b9fe-5d28ccb51a7e
---
# Audit scope
Player→bot trade item-loss while the bot was actively farming. Bot-side code only (player code presumed correct per user instruction). Trigger case from user: spare bow in inventory traded to bot while bot was farming/attacking; bow disappeared.

# Trade flow recap (player gives bow to bot)
1. Player chats trade-invite phrase → `BotChatManager` triggers `Trade.inviteTrade(bot, owner)`.
2. Bot's `tickManualTrade` (BotInventoryManager.java:139) sees the invite, after a small delay calls `Trade.visitTrade(bot, owner)` to accept.
3. Player adds bow to their trade window (their inventory slot is cleared at `Trade.addItem` time on the player side).
4. Player locks → `Trade.completeTrade(player)` runs on packet thread; bot has not yet locked, so handshake returns false. Player's `Trade.locked` is now true.
5. Bot tick (`tickManualTrade`) sees `trade.isPartnerConfirmed()` true → calls `completeTradeAndThank` (BotInventoryManager.java:625) → `Trade.completeTrade(bot)`.
6. Handshake locks bot; `fitsInInventory()` for both sides; if pass, both sides' `completeTrade()` runs and `addFromDrop` writes the bow into bot's EQUIP inventory.

# Root-cause finding (bot side)
`runCommonTickSystems` (BotManager.java:2720) does **not** gate any subsystem on `bot.getTrade() != null`. While the player has the trade window open AND while the bot is sitting between steps 5 and 6 above, the bot scheduler thread keeps running:
- `BotInventoryManager.tickPassiveLoot` — picks up nearby drops via `bot.pickupItem(drop)`, then runs `BotEquipManager.autoEquip` for EQUIP drops (BotInventoryManager.java:120-126).
- `BotPotionManager.tickPotionCheck` / `tickPassiveRecovery` — consumes potions (USE inv mutation).
- `BotCombatManager.tickBuffs` / `tickSupportHealing` — may consume USE items.
- `BotEquipManager.autoEquip` indirectly via several entry points triggered by chat/loot.

This produces a TOCTOU race against `Trade.completeTrade`:
- Thread A (packet handler running `Trade.completeTrade(bot)`): fitsInInventory at line 326 returns true (bot has 1 free slot).
- Thread B (bot scheduler `tickPassiveLoot`): `bot.pickupItem(drop)` fills that slot via `InventoryManipulator.addFromDrop`; inventory now full.
- Thread A: enters `local.completeTrade()` (bot side) and runs `InventoryManipulator.addFromDrop(chr.getClient(), bow, show)` (Trade.java:129). Returns `false` because inventory is full.

Trade.completeTrade ignores the boolean return of addFromDrop (Trade.java:129) → bow is silently lost. Player's bow was already removed from their inventory at `Trade.addItem` time and is never restored.

There is also a related silent-loss path on cancel (Trade.java:158) — same pattern, but on the player side, so out of audit scope.

# Secondary findings (bot side, lower severity)

1. **autoEquip during open trade can shuffle EQUIP slot positions** that are referenced by `Trade.items` / `exchangeItems` lists. The lists store `Item` references (not slot positions), and `addFromDrop` re-positions on insert, so identity is preserved — but `KarmaManipulator.toggleKarmaFlagToUntradeable` is applied to the exchanged Item after autoEquip may have moved/re-equipped a *different* item by the same id. Not a confirmed bug, but the surface should be eliminated by the same gate.

2. **`completeTradeAndThank` calls `BotEquipManager.autoEquip` immediately after `Trade.completeTrade(bot)`** (BotInventoryManager.java:175, 200). If the just-received item is "worse" than what's equipped, autoEquip is a no-op — but if it's the same slot as currently equipped and the optimizer picks the new one, the displaced old item lands in EQUIP bag (handleItemMove handles this). Behavior is intended; flagging only as confirmation it's not the loss source.

3. **`pendingTradeRestoreSlots`** safety net (BotInventoryManager.java:609) restores per-slot items if the bot-initiated trade is declined/cancelled. This protects bot-as-giver. There is **no analogous safety net for the player-as-giver direction** because the bot doesn't track items the player puts in. Acceptable — that's player-side state.

# Bot/player parallel-code review
No bot-side reimplementation of trade-engine logic was found. `completeTradeAndThank` delegates to `Trade.completeTrade(bot)` (the same engine human clients use). Bot-side trade automation (visitTrade timing, lock-on-partner-confirmed, batching ≤9 items, pendingTradeRestoreSlots) is bot-specific by necessity (no human clicks). No deletion / unification recommended here.

# Recommended fixes (bot side, awaiting greenlight)

A. **Gate the inventory-mutating subsystems on `bot.getTrade() != null`** in `runCommonTickSystems` (BotManager.java:2720). Specifically suspend:
   - `BotInventoryManager.tickPassiveLoot` (must not pickupItem mid-trade).
   - `BotPotionManager.tickPotionCheck` and any USE-consuming buff tick.
   - Any `autoEquip` call site that fires from tick context (loot pickup, mode-start delays). The chat/explicit trade paths can keep their autoEquip calls.

   Movement, mob-damage, AFK, and pure-render ticks can keep running so the bot doesn't appear frozen. Trade itself is a few seconds — gating is cheap.

B. **Optional belt-and-suspenders in `completeTradeAndThank`**: log a warn if `Trade.completeTrade(bot)` did not actually move the items (detect by checking inventory delta). Helps catch any future regression of (A).

C. **(Out of scope per user — player code)**: have `Trade.completeTrade` check `addFromDrop` boolean and either retry-as-floor-drop or cancel the trade with proper rollback. This is the real silent-loss surface, but lives in player/shared code.

# File:line index
- BotManager.java:2720 — runCommonTickSystems (no trade gate)
- BotInventoryManager.java:100-130 — tickPassiveLoot pickup + autoEquip
- BotInventoryManager.java:139 — tickManualTrade (player-initiated trade)
- BotInventoryManager.java:625 — completeTradeAndThank (calls Trade.completeTrade(bot))
- Trade.java:121 — completeTrade body, ignores addFromDrop boolean
- Trade.java:129 — `InventoryManipulator.addFromDrop(chr.getClient(), item, show)` ← silent loss point
- Trade.java:307 — fitsInInventory precheck (TOCTOU window starts here)
- Trade.java:367-368 — local.completeTrade() / partner.completeTrade()
