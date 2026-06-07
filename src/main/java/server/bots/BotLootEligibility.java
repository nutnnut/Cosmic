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
            ItemId.REMOTE_GACHAPON_TICKET,
            ItemId.RANDOM_BEAUTY_COUPON);

    public static boolean isOwnerValuable(int itemId) {
        return OWNER_VALUABLES.contains(itemId);
    }

    /** Per-bot loot filter set via chat ("only equips" / "mesos only" / "ignore junk" / "loot everything"). */
    public enum LootFilter { ALL, EQUIPS, MESOS, NO_JUNK }

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
        // Leave player-dropped mesos (e.g. Pickpocket drops) on the ground so a Chief Bandit can
        // detonate them with Meso Explosion instead of the loot loop sweeping them up.
        if (drop.getMeso() > 0 && drop.isPlayerDrop()) {
            return false;
        }
        if (!entry.lootEnabled) {
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
            // Owner valuables (Perfect Pitch / gachapon tickets / random beauty coupon) always loot
            // so the bot can hand them over — they bypass the per-bot loot filter entirely.
            if (!isOwnerValuable(itemId)) {
                // Loot filter (mesos are never filtered — only carried items).
                switch (entry.lootFilter) {
                    case MESOS -> { return false; }
                    case EQUIPS -> { if (type != InventoryType.EQUIP) { return false; } }
                    case NO_JUNK -> { if (type == InventoryType.ETC) { return false; } }
                    default -> { }
                }
            }
            // Ores/scrolls funneled into the owner's ore bag don't need bot inventory space.
            if (entry.funnelOreBag && ItemConstants.isOreBagAllowed(itemId)
                    && entry.owner != null && entry.owner.getMap() == bot.getMap()
                    && entry.owner.getOreStorage() != null && !entry.owner.getOreStorage().isFull()) {
                return true;
            }
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
