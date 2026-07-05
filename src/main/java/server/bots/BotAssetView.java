package server.bots;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.ItemFactory;
import net.server.world.World;
import server.Storage;
import server.Trade;
import server.maps.HiredMerchant;
import server.maps.PlayerShopItem;
import tools.Pair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only aggregation of everything a bot owns, including the containers the rest of the bot
 * code cannot see today (stall stock, storage, trade escrow, merchant earnings) alongside the
 * live bags.
 *
 * Contract (docs/bot/living-economy-design.md sec 10.4):
 * - valuation / wealth / market decisions read the FULL snapshot;
 * - wear / consume / stage paths may only touch IN-HAND assets (EQUIPPED / BAG);
 * - an item appears in exactly one location (no double counting: staging into a trade or stall
 *   removes it from the bag, so each container's contents are disjoint by construction).
 */
final class BotAssetView {

    enum Location { EQUIPPED, BAG, STALL, STORAGE, TRADE_ESCROW, FREDRICK }

    /** One owned item stack. quantity is the effective total (stall bundles are expanded). */
    record Asset(Item item, int quantity, Location location) {
        boolean inHand() {
            return location == Location.EQUIPPED || location == Location.BAG;
        }
    }

    /** Immutable point-in-time view. Meso is split by the wallet it sits in. */
    record Snapshot(List<Asset> assets, long liquidMeso, long merchantMeso, long storageMeso,
                    long tradeEscrowMeso) {
        long totalMeso() {
            return liquidMeso + merchantMeso + storageMeso + tradeEscrowMeso;
        }

        List<Asset> at(Location loc) {
            List<Asset> out = new ArrayList<>();
            for (Asset a : assets) {
                if (a.location() == loc) {
                    out.add(a);
                }
            }
            return out;
        }

        List<Asset> inHand() {
            List<Asset> out = new ArrayList<>();
            for (Asset a : assets) {
                if (a.inHand()) {
                    out.add(a);
                }
            }
            return out;
        }
    }

    private static final InventoryType[] BAG_TYPES = {
            InventoryType.EQUIP, InventoryType.USE, InventoryType.SETUP,
            InventoryType.ETC, InventoryType.CASH
    };

    private BotAssetView() {
    }

    /** Live-container snapshot; never touches the DB. */
    static Snapshot snapshot(Character bot) {
        return gather(bot, List.of());
    }

    /** snapshot(...) plus DB-backed Fredrick leftovers (closed-stall items awaiting pickup). */
    static Snapshot snapshotWithFredrick(Character bot) {
        List<Item> fredrick = new ArrayList<>();
        try {
            for (Pair<Item, InventoryType> p : ItemFactory.MERCHANT.loadItems(bot.getId(), false)) {
                fredrick.add(p.getLeft());
            }
        } catch (Exception e) {
            // DB unavailable: degrade to the live view rather than fail the caller
        }
        return gather(bot, fredrick);
    }

    private static Snapshot gather(Character bot, List<Item> fredrickItems) {
        List<Item> equipped = itemsOf(bot.getInventory(InventoryType.EQUIPPED));
        Map<InventoryType, List<Item>> bags = new EnumMap<>(InventoryType.class);
        for (InventoryType t : BAG_TYPES) {
            bags.put(t, itemsOf(bot.getInventory(t)));
        }

        List<PlayerShopItem> stall = List.of();
        HiredMerchant hm = liveMerchant(bot);
        if (hm != null) {
            stall = new ArrayList<>(hm.getItems());
        }

        List<Item> storageItems = List.of();
        long storageMeso = 0;
        Storage storage = bot.getStorage();
        if (storage != null) {
            storageItems = new ArrayList<>(storage.getItems());
            storageMeso = storage.getMeso();
        }

        List<Item> tradeItems = List.of();
        long tradeMeso = 0;
        Trade trade = bot.getTrade();
        if (trade != null) {
            // own staged side: already removed from the bags at addItem time
            tradeItems = trade.getItems();
            tradeMeso = trade.getStagedMeso();
        }

        return assemble(equipped, bags, stall, storageItems, tradeItems, fredrickItems,
                bot.getMeso(), bot.getMerchantMeso(), storageMeso, tradeMeso);
    }

    /** Pure assembly core (test seam): callers supply container contents, this tags + sums. */
    static Snapshot assemble(List<Item> equipped, Map<InventoryType, List<Item>> bags,
                             List<PlayerShopItem> stall, List<Item> storageItems,
                             List<Item> tradeItems, List<Item> fredrickItems,
                             long liquidMeso, long merchantMeso, long storageMeso,
                             long tradeEscrowMeso) {
        List<Asset> assets = new ArrayList<>();
        for (Item it : equipped) {
            assets.add(new Asset(it, it.getQuantity(), Location.EQUIPPED));
        }
        for (List<Item> bag : bags.values()) {
            for (Item it : bag) {
                assets.add(new Asset(it, it.getQuantity(), Location.BAG));
            }
        }
        for (PlayerShopItem psi : stall) {
            if (!psi.isExist()) {
                continue;
            }
            int qty = Math.max(0, psi.getBundles()) * Math.max(1, (int) psi.getItem().getQuantity());
            if (qty <= 0) {
                continue; // sold out
            }
            assets.add(new Asset(psi.getItem(), qty, Location.STALL));
        }
        for (Item it : storageItems) {
            assets.add(new Asset(it, it.getQuantity(), Location.STORAGE));
        }
        for (Item it : tradeItems) {
            assets.add(new Asset(it, it.getQuantity(), Location.TRADE_ESCROW));
        }
        for (Item it : fredrickItems) {
            assets.add(new Asset(it, it.getQuantity(), Location.FREDRICK));
        }
        return new Snapshot(Collections.unmodifiableList(assets),
                liquidMeso, merchantMeso, storageMeso, tradeEscrowMeso);
    }

    /**
     * The bot's merchant whether it is still being set up (still attached to the character) or
     * already published to a map (detached; only the world registry still points at it).
     */
    private static HiredMerchant liveMerchant(Character bot) {
        HiredMerchant direct = bot.getHiredMerchant();
        if (direct != null) {
            return direct;
        }
        World world = bot.getWorldServer();
        return world != null ? world.getHiredMerchant(bot.getId()) : null;
    }

    private static List<Item> itemsOf(Inventory inv) {
        return inv != null ? new ArrayList<>(inv.list()) : List.of();
    }
}
