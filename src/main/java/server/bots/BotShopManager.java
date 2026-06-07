package server.bots;

import client.Character;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import client.inventory.manipulator.InventoryManipulator;
import client.inventory.manipulator.KarmaManipulator;
import constants.game.GameConstants;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import server.Shop;
import server.ShopFactory;
import server.ShopItem;
import server.StatEffect;
import server.Storage;
import server.life.NPC;
import server.maps.Foothold;
import server.maps.MapObject;
import server.maps.MapObjectType;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;

final class BotShopManager {

    // Test seam: ItemInformationProvider's WZ/DB static initializer can't run in unit tests,
    // so projectile attack / slot-max lookups go through overridable hooks (see BotShopManagerTest).
    @FunctionalInterface
    interface SlotMaxLookup {
        short slotMax(Character bot, int itemId);
    }

    static IntUnaryOperator projectileWatk =
            id -> ItemInformationProvider.getInstance().getWatkForProjectile(id);
    static SlotMaxLookup ammoSlotMax =
            (bot, id) -> ItemInformationProvider.getInstance().getSlotMax(bot.getClient(), id);

    private static final int SHOP_MANHATTAN_RADIUS = 200;
    private static final int SHOP_ARRIVE_DIST = 100;
    private static final int SHOP_NPC_SEARCH_DIST = 601;
    private static final int SHOP_APPROACH_DELAY_MAX_MS = 5001;
    private static final int SHOP_STEP_DELAY_MIN_MS = 2000;
    private static final int SHOP_STEP_DELAY_MAX_MS = 4001;
    private static final int SELL_TRASH_STEP_DELAY_MS = 500;
    private static final long SHOP_VISIT_TIMEOUT_MS = 30_000L;
    private static final long SHOP_SEQUENCE_TIMEOUT_MS = 45_000L;
    private static final long SHOP_STUCK_FALLBACK_MS = 1000L;
    private static final int SHOP_STUCK_MOVE_TOLERANCE_PX = 2;
    private static final int POT_TRIGGER_THRESHOLD = 4; // 80% of target (5) for early trigger
    private static final int POT_TARGET_THRESHOLD = 5; // full target when buying at shop
    private static final int AMMO_TRIGGER_THRESHOLD = 8;
    private static final int AMMO_TARGET_THRESHOLD = 10; // full target when buying at shop
    private static final int RECHARGE_MAX_SETS = 10; // cap recharge to the best N own-type stacks
    private static final long WINDOW_SHOP_MSG_CD_MS = 300_000L; // throttle the "window shopping" notice (5 min)

    private BotShopManager() {}

    private record NpcShopMatch(NPC npc, Shop shop, Point npcPos) {}

    private record ShopGearUpgrade(short slot, int itemId, int price, String name) {}

    private enum ShortfallReason { NONE, NO_MESO, NO_SPACE, OTHER }

    private record BuyReport(int itemId, int quantity, int requestedQuantity, ShortfallReason reason) {
        boolean hasShortfall() {
            return reason != ShortfallReason.NONE && quantity < requestedQuantity;
        }
    }

    private record ShopSlotItem(short slot, ShopItem shopItem) {}

    private record PurchaseSequence(BotEntry entry,
                                    Character bot,
                                    Point npcPos,
                                    List<PurchaseAction> actions,
                                    List<String> bought,
                                    BuyReport firstShortfall) {
        PurchaseSequence withFirstShortfall(BuyReport report) {
            if (firstShortfall == null && report != null && report.hasShortfall()) {
                return new PurchaseSequence(entry, bot, npcPos, actions, bought, report);
            }
            return this;
        }
    }

    @FunctionalInterface
    private interface PurchaseAction {
        PurchaseSequence run(PurchaseSequence sequence, Shop shop);
    }

    private static final List<String> RESUPPLY_MSGS = List.of(
            "brb gotta resupply", "one sec, going to restock", "be right back, need supplies",
            "brb, refilling", "be right back~"
    );

    private static final List<String> SHOPPING_MSGS = List.of(
            "shopping...", "restocking now", "buying stuff", "ok let me buy", "getting supplies"
    );

    static void onMapChange(BotEntry entry, Character bot) {
        clearShopState(entry);

        NpcShopMatch match = findBestShop(bot, false);
        if (match == null) {
            return;
        }

        WeaponType wt = BotAttackExecutionProvider.getEquippedWeaponType(bot);
        boolean needsRecharge = needsRechargeForShop(bot, wt, ammoTriggerThreshold());
        boolean needsAmmoForShop = needsFixedAmmoForShop(bot, match.shop, wt, ammoTriggerThreshold());
        int[] pots = BotPotionManager.countPotions(bot);
        int potTrigger = BotManager.cfg.POT_LOW_WARN * POT_TRIGGER_THRESHOLD;
        boolean needsHpPots = pots[0] < potTrigger && findPotionItem(match.shop, bot, true) != null;
        boolean needsMpPots = pots[1] < potTrigger && findPotionItem(match.shop, bot, false) != null;
        boolean wantsGear = hasAffordableGearUpgrade(bot, match.shop);
        if (!needsRecharge && !needsAmmoForShop && !needsHpPots && !needsMpPots && !wantsGear) {
            return;
        }

        long distSq = (long) bot.getPosition().distanceSq(match.npcPos);
        if (distSq > 1000L * 1000L) {
            BotManager.getInstance().botSay(bot, BotManager.randomReply(RESUPPLY_MSGS));
        }

        startShopVisit(entry, bot, match);
    }

    static void requestSellTrashVisit(BotEntry entry, Character bot) {
        if (entry == null || bot == null || bot.getMap() == null) {
            return;
        }
        if (BotInventoryManager.collectSellTrashEquips(entry, bot).isEmpty()) {
            BotManager.getInstance().botReply(entry, "no trash equips worth selling");
            return;
        }

        entry.shopSellTrashPending = true;
        if (entry.shopVisitPending) {
            return;
        }

        NpcShopMatch match = findBestShop(bot, true);
        if (match == null) {
            entry.shopSellTrashPending = false;
            BotManager.getInstance().botReply(entry, "can't find a shop here");
            return;
        }

        BotManager.getInstance().botReply(entry, "ok gonna sell the junk");
        startShopVisit(entry, bot, match);
    }

