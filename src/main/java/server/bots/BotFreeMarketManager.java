package server.bots;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import net.server.channel.handlers.PlayerInteractionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.CashShop;
import server.ItemInformationProvider;
import server.Trade;
import server.maps.HiredMerchant;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.PlayerShopItem;
import server.maps.Portal;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Free-Market session errand (docs/bot/living-economy-design.md sec 8.1-8.2): during a rest
 * break, a bot with sellable surplus travels to an FM town, walks the market portal in, picks a
 * room, opens a real {@link HiredMerchant} stall stocked from its shelf surplus at prices from
 * its own {@link BotMarketBook}, browses the other stalls (observations + the odd bargain buy),
 * and walks out. Mirrors {@link BotGachaponManager}'s DetourErrand shape throughout.
 *
 * <p>V1 scope: USE-tab shelf surplus only (equip listing joins with the S4 valuer); no Fredrick
 * collection yet (MerchantMesos accrue safely and BotAssetView counts them); restock/reprice of a
 * live stall is the next slice - an existing live stall downgrades a trip to browse-only.
 */
final class BotFreeMarketManager {

    private static final Logger log = LoggerFactory.getLogger(BotFreeMarketManager.class);

    /** Hired-merchant permit (CASH tab; id/10000==503). Not consumed on open (server behavior). */
    static final int PERMIT_ITEM = 5030000;
    /** Commodity.img SN 10000562: ItemId 5030000, Price 5900 NX (verified from WZ). */
    static final int PERMIT_SN = 10000562;
    static final int PERMIT_NX_FALLBACK = 5900;

    static final int FM_ENTRANCE = constants.id.MapId.FM_ENTRANCE; // 910000000

    /**
     * Towns carrying the scripted market00 portal (grep of Map.wz portal scripts, 2026-07-02).
     * The errand travels to the TOWN via the normal graph, then walks the scripted portal itself
     * - zero BotWorldGraph changes (explorer-verified approach).
     */
    static final int[] FM_TOWNS = {
            100000100, 102000000, 120000200, 130000200, 140000000, 200000000, 211000100,
            220000000, 221000000, 222000000, 230000000, 230000002, 240000000, 250000000,
            251000000, 260000000, 261000000, 271010000, 540000000, 541000000, 550000000,
            551000000, 600000000, 801000300,
    };

    // Phases of one market session (entry.fmPhase).
    static final int PHASE_TRAVEL = 0;   // walking to the FM town
    static final int PHASE_ENTER = 1;    // at the town: walk the market00 scripted portal
    static final int PHASE_TO_ROOM = 2;  // at the entrance: pick + enter a room
    static final int PHASE_SETUP = 3;    // in the room: place + stock + publish the stall
    static final int PHASE_BROWSE = 4;   // read the other stalls, maybe grab a bargain
    static final int PHASE_EXIT = 5;     // walk back out to the saved town

    // Jittered scan cadence (mirrors gacha; the satiation interval is the real limiter).
    private static final long SCAN_MIN_MS = 90_000L;
    private static final long SCAN_MAX_MS = 180_000L;
    /** Overall no-progress deadline per walking leg (gacha-style). */
    static final long ERRAND_TIMEOUT_MS = 150_000L;
    /** Per-phase watchdog for the in-FM phases (short walks + staging). */
    private static final long PHASE_DEADLINE_MS = 60_000L;
    /** Humanlike browse dwell before heading out. */
    private static final long BROWSE_MIN_MS = 15_000L;
    private static final long BROWSE_MAX_MS = 45_000L;

