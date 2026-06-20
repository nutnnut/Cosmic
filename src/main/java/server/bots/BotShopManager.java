package server.bots;

import client.Character;
import client.Job;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import constants.game.GameConstants;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import server.Shop;
import server.ShopFactory;
import server.ShopItem;
import server.StatEffect;
import server.life.NPC;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;

final class BotShopManager {
    private static final Logger log = LoggerFactory.getLogger(BotShopManager.class);

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
    // Close-enough fallback when the walk can't land next to the keeper (fenced booths):
    // matches the cab-NPC interaction radius in BotTravelManager.
    private static final int SHOP_FALLBACK_DIST = 500;
    private static final int SHOP_NPC_SEARCH_DIST = 601;
    private static final int SHOP_APPROACH_DELAY_MAX_MS = 5001;
    private static final int SHOP_STEP_DELAY_MIN_MS = 2000;
    private static final int SHOP_STEP_DELAY_MAX_MS = 4001;
    private static final int SELL_TRASH_STEP_DELAY_MS = 500;
    private static final long SHOP_VISIT_TIMEOUT_MS = 30_000L;
    // Idle gate, not a total cap: every executed shop step refreshes shopSequenceStartedAtMs,
    // so long multi-item hauls keep going — only a sequence whose next step never fires aborts.
    private static final long SHOP_SEQUENCE_IDLE_TIMEOUT_MS = 45_000L;
    private static final long SHOP_STUCK_FALLBACK_MS = 1000L;
    private static final int SHOP_STUCK_MOVE_TOLERANCE_PX = 2;
    private static final int POT_TRIGGER_THRESHOLD = 4; // 80% of target (5) for early trigger
    private static final int POT_TARGET_THRESHOLD = 5; // full target when buying at shop
    private static final int AMMO_TRIGGER_THRESHOLD = 8;
    private static final int AMMO_TARGET_THRESHOLD = 10; // full target when buying at shop
    private static final int RETURN_SCROLL_NEAREST_TOWN = 2030000;
    private static final int RETURN_SCROLL_TARGET_QTY = 10;
    private static final int RECHARGE_MAX_SETS = 10; // cap recharge to the best N own-type stacks
    private static final int AUTO_SELL_FREE_SLOT_THRESHOLD = 2; // bag tab "cramped" when this few slots left
    private static final int USE_HEALTHY_FREE_SLOTS = 6; // cramped USE escalation sells down to this many free slots

    static class Config {
        // Debug/verify aid: after a sell-trash visit, list the USE/ETC items that were sold so
        // the owner can spot a valuable being misclassified. Equips are excluded (well tested).
        public boolean REPORT_SOLD_USE_ETC = true;
    }
    static Config cfg = new Config();

    private BotShopManager() {}

    private record NpcShopMatch(NPC npc, Shop shop, Point npcPos) {}

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

        boolean wantsSellTrash = shouldAutoSellTrash(entry, bot);
        NpcShopMatch match = findBestShop(bot, wantsSellTrash);
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
        if (!needsRecharge && !needsAmmoForShop && !needsHpPots && !needsMpPots && !wantsSellTrash) {
            return;
        }

        if (wantsSellTrash) {
            entry.shopSellTrashPending = true;
        }

        long distSq = (long) bot.getPosition().distanceSq(match.npcPos);
        if (distSq > 1000L * 1000L) {
            BotManager.getInstance().botSay(bot, BotManager.randomReply(RESUPPLY_MSGS));
        }