    /**
     * Full shop trip: walk to the nearest shop NPC, then on arrival buy any gear upgrades / restock
     * ammo &amp; potions (the standard purchase sequence) AND sell off trash equips + junk ETC items.
     * Backs the "go shopping" command. Locked/reserved items are left untouched.
     */
    static void requestShopTrip(BotEntry entry, Character bot) {
        if (entry == null || bot == null || bot.getMap() == null) {
            return;
        }
        if (entry.shopVisitPending) {
            BotManager.getInstance().botReply(entry, "already on my way to a shop");
            return;
        }

        NpcShopMatch match = findBestShop(bot, true);
        if (match == null) {
            BotManager.getInstance().botReply(entry, "can't find a shop around here");
            return;
        }

        entry.shopSellTrashPending = true;
        entry.shopSellEtcPending = true;
        BotManager.getInstance().botReply(entry, "ok, heading to the shop");
        startShopVisit(entry, bot, match);
    }

    /**
     * Immediately sells the bot's whole ETC inventory to a shop NPC near the bot (no walking). Used
     * by the "sell etc" command — replies that it isn't near a shop when none is within reach.
     */
    static void sellEtcNearby(BotEntry entry, Character bot) {
        if (entry == null || bot == null || bot.getMap() == null) {
            return;
        }
        NPC npc = findNpcNear(bot, bot.getPosition());
        if (npc == null) {
            BotManager.getInstance().botReply(entry, "i'm not near a shop");
            return;
        }
        Shop shop = ShopFactory.getInstance().getShopForNPC(npc.getId());
        if (shop == null) {
            BotManager.getInstance().botReply(entry, "this shop's closed");
            return;
        }

        var etc = bot.getInventory(InventoryType.ETC);
        if (etc == null) {
            return;
        }
        int sold = 0;
        int mesoBefore = bot.getMeso();
        for (Item item : new ArrayList<>(etc.list())) {
            if (!BotInventoryManager.hasItem(bot, item) || BotInventoryManager.isItemLocked(item)) {
                continue;
            }
            shop.sell(bot.getClient(), InventoryType.ETC, item.getPosition(), item.getQuantity());
            if (!BotInventoryManager.hasItem(bot, item)) {
                sold++;
            }
        }
        int gained = bot.getMeso() - mesoBefore;
        if (sold == 0) {
            BotManager.getInstance().botReply(entry, "nothing in my etc worth selling");
        } else {
            BotManager.getInstance().botReply(entry, "sold " + sold + " etc item" + (sold != 1 ? "s" : "")
                    + " for " + GameConstants.numberWithCommas(gained) + " mesos");
        }
    }

    /**
     * If a shop NPC is within reach, immediately sells the full tab's non-saved items — skipping
     * slot-locked items, and (for EQUIP) reserved/useful equips. Returns true when a shop was
     * nearby, so the caller skips the walk-to-shop fallback. Used for proactive full-bag selling.
     */
    static boolean autoSellTabNearShop(BotEntry entry, Character bot, InventoryType type) {
        if (bot.getMap() == null) {
            return false;
        }
        NPC npc = findNpcNear(bot, bot.getPosition());
        if (npc == null) {
            return false;
        }
        Shop shop = ShopFactory.getInstance().getShopForNPC(npc.getId());
        if (shop == null) {
            return false;
        }
        List<Item> toSell;
        if (type == InventoryType.EQUIP) {
            toSell = BotInventoryManager.collectSellTrashEquips(entry, bot); // skips reserved/useful/locked
        } else {
            Inventory inv = bot.getInventory(type);
            toSell = new ArrayList<>();
            if (inv != null) {
                for (Item it : inv.list()) {
                    if (!BotInventoryManager.isItemLocked(it)) {
                        toSell.add(it);
                    }
                }
            }
        }
        int sold = 0;
        int mesoBefore = bot.getMeso();
        for (Item item : new ArrayList<>(toSell)) {
            if (!BotInventoryManager.hasItem(bot, item) || BotInventoryManager.isItemLocked(item)) {
                continue;
            }
            shop.sell(bot.getClient(), type, item.getPosition(), item.getQuantity());
            if (!BotInventoryManager.hasItem(bot, item)) {
                sold++;
            }
        }
        if (sold > 0) {
            int gained = bot.getMeso() - mesoBefore;
            BotManager.getInstance().botReply(entry, "bag was full — sold " + sold + " "
                    + type.name().toLowerCase() + " item" + (sold != 1 ? "s" : "") + " for "
                    + GameConstants.numberWithCommas(gained) + " mesos");
        }
        return true;
    }

    // Town storage-keeper NPC ids (every script under scripts/npc that opens account storage).
    private static final Set<Integer> STORAGE_NPC_IDS = Set.of(
            1002005, 1012009, 1022005, 1032006, 1052017, 1061008, 1091004, 1100000, 1200000,
            2010006, 2020004, 2041008, 2050004, 2060008, 2070000, 2080005, 2090000, 2093003,
            2100000, 2110000, 9030100, 9120009, 9201081, 9270042, 9270054);