    /** Don't bother with a trip for fewer than this many listable stacks (resource bound). */
    static final int MIN_LISTINGS_TO_TRIP = 3;
    /**
     * One-way travel cap for the FM leg, from wherever the bot is RESTING (owner rule: no
     * back-and-forth treks — a bot deep in a dungeon never diverts to the market mid-grind; the
     * market rides the break. With the break-destination bias below this is usually ~0).
     */
    static final double MAX_ONE_WAY_TRAVEL_SECONDS = 90.0;
    /** Extra detour budget the BREAK town may take on to land at an FM town instead (adjacency). */
    static final double FM_BREAK_DETOUR_BUDGET_SECONDS = 45.0;
    /** Chill-session browse dwell multiplier: a "market day" lingers instead of a quick loop. */
    private static final int CHILL_DWELL_FACTOR = 8;
    /** Stall slot cap (HiredMerchant.addItem refuses past 16). */
    static final int STALL_SLOT_CAP = 16;
    /** Bounded bargain purchases per trip (wallet + WTP are the real limits; this bounds dwell). */
    private static final int MAX_BARGAIN_BUYS = 2;
    /** Bounded stall-spot placement attempts before falling back to browse-only. */
    private static final int MAX_PLACE_TRIES = 3;

    private BotFreeMarketManager() {
    }

    // ---- seams (WZ/DB/singleton-backed; tests swap) -------------------------------------------

    /** NPC sell-back for a whole stack - the alternative a listing must beat (SSOT getPrice). */
    @FunctionalInterface
    interface NpcSellLookup {
        long price(int itemId, int quantity);
    }
    static NpcSellLookup npcSell = (id, qty) -> {
        double p = ItemInformationProvider.getInstance().getPrice(id, qty);
        return p > 0 ? Math.round(p) : 0;
    };

    /** Tradeability gate for listing (drop/trade-restricted items stay home). */
    static java.util.function.IntPredicate tradeable = id -> {
        try {
            return !ItemInformationProvider.getInstance().isDropRestricted(id);
        } catch (RuntimeException e) {
            return false;
        }
    };

    /** Account NX + charge, mirroring the gacha ticket abstraction (the ONE allowed shortcut). */
    static BotGachaponManager.NxBalance nxBalance = bot -> bot.getCashShop().getCash(CashShop.NX_CREDIT);
    static BotGachaponManager.NxCharge nxCharge = (bot, nx) -> bot.getCashShop().gainCash(CashShop.NX_CREDIT, -nx);

    /** Real permit NX price from the cash-shop commodity SSOT; verified fallback otherwise. */
    static BotGachaponManager.TicketPrice permitPrice = () -> {
        try {
            CashShop.CashItem ci = CashShop.CashItemFactory.getItem(PERMIT_SN);
            if (ci != null && ci.getItemId() == PERMIT_ITEM && ci.getPrice() > 0) {
                return ci.getPrice();
            }
        } catch (RuntimeException e) {
            // WZ not loaded - fall through
        }
        return PERMIT_NX_FALLBACK;
    };

    static java.util.function.BiConsumer<BotEntry, String> reply =
            (entry, text) -> BotManager.getInstance().botReply(entry, text);

    // ---- pure pricing core (unit-tested) -------------------------------------------------------