        startShopVisit(entry, bot, match);
    }

    /** Bags filling up while farming: unload junk at a shop without being told — but only when a
     *  cramped tab actually holds sellable trash (selling can't free slots otherwise). */
    static boolean shouldAutoSellTrash(BotEntry entry, Character bot) {
        boolean equipCramped = isCramped(bot, InventoryType.EQUIP);
        boolean useCramped = isCramped(bot, InventoryType.USE);
        boolean etcCramped = isCramped(bot, InventoryType.ETC);
        if (!equipCramped && !useCramped && !etcCramped) {
            return false;
        }
        if (equipCramped && !BotInventoryManager.collectSellTrashEquips(entry, bot).isEmpty()) {
            return true;
        }
        if (useCramped && (!BotInventoryManager.collectSellTrashUseItems(bot).isEmpty()
                || BotInventoryManager.crampedUseSalesAvailable(bot))) {
            return true;
        }
        return etcCramped && !BotInventoryManager.collectSellTrashEtcItems(bot).isEmpty();
    }

    static boolean isCramped(Character bot, InventoryType type) {
        var inv = bot.getInventory(type);
        return inv != null && inv.getNumFreeSlot() <= AUTO_SELL_FREE_SLOT_THRESHOLD;
    }

    private static final long SELL_BLOCK_LOG_THROTTLE_MS = 60_000L;

    /**
     * Diagnostic for "the bag is full but the bot never sells". Emitted (throttled per bot) whenever
     * a tab is cramped, dumping every input the auto-sell decision depends on so a single live log
     * line is conclusive instead of leaving us to guess at unobservable runtime state. Unlike the
     * {@code bot-errand:} log (which only fires once the trigger branch is reached), this fires even
     * when the bot is {@code !grinding} or {@code shouldAutoSellTrash} is false — the exact cases that
     * would otherwise be silent. Grep {@code bot-sellblock}.
     */
    static void logSellBlockIfCramped(BotEntry entry, Character bot) {
        if (entry == null || bot == null) {
            return;
        }
        boolean equipCramped = isCramped(bot, InventoryType.EQUIP);
        boolean useCramped = isCramped(bot, InventoryType.USE);
        boolean etcCramped = isCramped(bot, InventoryType.ETC);
        if (!equipCramped && !useCramped && !etcCramped) {
            return; // nothing cramped — no stuck bag to explain
        }
        long now = System.currentTimeMillis();
        if (now < entry.sellBlockLogAtMs + SELL_BLOCK_LOG_THROTTLE_MS) {
            return;
        }
        entry.sellBlockLogAtMs = now;
        int eqTrash = -1;
        int useTrash = -1;
        int etcTrash = -1;
        boolean shouldSell = false;
        try {
            eqTrash = BotInventoryManager.collectSellTrashEquips(entry, bot).size();
            useTrash = BotInventoryManager.collectSellTrashUseItems(bot).size();
            etcTrash = BotInventoryManager.collectSellTrashEtcItems(bot).size();
            shouldSell = shouldAutoSellTrash(entry, bot);
        } catch (RuntimeException ignored) {
            // diagnostic must never throw into the grind tick
        }
        log.info("bot-sellblock: {} cramped[eq={} use={} etc={}] sellableTrash[eq={} use={} etc={}] "
                        + "shouldSell={} grinding={} following={} autopilot={} shopPending={} errandMap={}",
                bot.getName(), equipCramped, useCramped, etcCramped, eqTrash, useTrash, etcTrash,
                shouldSell, entry.grinding, entry.following, BotAutopilotManager.isActive(entry),
                entry.shopVisitPending, entry.autopilotErrandMapId);
    }

    static void requestSellTrashVisit(BotEntry entry, Character bot) {
        if (entry == null || bot == null || bot.getMap() == null) {
            return;
        }
        boolean nothingToSell = BotInventoryManager.collectSellTrashItems(entry, bot).isEmpty()
                && !(isCramped(bot, InventoryType.USE) && BotInventoryManager.crampedUseSalesAvailable(bot));
        if (nothingToSell) {
            BotManager.getInstance().botReply(entry, "no junk worth selling");
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

    private static void startShopVisit(BotEntry entry, Character bot, NpcShopMatch match) {
        entry.shopVisitPending = true;
        entry.shopNpcPos = match.npcPos;
        entry.shopTargetPos = BotTravelManager.pickReachableApproachPoint(entry, bot, match.npcPos, SHOP_MANHATTAN_RADIUS);
        entry.shopTargetGraphChecked = approachGraphReady(entry, bot);
        entry.shopApproachDelayMs = (int) BotManager.randMs(0, SHOP_APPROACH_DELAY_MAX_MS);
        entry.shopVisitStartedAtMs = System.currentTimeMillis();
        entry.shopSequenceStartedAtMs = 0L;
    }

    private static boolean approachGraphReady(BotEntry entry, Character bot) {
        BotMovementProfile profile = entry.movementProfile != null
                ? entry.movementProfile : BotMovementProfile.fromCharacter(bot);
        return BotNavigationGraphProvider.peekBestGraph(bot.getMap(), profile) != null;
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
            // Some shopkeepers sit in fenced booths the approach walk can't enter (e.g. the
            // Ellinia grocer) — if the bot got close, shop from where it stands instead of
            // giving up within sight of the counter.
            if (manhattan(bot.getPosition(), entry.shopNpcPos) <= SHOP_FALLBACK_DIST) {
                startShopSequence(entry, bot);
                return true;
            }
            BotManager.getInstance().botSay(bot, "couldn't reach shop in time");
            clearShopState(entry);
            return false;
        }
        if (entry.shopSequenceActive
                && entry.shopSequenceStartedAtMs > 0
                && now - entry.shopSequenceStartedAtMs > SHOP_SEQUENCE_IDLE_TIMEOUT_MS) {
            abortShop(entry, bot, "took too long at the shop, giving up");
            return false;
        }
        if (entry.shopApproachDelayMs > 0) {
            entry.shopApproachDelayMs = BotMovementManager.tickDown(entry.shopApproachDelayMs);
            return false;
        }
        if (!entry.shopSequenceActive && !entry.shopTargetGraphChecked && approachGraphReady(entry, bot)) {
            // The original pick raced the map-change graph warmup and couldn't filter for
            // reachability — re-pick now that pathability is known.
            entry.shopTargetPos = BotTravelManager.pickReachableApproachPoint(entry, bot, entry.shopNpcPos, SHOP_MANHATTAN_RADIUS);
            entry.shopTargetGraphChecked = true;
        }

        Point botPos = bot.getPosition();
        Point target = entry.shopTargetPos != null ? entry.shopTargetPos : entry.shopNpcPos;
        boolean reachedApproach = manhattan(botPos, target) <= SHOP_ARRIVE_DIST;
        boolean stuckAtNpc = !entry.shopSequenceActive
                && !reachedApproach
                && isStuckNearNpc(entry, botPos, now);
        if (reachedApproach || stuckAtNpc) {
            if (!entry.shopSequenceActive) {
                startShopSequence(entry, bot);
            }
            return true;
        }

        return true;
    }

    private static void startShopSequence(BotEntry entry, Character bot) {
        entry.shopSequenceActive = true;
        entry.shopSequenceStartedAtMs = System.currentTimeMillis();
        BotManager.getInstance().botSay(bot, BotManager.randomReply(SHOPPING_MSGS));
        Point npcPos = entry.shopNpcPos;
        scheduleShopStep(entry, () -> executePurchases(entry, bot, npcPos));
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
        return manhattan(botPos, entry.shopNpcPos) <= SHOP_FALLBACK_DIST;
    }

    private static int manhattan(Point a, Point b) {
        return Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
    }

    /** On-arrival visit decision: is the shop in front of the bot worth stopping at? Sell-trash =>
     *  any shop; otherwise it must stock something the bot actually needs right now (pots OR ammo) —
     *  this is the granular, bag-state-aware check, distinct from the looser potion-only filter the
     *  cross-map errand destination search uses. */
    private static NpcShopMatch findBestShop(Character bot, boolean allowAnyShop) {
        return findBestShop(bot.getMap(), allowAnyShop ? shop -> true : shop -> shopHasAnythingNeeded(bot, shop));
    }

    /** First shop NPC on {@code map} whose shop the {@code accept} predicate matches. Generalized
     *  over an arbitrary map and criterion so both the on-arrival visit decision and the cross-map
     *  nearest-shop search share one NPC scan. */
    private static NpcShopMatch findBestShop(MapleMap map, Predicate<Shop> accept) {
        if (map == null) {
            return null;
        }
        List<MapObject> objects = map.getMapObjectsInRange(
                new Point(0, 0), Double.POSITIVE_INFINITY,
                Arrays.asList(MapObjectType.NPC));

        for (MapObject obj : objects) {
            NPC npc = (NPC) obj;
            if (!npc.hasShop()) {
                continue;
            }
            Shop shop = ShopFactory.getInstance().getShopForNPC(npc.getId());
            if (shop == null) {
                continue;
            }
            if (accept.test(shop)) {
                return new NpcShopMatch(npc, shop, npc.getPosition());
            }
        }
        return null;
    }

    // How far (portal hops) the bot will look for a shop when its own map has none. Towns and their
    // shop sub-maps (e.g. Orbis 200000000 -> department store 200000002) are 1-2 hops apart, so a
    // modest cap finds them while bounding the map-load cost of the search.
    private static final int SHOP_SEARCH_MAX_HOPS = 6;
    private static final int NO_SHOP_MAP = Integer.MIN_VALUE;
    // Cache of "nearest reachable map with a matching shop" per source map, one map per criterion.
    // Both the world graph and shop catalogs are static, and neither criterion depends on live bag
    // state (any shop for a sell trip; a potion-stocking shop for a supply run), so these answers
    // never change — compute each (map-loading) flood once per (source map, criterion).
    private static final Map<Integer, Integer> nearestAnyShopMapCache = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> nearestPotionShopMapCache = new ConcurrentHashMap<>();

    /**
     * The nearest reachable map (current map first, then by portal-hop distance) that has a shop the
     * bot needs, or {@code null} if none within {@link #SHOP_SEARCH_MAX_HOPS}. This is the fix for a
     * bot stranded in a town hub whose own map has no shop NPC (the shop sits one portal away): rather
     * than only ever heading to {@code getReturnMap()} ("nearest town"), the bot seeks the nearest
     * actual shop that fits the criteria. Both criteria — any shop (sell trip) and potion-stocking
     * (supply run) — are bag-state-independent, so each is cached per source map.
     */
    static Integer findNearestShopMap(Character bot, boolean allowAnyShop) {
        if (bot == null || bot.getMap() == null || bot.getClient() == null) {
            return null;
        }
        int from = bot.getMapId();
        // Sell-trash trip => any shop; supply run => a potion-stocking shop (also carries ammo).
        Map<Integer, Integer> cache = allowAnyShop ? nearestAnyShopMapCache : nearestPotionShopMapCache;
        Integer cached = cache.get(from);
        if (cached != null) {
            return cached == NO_SHOP_MAP ? null : cached;
        }
        Predicate<Shop> accept = allowAnyShop ? shop -> true : BotShopManager::shopSellsAnyPotion;
        Integer found = null;
        try {
            var factory = bot.getClient().getChannelServer().getMapFactory();
            Set<Integer> seen = new HashSet<>();
            // Nearest-first: widen the reachable radius one hop at a time and only probe maps that newly
            // entered range, so the first shop hit is the closest. Early-return keeps map loading minimal.
            outer:
            for (int hops = 0; hops <= SHOP_SEARCH_MAX_HOPS; hops++) {
                for (int mapId : BotWorldGraph.reachableWithin(from, hops)) {
                    if (!seen.add(mapId)) {
                        continue;
                    }
                    if (findBestShop(factory.getMap(mapId), accept) != null) {
                        found = mapId;
                        break outer;
                    }
                }
            }
        } catch (RuntimeException ex) {
            return null; // best-effort: world graph / map load unavailable -> caller falls back to return map
        }
        cache.put(from, found == null ? NO_SHOP_MAP : found);
        return found;
    }

    /** Pots low enough to need restocking. Per the errand policy this is the ONLY need that requires a
     *  specific shop (one that stocks potions); a full bag or low ammo can be handled at any shop. */
    static boolean potsLow(Character bot) {
        try {
            int[] pots = BotPotionManager.countPotions(bot);
            int potTrigger = BotManager.cfg.POT_LOW_WARN * 5;
            return pots[0] < potTrigger || pots[1] < potTrigger;
        } catch (RuntimeException ex) {
            return false; // best-effort: can't read pots -> treat as "not pot-low" (any shop is fine)
        }
    }

    /** SSOT affordability gate for a BUY-pots resupply errand: skip the town trip when the bot can't
     *  afford a useful restock, so a broke bot doesn't walk to a shop, buy nothing on NOT_ENOUGH_MESO,
     *  and bounce back forever. Used by BOTH errand triggers (reactive grind-stop in BotPotionManager
     *  and pre-travel in BotAutopilotManager). Selling is never gated by this — it earns the meso. */
    static boolean canAffordPotResupply(Character bot) {
        return bot.getMeso() >= BotManager.cfg.POT_SPEND_MIN_MESO;
    }

    /** True when the equipped weapon REQUIRES ammo but has none usable (no stars/bullets and no
     *  infinite-ammo buff like Soul Arrow / Shadow Stars) - the bot literally cannot attack until it
     *  restocks. Used to force a town resupply trip even when broke, since the visit sells trash to
     *  fund the refill. ponytail: a fully-broke bot recovers over two visits (a visit sells trash at
     *  its tail, the next recharges) - acceptable for this rare corner; the dagger thief build avoids
     *  the ammo economy entirely. Best-effort: unreadable weapon/ammo reads as "not stranded". */
    static boolean isOutOfUsableAmmo(Character bot) {
        try {
            WeaponType wt = BotAttackExecutionProvider.getEquippedWeaponType(bot);
            return BotCombatManager.isRangedAmmoWeapon(wt) && BotCombatManager.countAmmo(bot, wt) <= 0;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** True when the bot needs to BUY a consumable (HP/MP potions or ammo) — i.e. the errand is a
     *  supply run, not a pure sell-trash / bag-dump. Drives the errand-destination shop filter: a
     *  supply run must reach a potion-stocking shop (which also carries ammo), while a sell-only trip
     *  uses any shop. Reuses the same low-supply predicates the shopping flow uses (SSOT); the ammo
     *  checks are shop-independent (recharge takes no shop; fixed-ammo treats a null shop as "any").
     *  Best-effort: a partial character mock that breaks a count reads as "doesn't need to buy", so
     *  the bot falls back to any shop rather than stranding the errand. */
    static boolean needsToBuySupplies(Character bot) {
        try {
            if (potsLow(bot)) {
                return true;
            }
            WeaponType wt = BotAttackExecutionProvider.getEquippedWeaponType(bot);
            int ammoThreshold = ammoTriggerThreshold();
            return needsRechargeForShop(bot, wt, ammoThreshold)
                    || needsFixedAmmoForShop(bot, null, wt, ammoThreshold)
                    || shouldBuyStarterAmmoSet(bot, wt);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** True if the shop sells at least one recovery potion (HP or MP) at a real price. The cross-map
     *  errand-destination filter for a supply trip — a potion-stocking shop reliably also stocks the
     *  other consumables, so it's a sufficient, bag-state-independent proxy for "can resupply here".
     *  {@link BotInventoryManager#isRecoveryPotion} is the recovery-potion SSOT. */
    private static boolean shopSellsAnyPotion(Shop shop) {
        for (ShopItem si : shop.getItems()) {
            if (si.getPrice() > 0 && BotInventoryManager.isRecoveryPotion(si.getItemId())) {
                return true;
            }
        }
        return false;
    }

    /** On-arrival visit criterion: does this shop stock something the bot needs RIGHT NOW (ammo to
     *  recharge/buy, or a potion type it's low on that the shop sells)? Bag-state-aware, unlike the
     *  potion-only errand-destination filter. */
    private static boolean shopHasAnythingNeeded(Character bot, Shop shop) {
        WeaponType wt = BotAttackExecutionProvider.getEquippedWeaponType(bot);
        if (needsFixedAmmoForShop(bot, shop, wt, ammoTriggerThreshold())) {
            return true;
        }
        if (needsRechargeForShop(bot, wt, ammoTriggerThreshold())) {
            return true;
        }
        if (shouldBuyStarterAmmoSet(bot, wt) && findAmmoItem(shop, wt) != null) {
            return true;
        }
        int[] pots = BotPotionManager.countPotions(bot);
        if (pots[0] < BotManager.cfg.POT_LOW_WARN * 5 && findPotionItem(shop, bot, true) != null) {
            return true;
        }
        if (pots[1] < BotManager.cfg.POT_LOW_WARN * 5 && findPotionItem(shop, bot, false) != null) {
            return true;
        }
        return false;
    }

    private static void executePurchases(BotEntry entry, Character bot, Point npcPos) {
        if (!isShopSequenceValid(entry, bot, npcPos)) {
            abortShop(entry, bot, "couldn't get to the shopkeeper, never mind");
            return;
        }

        WeaponType wt = BotAttackExecutionProvider.getEquippedWeaponType(bot);
        List<PurchaseAction> actions = new ArrayList<>();

        // Buy a fresh set FIRST if the bot owns none (recharge can only refill an existing stack); the
        // recharge step below then tops the new set up to slot-max in the same visit.
        if (shouldBuyStarterAmmoSet(bot, wt)) {
            actions.add((sequence, shop) -> appendBuyReport(sequence, buyStarterAmmoSet(bot, shop, wt),
                    wt == WeaponType.GUN ? "bullets" : "throwing stars"));
        }
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
        if (shouldBuyReturnScrollWhileShopping(bot)) {
            actions.add((sequence, shop) -> appendBuyReport(sequence, buyReturnScrolls(bot, shop), "Return Scroll - Nearest Town"));
        }
        // Class-weighted pot budget: buy the PRIMARY pot type first (mage -> MP, everyone else -> HP)
        // but cap its spend so the SECONDARY keeps a reserve share of the meso, then the secondary
        // takes the rest. Without this the first type (HP) drained the whole wallet to its target and
        // starved the second (MP) - backwards for a mage. Ammo was bought above (essential to attack).
        boolean mage = bot.getJobStyle() == Job.MAGICIAN;
        actions.add((sequence, shop) -> buyPotsCapped(sequence, bot, shop,
                !mage, POT_SECONDARY_RESERVE_FRAC, mage ? "MP pots" : "HP pots"));
        actions.add((sequence, shop) -> buyPotsCapped(sequence, bot, shop,
                mage, 0.0, mage ? "HP pots" : "MP pots"));
        // Last: spend any surplus on a worthwhile gear upgrade (after consumables are covered).
        actions.add(BotShopManager::evaluateAndBuyEquip);

        runPurchaseStep(new PurchaseSequence(entry, bot, npcPos, actions, new ArrayList<>(), null), 0);
    }

    private static void runPurchaseStep(PurchaseSequence sequence, int index) {
        if (!isShopSequenceValid(sequence.entry(), sequence.bot(), sequence.npcPos())) {
            abortShop(sequence.entry(), sequence.bot(), "couldn't stay at the shop to buy, never mind");
            return;
        }
        if (index >= sequence.actions().size()) {
            // SSOT: every shop visit ends by unloading trash, no matter what brought the bot here
            // (resupply pots/ammo, manual sell command, cramped auto-sell). startSellTrashSequence
            // no-ops cleanly and keeps the existing buy-report messaging when nothing is sellable.
            startSellTrashSequence(sequence);
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

    private static void startSellTrashSequence(PurchaseSequence sequence) {
        // Selling is now the tail of EVERY visit, so distinguish an explicit sell goal (cramped
        // auto-sell or a "sell trash" command set shopSellTrashPending) from an incidental resupply
        // visit. Only the explicit goal announces "no junk worth selling"; an incidental visit with
        // nothing to sell just finishes its normal buy report (identical to the old non-sell path).
        boolean explicitSell = sequence.entry().shopSellTrashPending;
        List<Item> items = new ArrayList<>(
                BotInventoryManager.collectSellTrashItems(sequence.entry(), sequence.bot()));
        // Pressure-driven escalation: when the USE tab is cramped, sell the lowest value-per-slot
        // shelf stacks (worst first) on top of the always-junk, down to a healthy free-slot margin.
        // Junk already in the plan frees slots too, so it counts toward the margin and is excluded.
        if (isCramped(sequence.bot(), InventoryType.USE)) {
            var useInv = sequence.bot().getInventory(InventoryType.USE);
            int freeNow = useInv != null ? useInv.getNumFreeSlot() : 0;
            long junkUseSlots = items.stream()
                    .filter(it -> it.getInventoryType() == InventoryType.USE).count();
            int slotsToFree = USE_HEALTHY_FREE_SLOTS - (freeNow + (int) junkUseSlots);
            if (slotsToFree > 0) {
                Set<Item> already = Collections.newSetFromMap(new IdentityHashMap<>());
                already.addAll(items);
                List<Item> crampedSales = BotInventoryManager.collectCrampedUseSales(
                        sequence.bot(), slotsToFree, already);
                int farmItemId = sequence.entry().autopilotFarmItemId;
                if (farmItemId != 0) {
                    crampedSales.removeIf(it -> it.getItemId() == farmItemId);
                }
                items.addAll(crampedSales);
            }
        }
        if (items.isEmpty()) {
            sequence.entry().shopSellTrashPending = false;
            if (explicitSell) {
                BotManager.getInstance().botSay(sequence.bot(), "no junk worth selling");
                finishPurchaseSequence(sequence, false);
            } else {
                finishPurchaseSequence(sequence, true);
            }
            return;
        }

        List<Item> plan = List.copyOf(items);
        scheduleShopStep(sequence.entry(), SELL_TRASH_STEP_DELAY_MS,
                () -> runSellTrashStep(
                        sequence.entry(),
                        sequence.bot(),
                        sequence.npcPos(),
                        0,
                        new ArrayList<>(),
                        Collections.newSetFromMap(new IdentityHashMap<>()),
                        plan,
                        sequence.bought(),
                        sequence.firstShortfall()));
    }

    private static void runSellTrashStep(BotEntry entry, Character bot, Point npcPos, int soldCount, List<String> soldUseEtc,
                                         Set<Item> failedItems, List<Item> plan,
                                         List<String> bought, BuyReport firstShortfall) {
        if (!isShopSequenceValid(entry, bot, npcPos)) {
            abortShop(entry, bot, "couldn't stay at the shop to sell, never mind");
            return;
        }

        List<Item> items = plan.stream()
                .filter(item -> BotInventoryManager.hasItem(bot, item))
                .filter(item -> !failedItems.contains(item))
                .filter(item -> BotInventoryManager.sellTrashQuantity(item) > 0)
                .toList();
        if (items.isEmpty()) {
            entry.shopSellTrashPending = false;
            if (soldCount > 0) {
                BotManager.getInstance().botSay(bot, "sold " + soldCount + " junk item" + (soldCount != 1 ? "s" : ""));
                if (cfg.REPORT_SOLD_USE_ETC) {
                    for (String line : buildSoldDetailLines(soldUseEtc)) {
                        BotManager.getInstance().botSay(bot, line);
                    }
                }
            }
            if (!failedItems.isEmpty()) {
                BotManager.getInstance().botSay(bot, buildSellTrashFailureMessage(failedItems.size()));
            } else if (soldCount == 0) {
                BotManager.getInstance().botSay(bot, "no junk worth selling");
            }
            finishPurchaseSequence(new PurchaseSequence(entry, bot, npcPos, List.of(), bought, firstShortfall), false);
            return;
        }

        Item item = items.get(0);
        if (!BotInventoryManager.hasItem(bot, item)) {
            scheduleShopStep(entry, SELL_TRASH_STEP_DELAY_MS,
                    () -> runSellTrashStep(entry, bot, npcPos, soldCount, soldUseEtc, failedItems, plan, bought, firstShortfall));
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

        // Quantity is read at sell time so stacks that grew since planning still sell correctly.
        short beforeQuantity = item.getQuantity();
        short soldQuantity = BotInventoryManager.sellTrashQuantity(item);
        shop.sell(bot.getClient(), item.getInventoryType(), item.getPosition(), soldQuantity);
        if (BotInventoryManager.hasItem(bot, item)
                && item.getQuantity() > beforeQuantity - soldQuantity) {
            failedItems.add(item);
            scheduleShopStep(entry, SELL_TRASH_STEP_DELAY_MS,
                    () -> runSellTrashStep(entry, bot, npcPos, soldCount, soldUseEtc, failedItems, plan, bought, firstShortfall));
            return;
        }

        if (item.getInventoryType() != InventoryType.EQUIP) {
            soldUseEtc.add(soldQuantity + " " + resolveItemName(item.getItemId(), "item"));
        }
        logSoldItem(bot, item, soldQuantity);
        int nextSoldCount = soldCount + 1;
        scheduleShopStep(entry, SELL_TRASH_STEP_DELAY_MS,
                () -> runSellTrashStep(entry, bot, npcPos, nextSoldCount, soldUseEtc, failedItems, plan, bought, firstShortfall));
    }

    /** Audit trail for every NPC sale a bot makes — equips include their above-base trade
     *  score so a concerning sale (a good roll liquidated) is findable in the server log. */
    private static void logSoldItem(Character bot, Item item, short quantity) {
        String name = resolveItemName(item.getItemId(), "item");
        if (item instanceof Equip equip) {
            double score;
            try {
                score = BotInventoryManager.tradeValueScore(ItemInformationProvider.getInstance(), equip);
            } catch (Throwable t) {
                score = -1; // unit tests / WZ unavailable
            }
            log.info("bot-sell: {} sold equip {} (id {}, tradeScore {})",
                    bot.getName(), name, item.getItemId(), String.format("%.1f", score));
        } else {
            log.info("bot-sell: {} sold {}x {} (id {})",
                    bot.getName(), quantity, name, item.getItemId());
        }
    }

    // One or more ASCII chat lines listing the USE/ETC items sold, e.g. "unloaded: 12 Squid Ink, 3 Blue Potion".
    private static List<String> buildSoldDetailLines(List<String> soldUseEtc) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String soldEntry : soldUseEtc) {
            if (!line.isEmpty() && line.length() + soldEntry.length() + 2 > 90) {
                lines.add(line.toString());
                line = new StringBuilder();
            }
            line.append(line.isEmpty() ? (lines.isEmpty() ? "unloaded: " : "") : ", ").append(soldEntry);
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
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

    /** Quantity resupply tops the bot up to, per HP/MP potion type. The USE runway protects at
     *  least this much so a cramped sell trip never sheds pots the bot would immediately rebuy. */
    static int potResupplyTarget() {
        return BotManager.cfg.POT_LOW_WARN * POT_TARGET_THRESHOLD;
    }

    /** Quantity resupply tops (non-rechargeable) ammo up to; the USE runway protects at least this
     *  much so the bot never auto-sells arrows/bolts it would immediately rebuy. */
    static int ammoResupplyTarget() {
        return ammoTargetThreshold();
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

    /** True when a rechargeable-ammo bot (claw/gun) owns NO set at all to recharge - it sold/used the
     *  whole stack and must BUY a fresh set (recharge can only refill an existing stack). Without this a
     *  thief that lost its stars could never re-arm. Ammo is top spend priority, so this is ungated by
     *  the meso tiers; the cheapest star set is ~500 meso. */
    static boolean shouldBuyStarterAmmoSet(Character bot, WeaponType wt) {
        return isRechargeWeaponType(wt) && bestRechargeAmmoId(bot, wt) < 0
                && BotCombatManager.countAmmo(bot, wt) < ammoTargetThreshold();
    }

    /** Buy one fresh set of the cheapest matching rechargeable ammo (a star/bullet stack). The
     *  subsequent recharge step tops it up to slot-max within the same visit. */
    private static BuyReport buyStarterAmmoSet(Character bot, Shop shop, WeaponType wt) {
        ShopSlotItem ammo = findAmmoItem(shop, wt);
        if (ammo == null) {
            return new BuyReport(0, 0, 0, ShortfallReason.NONE);
        }
        return buyFixedCostItem(bot, shop, ammo, 1, 1);
    }

    static boolean shouldBuyReturnScrollWhileShopping(Character bot) {
        return countReturnScrolls(bot) < RETURN_SCROLL_TARGET_QTY;
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
                case CLAW -> ItemConstants.isThrowingStar(id);
                case GUN -> ItemConstants.isBullet(id);
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

    private static BuyReport buyReturnScrolls(Character bot, Shop shop) {
        ShopSlotItem scroll = findReturnScrollItem(shop);
        if (scroll == null) {
            return new BuyReport(0, 0, 0, ShortfallReason.NONE);
        }
        int current = countReturnScrolls(bot);
        return buyFixedCostItem(bot, shop, scroll, Math.max(0, RETURN_SCROLL_TARGET_QTY - current), 10);
    }

    private static ShopSlotItem findReturnScrollItem(Shop shop) {
        List<ShopItem> items = shop.getItems();
        for (int i = 0; i < items.size(); i++) {
            ShopItem si = items.get(i);
            if (si.getItemId() == RETURN_SCROLL_NEAREST_TOWN && si.getPrice() > 0) {
                return new ShopSlotItem((short) i, si);
            }
        }
        return null;
    }

    static int countReturnScrolls(Character bot) {
        var use = bot.getInventory(InventoryType.USE);
        if (use == null) {
            return 0;
        }
        int count = 0;
        for (Item item : use.list()) {
            if (item.getItemId() == RETURN_SCROLL_NEAREST_TOWN && item.getQuantity() > 0) {
                count += item.getQuantity();
            }
        }
        return count;
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

    /** Cap on meso spent buying gear per shop visit, so one purchase can't drain the bot. */
    private static final int EQUIP_BUY_MAX_MESO = 50_000;

    /**
     * Buy at most ONE worthwhile equipment upgrade from the shop catalog. Shop equips have FIXED
     * stats, so they're scored on their base stats with the SAME offense valuation the grind planner
     * uses for gear ({@link BotScrollManager#potentialValue}) and compared to what's worn in the slot
     * - one SSOT, no parallel scorer. Only buys a strict upgrade the bot can wear right now, and only
     * from surplus meso above the resupply floor (capped), so consumable money is never touched. The
     * bought item is equipped by the regular autoEquip tick (it valued it an upgrade too).
     */
    private static PurchaseSequence evaluateAndBuyEquip(PurchaseSequence sequence, Shop shop) {
        Character bot = sequence.bot();
        // Surplus only: reserve the pot/ammo resupply floor, then cap the gear spend.
        long budget = Math.min((long) EQUIP_BUY_MAX_MESO, (long) bot.getMeso() - BotManager.cfg.AMMO_RESERVE_MESO);
        if (budget <= 0) {
            return sequence;
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        List<ShopItem> items = shop.getItems();
        short bestShopSlot = -1;
        int bestItemId = -1;
        double bestGain = 0.0; // strictly positive gain required -> only a real upgrade is bought
        for (int i = 0; i < items.size(); i++) {
            ShopItem si = items.get(i);
            int id = si.getItemId();
            int price = si.getPrice();
            if (price <= 0 || price > budget) {
                continue;
            }
            if (!ItemConstants.isEquipment(id) || ii.isCash(id)) {
                continue;
            }
            Short slot = BotScrollManager.primarySlot(ii, id);
            if (slot == null) {
                continue;
            }
            if (!(ii.getEquipById(id) instanceof Equip cand) || !ii.canWearEquipment(bot, cand, slot)) {
                continue; // fixed stats -> must be wearable right now (no future/stat-blocked projection)
            }
            Equip worn = BotScrollManager.wornInSlot(bot, ii, slot);
            double wornValue = worn == null ? 0.0 : BotScrollManager.potentialValue(bot, ii, worn);
            double gain = BotScrollManager.potentialValue(bot, ii, cand) - wornValue;
            if (gain > bestGain) {
                bestGain = gain;
                bestShopSlot = (short) i;
                bestItemId = id;
            }
        }
        if (bestItemId != -1
                && shop.buyDirect(bot, bestShopSlot, bestItemId, (short) 1) == Shop.TransactionResult.SUCCESS) {
            sequence.bought().add(resolveItemName(bestItemId, "gear"));
        }
        return sequence;
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

    /** Reserve share of the meso the SECONDARY pot type is guaranteed when the primary buys first,
     *  so a tight budget can't be fully spent on one pot type while starving the other. */
    private static final double POT_SECONDARY_RESERVE_FRAC = 0.35;

    /** Buy one pot type, but only if low, and spend at most {@code (1 - reserveForOtherFrac)} of the
     *  bot's current meso so the other pot type keeps its share. Skips quietly if already stocked. */
    private static PurchaseSequence buyPotsCapped(PurchaseSequence sequence, Character bot, Shop shop,
                                                  boolean forHp, double reserveForOtherFrac, String label) {
        int[] pots = BotPotionManager.countPotions(bot);
        int current = forHp ? pots[0] : pots[1];
        if (current >= BotManager.cfg.POT_LOW_WARN * 5) {
            return sequence;
        }
        long mesoCap = (long) Math.floor(bot.getMeso() * (1.0 - reserveForOtherFrac));
        return appendBuyReport(sequence, buyPotions(bot, shop, forHp, mesoCap), label);
    }

    private static BuyReport buyPotions(Character bot, Shop shop, boolean forHp, long mesoCap) {
        ShopSlotItem pot = findPotionItem(shop, bot, forHp);
        if (pot == null) {
            return new BuyReport(0, 0, 0, ShortfallReason.NONE);
        }

        int target = potResupplyTarget();
        int[] pots = BotPotionManager.countPotions(bot);
        int current = forHp ? pots[0] : pots[1];
        int want = Math.max(0, target - current);
        int price = pot.shopItem.getPrice();
        if (price > 0) {
            // Cap the quantity to what this type's meso share can afford (buyFixedCostItem's
            // NOT_ENOUGH_MESO handling is the backstop); leaves the rest for the other pot type.
            want = (int) Math.min((long) want, mesoCap / price);
        }
        return buyFixedCostItem(bot, shop, pot, want, 100);
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
                    : "only fit " + got + " " + itemName + " out of " + want + " - bag's full";
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
        // via the stuck-at-NPC / timeout fallbacks, where the bot is near the keeper but
        // can't physically reach the approach point (fenced booths) — hence the wide radius.
        Point pos = bot.getPosition();
        Point approach = entry.shopTargetPos != null ? entry.shopTargetPos : npcPos;
        boolean atShop = manhattan(pos, approach) <= SHOP_FALLBACK_DIST
                || manhattan(pos, npcPos) <= SHOP_FALLBACK_DIST;
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
        entry.shopTargetGraphChecked = false;
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
            if (entry.shopSequenceActive) {
                entry.shopSequenceStartedAtMs = System.currentTimeMillis();
            }
            try {
                step.run();
            } catch (RuntimeException exception) {
                abortShop(entry, entry.bot, "ran into a problem at the shop");
                throw exception;
            }
        });
    }


    private static NPC findNpcNear(Character bot, Point pos) {
        for (MapObject obj : bot.getMap().getMapObjectsInRange(
                pos, SHOP_NPC_SEARCH_DIST * SHOP_NPC_SEARCH_DIST,
                Arrays.asList(MapObjectType.NPC))) {
            NPC npc = (NPC) obj;
            if (npc.hasShop()) {
                return npc;
            }
        }
        return null;
    }
}
