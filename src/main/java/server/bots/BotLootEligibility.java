package server.bots;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import constants.id.ItemId;
import constants.inventory.ItemConstants;
import server.bots.pq.BotPqHooks;
import server.maps.MapItem;
import server.maps.MapleMap;

import java.util.Set;

public final class BotLootEligibility {
    public static final int KPQ_COUPON = 4001007;
    public static final int KPQ_PASS = 4001008;

    // Looted items a bot should route to its owner (hand over or offer via trade).
    private static final Set<Integer> OWNER_VALUABLES = Set.of(
            ItemId.PERFECT_PITCH,
            ItemId.LOCAL_GACHAPON_TICKET,
            ItemId.REMOTE_GACHAPON_TICKET);

    public static boolean isOwnerValuable(int itemId) {
        return OWNER_VALUABLES.contains(itemId);
    }

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
        if (itemId == KPQ_COUPON && (BotPqHooks.shouldSkipCouponLoot(entry)
                || (entry.kpq.couponTarget > 0 && bot.getItemQuantity(KPQ_COUPON, false) >= entry.kpq.couponTarget))) {
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
                && now - drop.getDropTime() >= 3_000;
    }
}