    /**
     * Unit ask for a listing: the max of what the bot believes the market pays and its own cost
     * basis (the shelf keep-value SSOT), marked up by the confidence-scaled opening margin
     * (design sec 5). 0 = nothing to go on, don't list.
     */
    static int unitAsk(double perceivedUnit, double privateConfidence, double costBasisUnit) {
        double base = Math.max(perceivedUnit, costBasisUnit);
        if (base <= 0) {
            return 0;
        }
        double margin = BotMarketMath.openingMargin(0.5, privateConfidence); // trait wiring: S4
        long ask = Math.round(base * (1.0 + margin));
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, ask));
    }

    /** List on the stall only when the after-fee proceeds beat just NPC-selling the stack. */
    static boolean beatsNpcSale(int unitAsk, int quantity, long npcSellWholeStack) {
        long gross = (long) unitAsk * Math.max(1, quantity);
        return gross - Trade.getFee(gross) > npcSellWholeStack;
    }

    /** A planned stall listing: one bag stack -> one merchant slot. */
    record ListingPlan(Item item, short bundles, short perBundle, int unitPrice) {
        long bundlePrice() {
            return (long) unitPrice * Math.max(1, perBundle);
        }
    }

    /**
     * Select the USE-shelf surplus worth listing: SHELF-tier stacks (never RUNWAY - those are the
     * bot's own supplies; JUNK is worthless NPC fodder) whose after-fee ask beats the NPC
     * alternative. Prices: book perception blended over the shelf keep-value cost basis.
     */
    static List<ListingPlan> selectListings(BotEntry entry, Character bot, long now) {
        List<ListingPlan> out = new ArrayList<>();
        Map<Item, BotInventoryManager.UseClass> classes = BotInventoryManager.classifyBagUse(bot);
        BotMarketBook book = BotMarketBook.of(entry, bot);
        for (Map.Entry<Item, BotInventoryManager.UseClass> e : classes.entrySet()) {
            if (e.getValue().tier() != BotInventoryManager.UseTier.SHELF) {
                continue;
            }
            Item item = e.getKey();
            int qty = Math.max(1, item.getQuantity());
            if (!tradeable.test(item.getItemId())) {
                continue;
            }
            long key = BotMarketMath.priceKey(item.getItemId(), 0);
            double perceivedUnit = book.perceivedPrice(key, now);
            double costBasisUnit = e.getValue().keepValue() / qty;
            int ask = unitAsk(perceivedUnit, book.privateConfidence(key, now), costBasisUnit);
            if (ask <= 0 || !beatsNpcSale(ask, qty, npcSell.price(item.getItemId(), qty))) {
                continue;
            }
            // One merchant slot per bag stack: a single bundle holding the whole stack.
            out.add(new ListingPlan(item, (short) 1, (short) qty, ask));
            if (out.size() >= STALL_SLOT_CAP) {
                break;
            }
        }
        return out;
    }

    // ---- scan: decide whether to start a market session ---------------------------------------

    static void tickScan(BotEntry entry, Character bot) {
        if (!BotManager.cfg.FM_MARKET_ENABLED || bot.getMap() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < entry.nextFmScanAtMs) {
            return;
        }
        entry.nextFmScanAtMs = now + BotManager.randMs((int) SCAN_MIN_MS, (int) SCAN_MAX_MS);

        if (!BotAutopilotManager.isActive(entry)) {
            return; // supervised companions stay at the owner's side
        }
        // Market sessions NEVER interrupt grinding (owner rule): they run from a rest break the
        // bot is already taking, or fill a whole chill session (the trading "market day").
        boolean chilling = entry.chillSession;
        if (!chilling && !BotBreakManager.onRestBreak(entry, bot, now)) {
            return;
        }
        if (entry.fmErrandMapId != -1 || entry.gachaErrandMapId != -1
                || entry.questErrandMapId != -1 || entry.jobErrandMapId != -1) {
            return; // one errand at a time
        }

        boolean stallServiceDue = entry.nextStallServiceAtMs > 0 && now >= entry.nextStallServiceAtMs;
        List<ListingPlan> listable = selectListings(entry, bot, now);
        // A chilling bot will also just go browse (its book still learns); a break-bot needs a reason.
        if (!stallServiceDue && listable.size() < MIN_LISTINGS_TO_TRIP && !chilling) {
            return; // nothing worth the walk. ponytail: S4 adds the own-income-rate travel gate
        }

        int town = nearestFmTown(entry, bot);
        if (town < 0) {
            return; // no FM town within the travel cap from this rest spot - market waits for a
                    // townside break (decideBreakDestination biases the next one toward an FM town)
        }
        entry.fmErrandMapId = town;
        entry.fmRoomMapId = -1;
        entry.fmPhase = PHASE_TRAVEL;
        entry.fmPlaceTries = 0;
        entry.fmBargainBuys = 0;
        entry.fmBrowseUntilMs = 0L;
        entry.fmStandSpot = null;
        entry.fmErrandProgress.begin(now);
        entry.fmPhaseDeadlineAtMs = now + ERRAND_TIMEOUT_MS;
        reply.accept(entry, stallServiceDue ? "gonna check on my shop at the fm"
                : "got some stuff to sell, heading to the free market");
    }

    /**
     * Nearest FM-portal town by travel seconds under the bot's real travel means, capped at
     * {@link #MAX_ONE_WAY_TRAVEL_SECONDS} one-way — beyond that the market is not worth the walk
     * from a rest spot and the trip simply doesn't happen (-1).
     */
    static int nearestFmTown(BotEntry entry, Character bot) {
        BotWorldGraph.RouteOptions options = BotGachaponManager.travelOptions.apply(bot);
        int best = -1;
        double bestSec = Double.MAX_VALUE;
        for (int town : FM_TOWNS) {
            // travelSeconds returns the ROUND trip (2x one-way)
            double sec = BotGachaponManager.travelSeconds.seconds(bot.getMapId(), town, options) / 2.0;
            if (sec < bestSec && sec <= MAX_ONE_WAY_TRAVEL_SECONDS) {
                bestSec = sec;
                best = town;
            }
        }
        return best;
    }

    /**
     * "Trade break adjacent to the normal break" (owner rule): when the bot is rolling a TOWN
     * break anyway and has market intent, upgrade the break destination to an FM-portal town if
     * one is within a small detour of the town it was going to — the market trip then rides
     * travel that was happening regardless. Null = no upgrade (break as planned).
     */
    static Integer preferFmBreakTown(BotEntry entry, Character bot, int plannedTown, long now) {
        if (!BotManager.cfg.FM_MARKET_ENABLED || !hasMarketIntent(entry, bot, now)) {
            return null;
        }
        for (int town : FM_TOWNS) {
            if (town == plannedTown) {
                return plannedTown; // the planned break town already has the market portal
            }
        }
        BotWorldGraph.RouteOptions options = BotGachaponManager.travelOptions.apply(bot);
        int best = -1;
        double bestExtra = Double.MAX_VALUE;
        for (int town : FM_TOWNS) {
            double extra = BotGachaponManager.travelSeconds.seconds(plannedTown, town, options) / 2.0;
            if (extra < bestExtra && extra <= FM_BREAK_DETOUR_BUDGET_SECONDS) {
                bestExtra = extra;
                best = town;
            }
        }
        return best != -1 ? best : null;
    }

    /** Would this bot go to the market if a break landed it near one? (surplus or stall service) */
    static boolean hasMarketIntent(BotEntry entry, Character bot, long now) {
        if (!BotManager.cfg.FM_MARKET_ENABLED || !BotAutopilotManager.isActive(entry)) {
            return false;
        }
        if (entry.nextStallServiceAtMs > 0 && now >= entry.nextStallServiceAtMs) {
            return true;
        }
        return selectListings(entry, bot, now).size() >= MIN_LISTINGS_TO_TRIP;
    }

    // ---- errand tick ---------------------------------------------------------------------------

    /** Drives an active market session; true when the tick is consumed. */
    static boolean tickErrand(BotEntry entry, Character bot, boolean runAiTick) {
        if (entry.fmErrandMapId == -1) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (entry.fmErrandProgress.stalled(now, ERRAND_TIMEOUT_MS) || now > entry.fmPhaseDeadlineAtMs) {
            finishErrand(entry, bot, "market trip fizzled, heading back to it later");
            return false;
        }
        switch (entry.fmPhase) {
            case PHASE_TRAVEL -> {
                if (bot.getMapId() == entry.fmErrandMapId) {
                    advancePhase(entry, PHASE_ENTER, now);
                    return true;
                }
                boolean moved = BotTravelManager.tickTravel(entry, bot, entry.fmErrandMapId,
                        BotAutopilotManager.MAX_TRAVEL_HOPS, runAiTick, false);
                entry.fmErrandProgress.record(bot, moved, now);
                return moved;
            }
            case PHASE_ENTER -> {
                if (bot.getMapId() == FM_ENTRANCE) {
                    advancePhase(entry, PHASE_TO_ROOM, now);
                    return true;
                }
                Portal market = bot.getMap() != null ? bot.getMap().getPortal("market00") : null;
                if (market == null) {
                    finishErrand(entry, bot, "huh, no market entrance here, never mind");
                    return false;
                }
                entry.fmErrandProgress.touch(now);
                return BotTravelManager.walkToPortalAndEnter(entry, bot, market, now, runAiTick);
            }
            case PHASE_TO_ROOM -> {
                if (entry.fmRoomMapId != -1 && bot.getMapId() == entry.fmRoomMapId) {
                    advancePhase(entry, PHASE_SETUP, now);
                    return true;
                }
                if (bot.getMapId() != FM_ENTRANCE) {
                    // bumped out somehow - walk back in
                    entry.fmPhase = PHASE_ENTER;
                    return true;
                }
                Portal roomPortal = pickRoomPortal(entry, bot);
                if (roomPortal == null) {
                    finishErrand(entry, bot, "market looks packed, another time");
                    return false;
                }
                entry.fmErrandProgress.touch(now);
                return BotTravelManager.walkToPortalAndEnter(entry, bot, roomPortal, now, runAiTick);
            }
            case PHASE_SETUP -> {
                return tickSetup(entry, bot, runAiTick, now);
            }
            case PHASE_BROWSE -> {
                if (entry.fmBrowseUntilMs == 0L) {
                    int dwellFactor = entry.chillSession ? CHILL_DWELL_FACTOR : 1; // market day lingers
                    entry.fmBrowseUntilMs = now + (long) dwellFactor
                            * BotManager.randMs((int) BROWSE_MIN_MS, (int) BROWSE_MAX_MS);
                    browseStalls(entry, bot, now);
                }
                entry.fmErrandProgress.touch(now);
                if (now < entry.fmBrowseUntilMs) {
                    return true; // lingering between the stalls, humanlike
                }
                advancePhase(entry, PHASE_EXIT, now);
                return true;
            }
            case PHASE_EXIT -> {
                if (!GameConstants_isFm(bot.getMapId())) {
                    finishErrand(entry, bot, null); // back in the world
                    return false;
                }
                Portal out = bot.getMap() != null ? bot.getMap().getPortal("out00") : null;
                if (out == null) {
                    finishErrand(entry, bot, null);
                    return false;
                }
                entry.fmErrandProgress.touch(now);
                return BotTravelManager.walkToPortalAndEnter(entry, bot, out, now, runAiTick);
            }
            default -> {
                finishErrand(entry, bot, null);
                return false;
            }
        }
    }

    private static boolean GameConstants_isFm(int mapId) {
        return mapId == FM_ENTRANCE || constants.game.GameConstants.isFreeMarketRoom(mapId);
    }

    private static void advancePhase(BotEntry entry, int phase, long now) {
        entry.fmPhase = phase;
        entry.fmPhaseDeadlineAtMs = now + PHASE_DEADLINE_MS;
        entry.fmErrandProgress.touch(now);
    }

    /** Entrance -> room: prefer the room portals in a stable per-bot order (in00..in19 probes). */
    private static Portal pickRoomPortal(BotEntry entry, Character bot) {
        List<Portal> candidates = new ArrayList<>();
        for (int i = 0; i <= 19; i++) {
            Portal p = bot.getMap().getPortal(String.format("in%02d", i));
            if (p != null && constants.game.GameConstants.isFreeMarketRoom(p.getTargetMapId())) {
                candidates.add(p);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        // stable per-bot-per-day pick spreads bots across rooms without coordination
        int idx = (int) Math.floorMod(bot.getId() * 31L + (System.currentTimeMillis() / 86_400_000L),
                candidates.size());
        Portal chosen = candidates.get(idx);
        entry.fmRoomMapId = chosen.getTargetMapId();
        return chosen;
    }

    /** In the room: walk to a spot, place + stock + publish the stall, then browse. */
    private static boolean tickSetup(BotEntry entry, Character bot, boolean runAiTick, long now) {
        // A live stall from a previous session (or no stock worth listing) -> browse-only trip.
        boolean stallAlive = bot.getWorldServer().getHiredMerchant(bot.getId()) != null;
        List<ListingPlan> listings = stallAlive ? List.of() : selectListings(entry, bot, now);
        if (stallAlive || listings.isEmpty() || !ensurePermit(entry, bot)) {
            advancePhase(entry, PHASE_BROWSE, now);
            return true;
        }
        Portal out = bot.getMap().getPortal("out00");
        Point anchor = out != null ? out.getPosition() : bot.getPosition();
        if (entry.fmStandSpot == null) {
            int offset = 260 + entry.fmPlaceTries * 160 + (bot.getId() % 7) * 30;
            entry.fmStandSpot = BotTravelManager.pickReachableApproachPoint(
                    entry, bot, new Point(anchor.x + offset, anchor.y), 40, 900);
        }
        Point stand = entry.fmStandSpot;
        if (entry.inAir || entry.climbing
                || Math.abs(bot.getPosition().x - stand.x) + Math.abs(bot.getPosition().y - stand.y) > 24) {
            BotTravelManager.pinMoveTarget(entry, stand);
            BotTravelManager.movementStep.step(entry, stand, runAiTick);
            entry.fmErrandProgress.touch(now);
            return true;
        }
        BotTravelManager.clearMoveTargetPin(entry);
        if (!PlayerInteractionHandler.canPlaceStore(bot)) {
            entry.fmPlaceTries++;
            entry.fmStandSpot = null;
            if (entry.fmPlaceTries >= MAX_PLACE_TRIES) {
                advancePhase(entry, PHASE_BROWSE, now); // no free spot - browse and go
            }
            return true;
        }
        openAndStockStall(entry, bot, listings, now);
        advancePhase(entry, PHASE_BROWSE, now);
        return true;
    }

    /** The permit check + gacha-style NX purchase abstraction (design sec 8.2, resolution 8). */
    static boolean ensurePermit(BotEntry entry, Character bot) {
        if (bot.getInventory(InventoryType.CASH).countById(PERMIT_ITEM) > 0) {
            return true;
        }
        int price = permitPrice.nx();
        if (nxBalance.nx(bot) < price) {
            return false; // can't afford a permit yet - browse-only trips still feed the book
        }
        if (!InventoryManipulator.checkSpace(bot.getClient(), PERMIT_ITEM, (short) 1, "")) {
            return false;
        }
        nxCharge.charge(bot, price);
        InventoryManipulator.addById(bot.getClient(), PERMIT_ITEM, (short) 1);
        reply.accept(entry, "bought a store permit, time to set up shop");
        return true;
    }

    /** Create + stock + publish under the marketBusy tick gate; tape LIST rows per slot. */
    private static void openAndStockStall(BotEntry entry, Character bot, List<ListingPlan> listings, long now) {
        entry.marketBusy = true;
        try {
            HiredMerchant merchant = HiredMerchant.createFor(bot, stallName(bot), PERMIT_ITEM);
            int listed = 0;
            for (ListingPlan plan : listings) {
                Item staged = plan.item().copy();
                staged.setQuantity(plan.perBundle());
                long bundlePrice = Math.min(Integer.MAX_VALUE, plan.bundlePrice());
                PlayerShopItem shopItem = new PlayerShopItem(staged, plan.bundles(), (int) bundlePrice);
                if (!merchant.addItem(shopItem)) {
                    break; // slot cap
                }
                InventoryType type = plan.item().getInventoryType();
                InventoryManipulator.removeFromSlot(bot.getClient(), type, plan.item().getPosition(),
                        (short) (plan.bundles() * plan.perBundle()), true);
                BotMarketLedger.getInstance().append(BotMarketLedger.EventKind.LIST,
                        plan.item().getItemId(), 0, plan.bundles() * plan.perBundle(),
                        plan.unitPrice(), bot.getId(), null, bot.getMapId());
                listed++;
            }
            if (listed == 0) {
                // nothing staged (all adds refused): unregister by closing the unpublished shell
                bot.setHiredMerchant(null);
                return;
            }
            try {
                merchant.saveItems(false);
            } catch (Exception e) {
                log.warn("stall saveItems failed for {}: {}", bot.getName(), e.toString());
            }
            merchant.publish(bot);
            entry.nextStallServiceAtMs = now + BotManager.randMs(20 * 3_600_000, 26 * 3_600_000);
            reply.accept(entry, "shop's up, " + listed + " things listed");
        } catch (RuntimeException e) {
            log.warn("stall setup failed for {}: {}", bot.getName(), e.toString());
        } finally {
            entry.marketBusy = false;
        }
    }

    /** Browse every other stall on this room map: observations always, bargains sparingly. */
    private static void browseStalls(BotEntry entry, Character bot, long now) {
        BotMarketBook book = BotMarketBook.of(entry, bot);
        List<MapObject> stalls = bot.getMap().getMapObjectsInRange(bot.getPosition(),
                Double.POSITIVE_INFINITY, List.of(MapObjectType.HIRED_MERCHANT));
        for (MapObject obj : stalls) {
            if (!(obj instanceof HiredMerchant merchant) || merchant.getOwnerId() == bot.getId()) {
                continue;
            }
            List<PlayerShopItem> items = merchant.getItems();
            for (int slot = 0; slot < items.size(); slot++) {
                PlayerShopItem psi = items.get(slot);
                if (!psi.isExist() || psi.getBundles() <= 0) {
                    continue;
                }
                int perBundle = Math.max(1, psi.getItem().getQuantity());
                double unit = (double) psi.getPrice() / perBundle;
                long key = BotMarketMath.priceKey(psi.getItem().getItemId(), 0);
                book.observe(key, unit, BotMarketMath.W_ASK, now);
                maybeBargainBuy(entry, bot, book, merchant, slot, psi, unit, key, now);
            }
        }
    }

    /**
     * Straight-buy a visibly underpriced listing: ask below the bot's own would-be ask floor
     * (perception minus its opening margin - reusing the margin math, no bargain constant).
     * The bot's wallet and perception are the only limiters (design sec 6).
     */
    private static void maybeBargainBuy(BotEntry entry, Character bot, BotMarketBook book,
                                        HiredMerchant merchant, int slot, PlayerShopItem psi,
                                        double unitAsk, long key, long now) {
        if (entry.fmBargainBuys >= MAX_BARGAIN_BUYS) {
            return;
        }
        double perceived = book.perceivedPrice(key, now);
        if (perceived <= 0) {
            return; // no idea what it's worth - not a bargain, just unknown
        }
        double margin = BotMarketMath.openingMargin(0.5, book.privateConfidence(key, now));
        if (unitAsk > perceived * (1.0 - Math.min(0.5, margin))) {
            return;
        }
        if (bot.getMeso() < psi.getPrice()) {
            return;
        }
        if (!InventoryManipulator.checkSpace(bot.getClient(), psi.getItem().getItemId(),
                (short) Math.max(1, psi.getItem().getQuantity()), "")) {
            return;
        }
        entry.marketBusy = true;
        try {
            merchant.buy(bot.getClient(), slot, (short) 1); // book learns via notifyStallSale
            entry.fmBargainBuys++;
            reply.accept(entry, "grabbed a deal at someone's shop");
        } catch (RuntimeException e) {
            log.warn("bargain buy failed for {}: {}", bot.getName(), e.toString());
        } finally {
            entry.marketBusy = false;
        }
    }

    private static String stallName(Character bot) {
        String[] pool = {"cheap stuff", "fair prices", "surplus sale", "come look",
                bot.getName() + "'s shop", "good deals here"};
        return pool[Math.floorMod(bot.getId(), pool.length)];
    }

    static void finishErrand(BotEntry entry, Character bot, String say) {
        clearFmErrand(entry);
        BotPersonality p = entry.personality != null ? entry.personality : BotPersonality.defaults();
        // Satiation: sessions ride the break cadence; meso-focused wiring lands with the S4 traits.
        long gap = Math.round(3_600_000L * (2.0 + 6.0 * ThreadLocalRandom.current().nextDouble())
                * (1.5 - 0.5 * p.chattiness()));
        entry.nextFmScanAtMs = System.currentTimeMillis() + gap;
        if (say != null) {
            reply.accept(entry, say);
        }
    }

    static void clearFmErrand(BotEntry entry) {
        BotTravelManager.clearMoveTargetPin(entry);
        entry.fmErrandMapId = -1;
        entry.fmRoomMapId = -1;
        entry.fmPhase = PHASE_TRAVEL;
        entry.fmPhaseDeadlineAtMs = 0L;
        entry.fmBrowseUntilMs = 0L;
        entry.fmPlaceTries = 0;
        entry.fmBargainBuys = 0;
        entry.fmStandSpot = null;
        entry.fmErrandProgress.clear();
        entry.marketBusy = false;
    }
}
