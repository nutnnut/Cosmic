package server.bots;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import constants.inventory.ItemConstants;
import server.bots.pq.BotPqHooks;
import server.maps.MapItem;
import server.maps.MapleMap;

public final class BotLootEligibility {
    public static final int KPQ_COUPON = 4001007;
    public static final int KPQ_PASS = 4001008;
    public static final int HPQ_RICE_CAKE = 4001101;
    public static final long MIN_TARGET_LOOT_AGE_MS = 3_000L;
    public static final long BOT_INVENTORY_DROP_TARGET_LOOT_AGE_MS = 15_000L;

    private BotLootEligibility() {
    }

    public static boolean isPresent(MapleMap map, MapItem drop) {
        return map != null
                && drop != null
                && !drop.isPickedUp()
                && map.getMapObject(drop.getObjectId()) == drop;
    }

    public static boolean canBotLoot(BotEntry entry, Character bot, MapItem drop) {
        if (entry == null || bot == null || drop == null || !drop.canBePickedBy(bot)) {
            return false;
        }

        int itemId = drop.getItemId();
        if (itemId == KPQ_PASS) {
            return false;
        }
        if (itemId == HPQ_RICE_CAKE) {
            return false;
        }
        if (itemId == KPQ_COUPON && (BotPqHooks.shouldSkipCouponLoot(entry)
                || (entry.kpq.couponTarget > 0 && bot.getItemQuantity(KPQ_COUPON, false) >= entry.kpq.couponTarget))) {
            return false;
        }
        if (itemId == BotZakumPrequestManager.ITEM_KEY || itemId == BotZakumPrequestManager.ITEM_FIRE_ORE
                || itemId == BotZakumPrequestManager.ITEM_PQ_DOCUMENT) {
            // Zakum PQ items are picked up explicitly by role in BotZakumPqRun (SSOT for both the
            // autonomous errand and player-led runs); passive loot taking them would vacuum the
            // leader's delivered key pile or the 7-stack pending under the Giant Chest.
            return false;
        }
        if (itemId > 0 && !bot.needQuestItem(drop.getQuest(), itemId)) {
            return false;
        }
        if (drop.getMeso() <= 0 && itemId > 0) {
            InventoryType type = ItemConstants.getInventoryType(itemId);
            Inventory inv = bot.getInventory(type);
            return inv == null || !inv.isFull();
        }
        return true;
    }

    public static boolean canBotTargetLoot(BotEntry entry, Character bot, MapleMap map, MapItem drop, long now) {
        return isPresent(map, drop)
                && canBotLoot(entry, bot, drop)
                && now - drop.getDropTime() >= requiredTargetLootAgeMs(bot, drop);
    }

    static long requiredTargetLootAgeMs(Character bot, MapItem drop) {
        if (bot == null || drop == null) {
            return MIN_TARGET_LOOT_AGE_MS;
        }
        if (isBotInventoryDrop(drop)) {
            return BOT_INVENTORY_DROP_TARGET_LOOT_AGE_MS;
        }
        return MIN_TARGET_LOOT_AGE_MS;
    }

    private static boolean isBotInventoryDrop(MapItem drop) {
        int ownerId = drop.getOwnerId();
        return drop.isPlayerDrop()
                && ownerId > 0
                && BotManager.getInstance().getActiveOwnerByBotCharId(ownerId) != null;
    }
}