    private static boolean storageNpcNear(Character bot) {
        for (MapObject obj : bot.getMap().getMapObjectsInRange(
                bot.getPosition(), SHOP_NPC_SEARCH_DIST * SHOP_NPC_SEARCH_DIST,
                Arrays.asList(MapObjectType.NPC))) {
            if (STORAGE_NPC_IDS.contains(((NPC) obj).getId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Deposits the given inventory tabs from the bot into the OWNER's account storage when the bot
     * is near a storage NPC. Mirrors the player deposit path (remove from inv → copy → store →
     * setUsedStorage). Used by the "stash" commands.
     */
    static void stashNearby(BotEntry entry, Character bot, List<InventoryType> tabs) {
        if (entry == null || bot == null || bot.getMap() == null) {
            return;
        }
        Character owner = entry.owner;
        if (owner == null || !owner.isLoggedinWorld()) {
            BotManager.getInstance().botReply(entry, "i don't know whose storage to use");
            return;
        }
        if (!storageNpcNear(bot)) {
            BotManager.getInstance().botReply(entry, "i'm not near a storage");
            return;
        }
        Storage storage = owner.getStorage();
        if (storage == null) {
            BotManager.getInstance().botReply(entry, "couldn't reach your storage");
            return;
        }

        int stashed = 0;
        boolean storageFull = false;
        for (InventoryType type : tabs) {
            Inventory inv = bot.getInventory(type);
            if (inv == null) {
                continue;
            }
            for (Item item : new ArrayList<>(inv.list())) {
                if (storage.isFull()) {
                    storageFull = true;
                    break;
                }
                if (!BotInventoryManager.hasItem(bot, item) || BotInventoryManager.isItemLocked(item)) {
                    continue;
                }
                short qty = item.getQuantity();
                InventoryManipulator.removeFromSlot(bot.getClient(), type, item.getPosition(), qty, false);
                Item copy = item.copy();
                copy.setQuantity(qty);
                KarmaManipulator.toggleKarmaFlagToUntradeable(copy);
                storage.store(copy);
                stashed++;
            }
            if (storageFull) {
                break;
            }
        }
        if (stashed > 0) {
            owner.setUsedStorage();
            BotManager.getInstance().botReply(entry, "stashed " + stashed + " item" + (stashed != 1 ? "s" : "")
                    + " in your storage" + (storageFull ? " (storage filled up)" : ""));
        } else {
            BotManager.getInstance().botReply(entry, storageFull ? "your storage is full" : "nothing to stash");
        }
    }

    private static void startShopVisit(BotEntry entry, Character bot, NpcShopMatch match) {
        entry.shopVisitPending = true;
        entry.shopNpcPos = match.npcPos;
        entry.shopTargetPos = pickShopApproachPoint(match.npcPos, entry, bot);
        entry.shopApproachDelayMs = (int) BotManager.randMs(0, SHOP_APPROACH_DELAY_MAX_MS);
        entry.shopVisitStartedAtMs = System.currentTimeMillis();
        entry.shopSequenceStartedAtMs = 0L;
    }

    static boolean tickShopVisit(BotEntry entry, Character bot) {
        if (!entry.shopVisitPending) {
            return false;
        }
        if (entry.shopNpcPos == null) {
            abortShop(entry, bot, "lost track of the shop, never mind");
            return false;
        }
        long now = System.currentTimeMillis();
        if (entry.shopVisitStartedAtMs > 0
                && !entry.shopSequenceActive
                && now - entry.shopVisitStartedAtMs > SHOP_VISIT_TIMEOUT_MS) {
            BotManager.getInstance().botSay(bot, "couldn't reach shop in time");
            clearShopState(entry);
            return false;
        }
        if (entry.shopSequenceActive
                && entry.shopSequenceStartedAtMs > 0
                && now - entry.shopSequenceStartedAtMs > SHOP_SEQUENCE_TIMEOUT_MS) {
            abortShop(entry, bot, "took too long at the shop, giving up");
            return false;
        }
        if (entry.shopApproachDelayMs > 0) {
            entry.shopApproachDelayMs = BotMovementManager.tickDown(entry.shopApproachDelayMs);
            return false;
        }

        Point botPos = bot.getPosition();
        Point target = entry.shopTargetPos != null ? entry.shopTargetPos : entry.shopNpcPos;
        boolean reachedApproach = manhattan(botPos, target) <= SHOP_ARRIVE_DIST;
        boolean stuckAtNpc = !entry.shopSequenceActive
                && !reachedApproach
                && isStuckNearNpc(entry, botPos, now);
        if (reachedApproach || stuckAtNpc) {
            if (!entry.shopSequenceActive) {
                entry.shopSequenceActive = true;
                entry.shopSequenceStartedAtMs = System.currentTimeMillis();
                BotManager.getInstance().botSay(bot, BotManager.randomReply(SHOPPING_MSGS));
                Point npcPos = entry.shopNpcPos;
                scheduleShopStep(entry, () -> executePurchases(entry, bot, npcPos));
            }
            return true;
        }

        return true;
    }

    private static boolean isStuckNearNpc(BotEntry entry, Point botPos, long now) {
        if (entry.shopNpcPos == null) {
            return false;
        }
        if (entry.shopStuckCheckPos == null) {
            entry.shopStuckCheckPos = new Point(botPos);
            entry.shopStuckCheckAtMs = now;
            return false;
        }
        if (botPos.distanceSq(entry.shopStuckCheckPos)
                > (long) SHOP_STUCK_MOVE_TOLERANCE_PX * SHOP_STUCK_MOVE_TOLERANCE_PX) {
            entry.shopStuckCheckPos.setLocation(botPos);
            entry.shopStuckCheckAtMs = now;
            return false;
        }
        if (now - entry.shopStuckCheckAtMs < SHOP_STUCK_FALLBACK_MS) {
            return false;
        }
        return manhattan(botPos, entry.shopNpcPos) <= SHOP_ARRIVE_DIST;
    }

    private static int manhattan(Point a, Point b) {
        return Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
    }

    private static NpcShopMatch findBestShop(Character bot, boolean allowAnyShop) {
        List<MapObject> objects = bot.getMap().getMapObjectsInRange(
                new Point(0, 0), Double.POSITIVE_INFINITY,
                Arrays.asList(MapObjectType.NPC));

        Point botPos = bot.getPosition();
        NpcShopMatch best = null;
        long bestDistSq = Long.MAX_VALUE;
        for (MapObject obj : objects) {
            NPC npc = (NPC) obj;
            Shop shop = ShopFactory.getInstance().getShopForNPC(npc.getId());
            if (shop == null || !shopSellsGoods(shop)) {
                // Skip non-merchants: storage keepers and quest NPCs (e.g. Inkwell) that have a
                // "shop" with no priced merchandise. The bot should only head to real vendors.
                continue;
            }
            // allowAnyShop: any vendor (sell trash / go shopping). Otherwise: only if it stocks
            // something the bot currently needs (auto-resupply on map change).
            if (!allowAnyShop && !shopHasAnythingNeeded(bot, shop)) {
                continue;
            }
            long distSq = (long) botPos.distanceSq(npc.getPosition());
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = new NpcShopMatch(npc, shop, npc.getPosition());
            }
        }
        return best;
    }

    /** True if the shop actually vendors merchandise (≥1 item priced above 0), i.e. a real merchant. */
    private static boolean shopSellsGoods(Shop shop) {
        for (ShopItem si : shop.getItems()) {
            if (si.getPrice() > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean shopHasAnythingNeeded(Character bot, Shop shop) {
        WeaponType wt = BotAttackExecutionProvider.getEquippedWeaponType(bot);
        if (needsFixedAmmoForShop(bot, shop, wt, ammoTriggerThreshold())) {
            return true;
        }
        if (needsRechargeForShop(bot, wt, ammoTriggerThreshold())) {
            return true;
        }
        int[] pots = BotPotionManager.countPotions(bot);
        if (pots[0] < BotManager.cfg.POT_LOW_WARN * 5 && findPotionItem(shop, bot, true) != null) {
            return true;
        }
        if (pots[1] < BotManager.cfg.POT_LOW_WARN * 5 && findPotionItem(shop, bot, false) != null) {
            return true;
        }
        return hasAffordableGearUpgrade(bot, shop);
    }

    /**
     * Equip items in {@code shop} that the autoEquip optimizer would put on the bot (upgrades over
     * its current gear). One entry per slot — the optimizer already picks the single best per slot.
     */
    private static List<ShopGearUpgrade> findGearUpgrades(Character bot, Shop shop) {
        List<ShopItem> items = shop.getItems();
        // Cheap pre-scan with the pure id→type helper: skip shops with no equips entirely so we
        // never touch ItemInformationProvider (and its DB-backed static init) for pot/ammo shops.
        boolean hasEquip = false;
        for (ShopItem si : items) {
            if (si.getPrice() > 0 && ItemConstants.getInventoryType(si.getItemId()) == InventoryType.EQUIP) {
                hasEquip = true;
                break;
            }
        }
        if (!hasEquip) {
            return List.of();
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        List<Equip> candidates = new ArrayList<>();
        Map<Integer, ShopGearUpgrade> byId = new HashMap<>();
        for (short i = 0; i < items.size(); i++) {
            ShopItem si = items.get(i);
            int id = si.getItemId();
            if (si.getPrice() <= 0 || byId.containsKey(id)) {
                continue;
            }
            if (ItemConstants.getInventoryType(id) != InventoryType.EQUIP || ii.isCash(id)) {
                continue;
            }
            if (!(ii.getEquipById(id) instanceof Equip equip)) {
                continue;
            }
            candidates.add(equip);
            byId.put(id, new ShopGearUpgrade(i, id, si.getPrice(), ii.getName(id)));
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<ShopGearUpgrade> upgrades = new ArrayList<>();
        for (int id : BotEquipManager.chosenUpgradeItemIds(bot, candidates)) {
            ShopGearUpgrade u = byId.get(id);
            if (u != null) {
                upgrades.add(u);
            }
        }
        return upgrades;
    }

    private static boolean hasAffordableGearUpgrade(Character bot, Shop shop) {
        for (ShopGearUpgrade u : findGearUpgrades(bot, shop)) {
            if (bot.getMeso() >= u.price()) {
                return true;
            }
        }
        return false;
    }

    private static PurchaseSequence buyGearUpgrades(PurchaseSequence sequence, Shop shop) {
        Character bot = sequence.bot();
        List<ShopGearUpgrade> upgrades = new ArrayList<>(findGearUpgrades(bot, shop));
        if (upgrades.isEmpty()) {
            return sequence;
        }
        upgrades.sort((a, b) -> Integer.compare(a.price(), b.price())); // buy the cheapest upgrades first
        boolean boughtAny = false;
        ShopGearUpgrade unaffordable = null;
        for (ShopGearUpgrade u : upgrades) {
            if (bot.getMeso() >= u.price()) {
                if (shop.buyDirect(bot, u.slot(), u.itemId(), (short) 1) == Shop.TransactionResult.SUCCESS) {
                    sequence.bought().add(u.name());
                    boughtAny = true;
                }
            } else if (unaffordable == null) {
                unaffordable = u;
            }
        }
        if (boughtAny) {
            // Force-equip the freshly bought gear right away.
            BotEquipManager.autoEquip(bot, sequence.entry().owner, null, true);
        }
        if (unaffordable != null) {
            maybeWindowShop(sequence.entry(), bot, unaffordable);
        }
        return sequence;
    }

    private static void maybeWindowShop(BotEntry entry, Character bot, ShopGearUpgrade u) {
        long now = System.currentTimeMillis();
        if (now < entry.nextWindowShopMsgMs) {
            return;
        }
        entry.nextWindowShopMsgMs = now + WINDOW_SHOP_MSG_CD_MS;
        BotManager.getInstance().botSay(bot, "went window shopping — a " + u.name() + " ("
                + GameConstants.numberWithCommas(u.price()) + " mesos) would be an upgrade but i can't afford it yet");
    }

    private static void executePurchases(BotEntry entry, Character bot, Point npcPos) {
        if (!isShopSequenceValid(entry, bot, npcPos)) {
            abortShop(entry, bot, "couldn't get to the shopkeeper, never mind");
            return;
        }

        WeaponType wt = BotAttackExecutionProvider.getEquippedWeaponType(bot);
        List<PurchaseAction> actions = new ArrayList<>();

        if (shouldRechargeWhileShopping(bot, wt)) {
            actions.add((sequence, shop) -> {
                BuyReport recharge = doRecharge(bot, shop, wt);
                if (recharge.quantity() > 0) {
                    int recharged = recharge.quantity();
                    String ammoName = wt == WeaponType.GUN ? "bullets" : "throwing stars";
                    sequence.bought().add("refilled " + recharged + " set"
                            + (recharged > 1 ? "s" : "") + " of my " + ammoName);
                }
                return sequence.withFirstShortfall(recharge);
            });
        }
        if (shouldBuyFixedAmmoWhileShopping(bot, wt)) {
            actions.add((sequence, shop) -> appendBuyReport(sequence, buyAmmo(bot, shop, wt), "ammo"));
        }
        actions.add((sequence, shop) -> {
            int[] pots = BotPotionManager.countPotions(bot);
            if (pots[0] < BotManager.cfg.POT_LOW_WARN * 5) {
                return appendBuyReport(sequence, buyPotions(bot, shop, true), "HP pots");
            }
            return sequence;
        });
        actions.add((sequence, shop) -> {
            int[] pots = BotPotionManager.countPotions(bot);
            if (pots[1] < BotManager.cfg.POT_LOW_WARN * 5) {
                return appendBuyReport(sequence, buyPotions(bot, shop, false), "MP pots");
            }
            return sequence;
        });
        actions.add(BotShopManager::buyGearUpgrades);

        runPurchaseStep(new PurchaseSequence(entry, bot, npcPos, actions, new ArrayList<>(), null), 0);
    }

    private static void runPurchaseStep(PurchaseSequence sequence, int index) {
        if (!isShopSequenceValid(sequence.entry(), sequence.bot(), sequence.npcPos())) {
            abortShop(sequence.entry(), sequence.bot(), "couldn't stay at the shop to buy, never mind");
            return;
        }
        if (index >= sequence.actions().size()) {
            if (sequence.entry().shopSellTrashPending) {
                startSellTrashSequence(sequence);
            } else {
                finishPurchaseSequence(sequence, true);
            }
            return;
        }

        NPC npc = findNpcNear(sequence.bot(), sequence.npcPos());
        if (npc == null) {
            abortShop(sequence.entry(), sequence.bot(), "the shopkeeper's gone, can't buy");
            return;
        }
        Shop shop = ShopFactory.getInstance().getShopForNPC(npc.getId());
        if (shop == null) {
            abortShop(sequence.entry(), sequence.bot(), "this shop's closed, can't buy");
            return;
        }

        PurchaseSequence next = sequence.actions().get(index).run(sequence, shop);
        scheduleShopStep(sequence.entry(), () -> runPurchaseStep(next, index + 1));
    }

    private static void finishPurchaseSequence(PurchaseSequence sequence, boolean announceIfEmpty) {
        if (!isShopSequenceValid(sequence.entry(), sequence.bot(), sequence.npcPos())) {
            abortShop(sequence.entry(), sequence.bot(), "couldn't finish up at the shop");
            return;
        }

        if (sequence.entry().shopSellEtcPending) {
            sequence.entry().shopSellEtcPending = false;
            int sold = sellEtcAtShop(sequence.bot(), sequence.npcPos());
            if (sold > 0) {
                BotManager.getInstance().botSay(sequence.bot(),
                        "sold " + sold + " etc item" + (sold != 1 ? "s" : ""));
            }
        }

        Runnable finish = () -> {
            if (!isShopSequenceValid(sequence.entry(), sequence.bot(), sequence.npcPos())) {
                abortShop(sequence.entry(), sequence.bot(), "couldn't finish up at the shop");
                return;
            }
            if (sequence.firstShortfall() != null) {
                BotManager.getInstance().botSay(sequence.bot(), buildShortfallMessage(sequence.firstShortfall()));
            } else if (announceIfEmpty && sequence.bought().isEmpty()) {
                // Never end a resupply visit silently: nothing was bought and nothing fell short.
                BotManager.getInstance().botSay(sequence.bot(), "turned out I didn't need anything here");
            }
            clearShopState(sequence.entry());
        };

        if (!sequence.bought().isEmpty()) {
            BotManager.getInstance().botSay(sequence.bot(), "bought " + String.join(", ", sequence.bought()));
            BotPotionManager.setupAutopotForBot(sequence.bot());
            BotCombatManager.tickAmmoCheck(sequence.entry(), sequence.bot());
            scheduleShopStep(sequence.entry(), finish);
            return;
        }

        finish.run();
    }

    /** Sells every non-locked ETC item to the shop at {@code npcPos}; returns how many sold. */
    private static int sellEtcAtShop(Character bot, Point npcPos) {
        NPC npc = findNpcNear(bot, npcPos);
        if (npc == null) {
            return 0;
        }
        Shop shop = ShopFactory.getInstance().getShopForNPC(npc.getId());
        if (shop == null) {
            return 0;
        }
        var etc = bot.getInventory(InventoryType.ETC);
        if (etc == null) {
            return 0;
        }
        int sold = 0;
        for (Item item : new ArrayList<>(etc.list())) {
            if (!BotInventoryManager.hasItem(bot, item) || BotInventoryManager.isItemLocked(item)) {
                continue;
            }
            shop.sell(bot.getClient(), InventoryType.ETC, item.getPosition(), item.getQuantity());
            if (!BotInventoryManager.hasItem(bot, item)) {
                sold++;
            }
        }
        return sold;
    }

    private static void startSellTrashSequence(PurchaseSequence sequence) {
        List<Item> items = BotInventoryManager.collectSellTrashEquips(sequence.entry(), sequence.bot());
        if (items.isEmpty()) {
            sequence.entry().shopSellTrashPending = false;
            BotManager.getInstance().botSay(sequence.bot(), "no trash equips worth selling");
            finishPurchaseSequence(sequence, false);
            return;
        }

        List<Item> plan = List.copyOf(items);
        scheduleShopStep(sequence.entry(), SELL_TRASH_STEP_DELAY_MS,
                () -> runSellTrashStep(
                        sequence.entry(),
                        sequence.bot(),
                        sequence.npcPos(),
                        0,
                        Collections.newSetFromMap(new IdentityHashMap<>()),
                        plan,
                        sequence.bought(),
                        sequence.firstShortfall()));
    }

    private static void runSellTrashStep(BotEntry entry, Character bot, Point npcPos, int soldCount, Set<Item> failedItems, List<Item> plan,
                                         List<String> bought, BuyReport firstShortfall) {
        if (!isShopSequenceValid(entry, bot, npcPos)) {
            abortShop(entry, bot, "couldn't stay at the shop to sell, never mind");
            return;
        }

        List<Item> items = plan.stream()
                .filter(item -> BotInventoryManager.hasItem(bot, item))
                .filter(item -> !failedItems.contains(item))
                .toList();
        if (items.isEmpty()) {
            entry.shopSellTrashPending = false;
            if (soldCount > 0) {
                BotManager.getInstance().botSay(bot, "sold " + soldCount + " trash equip" + (soldCount != 1 ? "s" : ""));
            }
            if (!failedItems.isEmpty()) {
                BotManager.getInstance().botSay(bot, buildSellTrashFailureMessage(failedItems.size()));
            } else if (soldCount == 0) {
                BotManager.getInstance().botSay(bot, "no trash equips worth selling");
            }
            finishPurchaseSequence(new PurchaseSequence(entry, bot, npcPos, List.of(), bought, firstShortfall), false);
            return;
        }

        Item item = items.get(0);
        if (!BotInventoryManager.hasItem(bot, item)) {
            scheduleShopStep(entry, SELL_TRASH_STEP_DELAY_MS,
                    () -> runSellTrashStep(entry, bot, npcPos, soldCount, failedItems, plan, bought, firstShortfall));
            return;
        }

        NPC npc = findNpcNear(bot, npcPos);
        if (npc == null) {
            abortShop(entry, bot, "the shopkeeper's gone, can't sell");
            return;
        }
        Shop shop = ShopFactory.getInstance().getShopForNPC(npc.getId());
        if (shop == null) {
            abortShop(entry, bot, "this shop's closed, can't sell");
            return;
        }

        shop.sell(bot.getClient(), InventoryType.EQUIP, item.getPosition(), (short) 1);
        if (BotInventoryManager.hasItem(bot, item)) {
            failedItems.add(item);
            scheduleShopStep(entry, SELL_TRASH_STEP_DELAY_MS,
                    () -> runSellTrashStep(entry, bot, npcPos, soldCount, failedItems, plan, bought, firstShortfall));
            return;
        }

        int nextSoldCount = soldCount + 1;
        scheduleShopStep(entry, SELL_TRASH_STEP_DELAY_MS,
                () -> runSellTrashStep(entry, bot, npcPos, nextSoldCount, failedItems, plan, bought, firstShortfall));
    }

    private static String buildSellTrashFailureMessage(int failedCount) {
        String items = failedCount + " item" + (failedCount != 1 ? "s" : "");
        return "unable to sell " + items + ", tell me to drop them if you want them gone";
    }

    private static PurchaseSequence appendBuyReport(PurchaseSequence sequence, BuyReport report, String fallbackName) {
        if (report.quantity() > 0) {
            sequence.bought().add(report.quantity() + " " + resolveItemName(report.itemId(), fallbackName));
        }
        return sequence.withFirstShortfall(report);
    }

    private static boolean needsAmmo(Character bot, WeaponType wt) {
        if (wt == null) {
            return false;
        }
        return wt == WeaponType.BOW || wt == WeaponType.CROSSBOW;
    }

    private static int ammoTriggerThreshold() {
        return BotCombatManager.cfg.AMMO_LOW_WARN * AMMO_TRIGGER_THRESHOLD;
    }

    private static int ammoTargetThreshold() {
        return BotCombatManager.cfg.AMMO_LOW_WARN * AMMO_TARGET_THRESHOLD;
    }

    private static boolean needsFixedAmmoForShop(Character bot, Shop shop, WeaponType wt, int threshold) {
        if (!needsAmmo(bot, wt) || BotCombatManager.countAmmo(bot, wt) >= threshold) {
            return false;
        }
        return shop == null || findAmmoItem(shop, wt) != null;
    }

    // True when the bot's BEST rechargeable ammo (highest-attack star/bullet matching the
    // weapon) is below the threshold AND has a partial stack that can actually be refilled.
    // Keying on the best item means a pile of weaker stars can't mask a depleted good stack,
    // and the partial-stack check stops pointless trips/silent no-ops when nothing is refillable.
    private static boolean needsRechargeForShop(Character bot, WeaponType wt, int threshold) {
        if (!isRechargeWeaponType(wt)) {
            return false;
        }
        int bestId = bestRechargeAmmoId(bot, wt);
        if (bestId < 0) {
            return false;
        }
        short slotMax = ammoSlotMax.slotMax(bot, bestId);
        int count = 0;
        boolean refillable = false;
        for (Item item : bot.getInventory(InventoryType.USE).list()) {
            if (item.getItemId() != bestId) {
                continue;
            }
            count += item.getQuantity();
            if (item.getQuantity() < slotMax) {
                refillable = true;
            }
        }
        return refillable && count < threshold;
    }

    static boolean shouldRechargeWhileShopping(Character bot, WeaponType wt) {
        return needsRechargeForShop(bot, wt, ammoTriggerThreshold());
    }

    static boolean shouldBuyFixedAmmoWhileShopping(Character bot, WeaponType wt) {
        return needsAmmo(bot, wt) && BotCombatManager.countAmmo(bot, wt) < ammoTargetThreshold();
    }

    private static boolean isRechargeWeaponType(WeaponType wt) {
        return wt == WeaponType.CLAW || wt == WeaponType.GUN;
    }

    private static ShopSlotItem findAmmoItem(Shop shop, WeaponType wt) {
        List<ShopItem> items = shop.getItems();
        ShopSlotItem best = null;
        for (int i = 0; i < items.size(); i++) {
            ShopItem si = items.get(i);
            if (si.getPrice() <= 0) {
                continue;
            }
            int id = si.getItemId();
            boolean matches = switch (wt) {
                case BOW -> ItemConstants.isArrowForBow(id);
                case CROSSBOW -> ItemConstants.isArrowForCrossBow(id);
                default -> false;
            };
            if (matches && (best == null || si.getPrice() < best.shopItem.getPrice())) {
                best = new ShopSlotItem((short) i, si);
            }
        }
        return best;
    }

    private static BuyReport buyAmmo(Character bot, Shop shop, WeaponType wt) {
        ShopSlotItem ammo = findAmmoItem(shop, wt);
        if (ammo == null) {
            return new BuyReport(0, 0, 0, ShortfallReason.NONE);
        }

        int target = ammoTargetThreshold();
        int current = BotCombatManager.countAmmo(bot, wt);
        return buyFixedCostItem(bot, shop, ammo, Math.max(0, target - current), 1000);
    }

    private static int bestRechargeAmmoId(Character bot, WeaponType wt) {
        int bestId = -1;
        int bestAtk = -1;
        for (Item item : bot.getInventory(InventoryType.USE).list()) {
            int id = item.getItemId();
            if (!ItemConstants.isRechargeable(id) || !matchesRechargeWeapon(id, wt)) {
                continue;
            }
            int atk = projectileWatk.applyAsInt(id);
            if (atk > bestAtk) {
                bestAtk = atk;
                bestId = id;
            }
        }
        return bestId;
    }

    private static boolean matchesRechargeWeapon(int itemId, WeaponType wt) {
        return switch (wt) {
            case CLAW -> ItemConstants.isThrowingStar(itemId);
            case GUN -> ItemConstants.isBullet(itemId);
            default -> false;
        };
    }

    private static BuyReport doRecharge(Character bot, Shop shop, WeaponType wt) {
        // Only recharge ammo matching the equipped weapon (claw->stars, gun->bullets) and only
        // the best stacks by attack: recharging off-weapon or low-tier leftovers just wastes meso,
        // and an off-weapon failure must never short-circuit the real ammo refill.
        List<Item> refillable = new ArrayList<>();
        for (Item item : bot.getInventory(InventoryType.USE).list()) {
            int id = item.getItemId();
            if (!ItemConstants.isRechargeable(id) || !matchesRechargeWeapon(id, wt)) {
                continue;
            }
            if (item.getQuantity() >= ammoSlotMax.slotMax(bot, id)) {
                continue;
            }
            refillable.add(item);
        }
        refillable.sort((a, b) -> Integer.compare(
                projectileWatk.applyAsInt(b.getItemId()), projectileWatk.applyAsInt(a.getItemId())));

        int recharged = 0;
        int attempted = 0;
        int shortfallItemId = 0;
        ShortfallReason reason = ShortfallReason.NONE;
        for (Item item : refillable) {
            if (recharged >= RECHARGE_MAX_SETS) {
                break;
            }
            Shop.TransactionResult result = shop.rechargeDirect(bot, item.getPosition());
            if (result == Shop.TransactionResult.SUCCESS) {
                recharged++;
                attempted++;
                continue;
            }
            attempted++;
            shortfallItemId = item.getItemId();
            reason = switch (result) {
                case NOT_ENOUGH_MESO -> ShortfallReason.NO_MESO;
                case NO_SPACE -> ShortfallReason.NO_SPACE;
                default -> ShortfallReason.OTHER;
            };
            break;
        }
        return new BuyReport(shortfallItemId, recharged, attempted, reason);
    }

    private static ShopSlotItem findPotionItem(Shop shop, Character bot, boolean forHp) {
        List<ShopItem> items = shop.getItems();
        int maxStat = forHp ? bot.getCurrentMaxHp() : bot.getCurrentMaxMp();
        int minRecover = (int) (maxStat * 0.10);
        int maxRecover = (int) (maxStat * 0.50);

        ShopSlotItem inBand = null;     // cheapest potion within [minRecover, maxRecover]
        ShopSlotItem bestTooLow = null; // highest-recover potion below minRecover
        int bestTooLowRecover = -1;
        ShopSlotItem bestTooHigh = null; // lowest-recover potion above maxRecover
        int bestTooHighRecover = Integer.MAX_VALUE;
        for (int i = 0; i < items.size(); i++) {
            ShopItem si = items.get(i);
            if (si.getPrice() <= 0) {
                continue;
            }
            int id = si.getItemId();
            if (!BotInventoryManager.isRecoveryPotion(id)) {
                continue;
            }

            StatEffect fx = BotInventoryManager.itemEffect(id);
            if (fx == null) {
                continue;
            }
            if (forHp && fx.getHpRate() > 0) {
                continue;
            }
            if (!forHp && fx.getMpRate() > 0) {
                continue;
            }

            int recover = forHp ? fx.getHp() : fx.getMp();
            if (recover <= 0) {
                continue;
            }

            if (recover < minRecover) {
                if (recover > bestTooLowRecover) {
                    bestTooLowRecover = recover;
                    bestTooLow = new ShopSlotItem((short) i, si);
                }
            } else if (recover > maxRecover) {
                if (recover < bestTooHighRecover) {
                    bestTooHighRecover = recover;
                    bestTooHigh = new ShopSlotItem((short) i, si);
                }
            } else if (inBand == null || si.getPrice() < inBand.shopItem.getPrice()) {
                inBand = new ShopSlotItem((short) i, si);
            }
        }
        // Prefer a potion sized for this bot; otherwise fall back to the closest available:
        // the strongest that's still too weak, or failing that the weakest that's too strong.
        if (inBand != null) {
            return inBand;
        }
        return bestTooLow != null ? bestTooLow : bestTooHigh;
    }

    private static BuyReport buyPotions(Character bot, Shop shop, boolean forHp) {
        ShopSlotItem pot = findPotionItem(shop, bot, forHp);
        if (pot == null) {
            return new BuyReport(0, 0, 0, ShortfallReason.NONE);
        }

        int target = BotManager.cfg.POT_LOW_WARN * POT_TARGET_THRESHOLD;
        int[] pots = BotPotionManager.countPotions(bot);
        int current = forHp ? pots[0] : pots[1];
        return buyFixedCostItem(bot, shop, pot, Math.max(0, target - current), 100);
    }

    private static BuyReport buyFixedCostItem(Character bot, Shop shop, ShopSlotItem item, int desiredQuantity, int batchSize) {
        if (item == null || desiredQuantity <= 0) {
            return new BuyReport(0, 0, 0, ShortfallReason.NONE);
        }

        int totalBought = 0;
        ShortfallReason reason = ShortfallReason.NONE;
        int price = item.shopItem.getPrice();

        while (totalBought < desiredQuantity) {
            int remaining = desiredQuantity - totalBought;
            short qty = (short) Math.min(remaining, batchSize);
            Shop.TransactionResult result = shop.buyDirect(bot, item.slot, item.shopItem.getItemId(), qty);
            if (result == Shop.TransactionResult.SUCCESS) {
                totalBought += qty;
                continue;
            }
            if (result == Shop.TransactionResult.NOT_ENOUGH_MESO) {
                reason = ShortfallReason.NO_MESO;
                int affordable = price > 0 ? Math.min(remaining, bot.getMeso() / price) : 0;
                if (affordable > 0) {
                    Shop.TransactionResult partial = shop.buyDirect(bot, item.slot, item.shopItem.getItemId(), (short) affordable);
                    if (partial == Shop.TransactionResult.SUCCESS) {
                        totalBought += affordable;
                    } else if (partial == Shop.TransactionResult.NO_SPACE) {
                        reason = ShortfallReason.NO_SPACE;
                    }
                }
            } else if (result == Shop.TransactionResult.NO_SPACE) {
                reason = ShortfallReason.NO_SPACE;
            } else {
                reason = ShortfallReason.OTHER;
            }
            break;
        }

        return new BuyReport(item.shopItem.getItemId(), totalBought, desiredQuantity, reason);
    }

    private static String buildShortfallMessage(BuyReport report) {
        String itemName = resolveItemName(report.itemId(), "item");
        String got = GameConstants.numberWithCommas(report.quantity());
        String want = GameConstants.numberWithCommas(report.requestedQuantity());
        return switch (report.reason()) {
            case NO_SPACE -> report.quantity() <= 0
                    ? "no room in my bag for " + itemName
                    : "only fit " + got + " " + itemName + " out of " + want + " — bag's full";
            case OTHER -> "shop wouldn't sell me " + itemName;
            case NO_MESO, NONE -> report.quantity() <= 0
                    ? "couldn't afford any " + itemName + " this trip"
                    : "could only afford " + got + " " + itemName + " out of " + want;
        };
    }

    private static String resolveItemName(int itemId, String fallbackName) {
        String name = ItemInformationProvider.getInstance().getName(itemId);
        return name != null ? name : fallbackName;
    }

    private static boolean isShopSequenceValid(BotEntry entry, Character bot, Point npcPos) {
        if (!entry.shopVisitPending || !entry.shopSequenceActive || npcPos == null || bot.getMap() == null) {
            return false;
        }
        // Accept proximity to the approach point OR the NPC itself: the sequence can start
        // via the stuck-at-NPC fallback, where the bot is near the NPC but not the approach point.
        Point pos = bot.getPosition();
        Point approach = entry.shopTargetPos != null ? entry.shopTargetPos : npcPos;
        boolean atShop = manhattan(pos, approach) <= SHOP_ARRIVE_DIST
                || manhattan(pos, npcPos) <= SHOP_ARRIVE_DIST;
        return atShop && findNpcNear(bot, npcPos) != null;
    }

    static void cancelShopVisit(BotEntry entry) {
        clearShopState(entry);
    }

    // Abort the shop visit and tell the owner why. Player commands cancel via
    // cancelShopVisit (which clears shopVisitPending) and scheduleShopStep guards on
    // that flag, so a cleared flag here means a concurrent player cancel — stay silent.
    private static void abortShop(BotEntry entry, Character bot, String reason) {
        if (entry.shopVisitPending) {
            BotManager.getInstance().botSay(bot, reason);
        }
        clearShopState(entry);
    }

    private static void clearShopState(BotEntry entry) {
        entry.shopVisitPending = false;
        entry.shopNpcPos = null;
        entry.shopTargetPos = null;
        entry.shopApproachDelayMs = 0;
        entry.shopSequenceActive = false;
        entry.shopVisitStartedAtMs = 0L;
        entry.shopSequenceStartedAtMs = 0L;
        entry.shopSellTrashPending = false;
        entry.shopSellEtcPending = false;
        entry.shopStuckCheckPos = null;
        entry.shopStuckCheckAtMs = 0L;
    }

    private static long stepDelayMs() {
        return BotManager.randMs(SHOP_STEP_DELAY_MIN_MS, SHOP_STEP_DELAY_MAX_MS);
    }

    private static void scheduleShopStep(BotEntry entry, Runnable step) {
        scheduleShopStep(entry, stepDelayMs(), step);
    }

    private static void scheduleShopStep(BotEntry entry, long delayMs, Runnable step) {
        BotManager.after(delayMs, () -> {
            if (!entry.shopVisitPending) {
                return;
            }
            try {
                step.run();
            } catch (RuntimeException exception) {
                abortShop(entry, entry.bot, "ran into a problem at the shop");
                throw exception;
            }
        });
    }

    private static Point pickShopApproachPoint(Point npcPos, BotEntry entry, Character bot) {
        var footholds = bot.getMap().getFootholds();
        if (footholds == null) {
            return npcPos;
        }
        List<Point> candidates = new ArrayList<>();
        for (Foothold fh : footholds.getAllFootholds()) {
            int fx1 = fh.getX1(), fy1 = fh.getY1();
            int fx2 = fh.getX2(), fy2 = fh.getY2();
            if (fx1 == fx2) {
                continue; // wall foothold
            }
            int xMin = Math.min(fx1, fx2);
            int xMax = Math.max(fx1, fx2);
            int step = Math.max(1, (xMax - xMin) / 20);
            for (int x = xMin; x <= xMax; x += step) {
                double t = (double) (x - fx1) / (fx2 - fx1);
                int y = (int) (fy1 + t * (fy2 - fy1));
                if (Math.abs(x - npcPos.x) + Math.abs(y - npcPos.y) <= SHOP_MANHATTAN_RADIUS) {
                    candidates.add(new Point(x, y));
                }
            }
        }
        if (candidates.isEmpty()) {
            return npcPos;
        }
        BotMovementProfile profile = entry.movementProfile != null
                ? entry.movementProfile : BotMovementProfile.fromCharacter(bot);
        BotNavigationGraph graph = BotNavigationGraphProvider.peekGraph(bot.getMap(), profile);
        if (graph == null) {
            graph = BotNavigationGraphProvider.peekClosestGraph(bot.getMap(), profile);
        }
        if (graph != null) {
            Point botPos = bot.getPosition();
            int startRegionId = BotNavigationManager.resolveCurrentRegionId(graph, entry, bot.getMap(), botPos);
            if (startRegionId >= 0) {
                List<Point> reachable = new ArrayList<>();
                for (Point candidate : candidates) {
                    int targetRegionId = BotNavigationManager.resolveTargetRegionId(
                            graph, entry, bot.getMap(), candidate);
                    if (targetRegionId < 0) continue;
                    if (startRegionId == targetRegionId
                            || !BotNavigationManager.findPath(graph, bot.getMap(), botPos,
                                    startRegionId, targetRegionId, candidate).isEmpty()) {
                        reachable.add(candidate);
                    }
                }
                if (!reachable.isEmpty()) {
                    candidates = reachable;
                }
            }
        }
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    private static NPC findNpcNear(Character bot, Point pos) {
        NPC nearest = null;
        long bestDistSq = Long.MAX_VALUE;
        for (MapObject obj : bot.getMap().getMapObjectsInRange(
                pos, SHOP_NPC_SEARCH_DIST * SHOP_NPC_SEARCH_DIST,
                Arrays.asList(MapObjectType.NPC))) {
            NPC npc = (NPC) obj;
            Shop shop = ShopFactory.getInstance().getShopForNPC(npc.getId());
            if (shop == null || !shopSellsGoods(shop)) {
                continue;   // only real merchants, never storage keepers / quest NPCs
            }
            long distSq = (long) pos.distanceSq(npc.getPosition());
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                nearest = npc;
            }
        }
        return nearest;
    }
}
