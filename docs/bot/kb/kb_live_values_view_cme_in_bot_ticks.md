---
name: kb_live_values_view_cme_in_bot_ticks
description: Bot tick threads CME when iterating live unmodifiable values() views; fixed Inventory.list() to snapshot under lock
metadata:
  type: feedback
---

Several server collections returned a LIVE view via `Collections.unmodifiableCollection(someMap.values())` built under a lock but **returned and iterated after the lock released**. Iterating off the owning thread races concurrent mutation and throws `ConcurrentModificationException` (`Collections$UnmodifiableCollection$1.next` -> `LinkedHashMap$LinkedValueIterator`).

Bots tick on `TimerManager-Worker-*`, so any bot-tick code reading player/map state is exposed. **Real crash (2026-05-31):** `BotPotionManager.countPotions` iterated `bot.getInventory(USE).list()` during the periodic potion check (tick -> tickCore -> runCommonTickSystems -> tickPotionCheck -> countPotions), racing inventory mutation.

**Root fix (commit 6050d1d9d):** `client/inventory/Inventory.java` `list()` now returns `Collections.unmodifiableList(new ArrayList<>(inventory.values()))` — a snapshot copied UNDER its own `lock`. This fixes the crash plus ~30 other bot `inv.list()` sites and player-side `findById`/`findByName` which iterate `list()` too. Tiny per-call copy cost (inventories are small).

Sibling fix earlier the same session (commit ab7253d97): `Character.getSummonsValues()` had the identical live-view bug -> now `new ArrayList<>(summons.values())` + `summons` is a `ConcurrentHashMap`. (That one was NOT the tick crash — only GM `Hide()` / packet handlers reach it.)

**Why:** a values()-view "under lock" gives NO protection once returned; the caller iterates outside the lock. `Collections$UnmodifiableCollection$1.next` in a trace is the tell that it's a live unmodifiable view, not a copy.

**How to apply:** never iterate a live `.values()` / `.list()` / unmodifiable-view return off the owning thread. Snapshot under the lock (as `list()` now does), or use existing snapshot accessors (`listById`, `getAllMonsters`, `getAllPlayers`, `getMapObjectsInRange` all copy under lock). Also: server logs truncate to 2 JDK frames — get the FULL stacktrace first; the real caller frame (`BotPotionManager:83`) only appeared once the full trace was pasted, after a long blind audit. Related: [[feedback_bot_coding_guidelines]], [[project_codebase_overview]].
