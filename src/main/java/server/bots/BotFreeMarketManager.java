package server.bots;

import client.Character;
import constants.inventory.ItemConstants;
import client.inventory.Equip;
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
import server.maps.SavedLocationType;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Free-Market session errand (docs/bot/economy.md): during a rest
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
     * Store-permit SKINS a bot's stall can wear (WZ-verified hired-merchant permits: Mushroom Elf,
     * Teddy Bear, Robot Stand, Coffeehouse, Granny's Stand; tiki torch 5030012 excluded like
     * SoloMapling). Cosmetic only — the bot still holds/gates on {@link #PERMIT_ITEM}; the skin is
     * never consumed or refunded (closeShop returns stock, not the permit). Picked deterministically
     * per bot so its shop identity stays stable across restarts (same idea as humanizeAsk styles).
     */
    static final int[] STALL_SKINS = {5030000, 5030001, 5030002, 5030004, 5030008, 5030010};

    static int stallSkin(Character bot) {
        return STALL_SKINS[Math.floorMod(bot.getId(), STALL_SKINS.length)];
    }

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
    static final int PHASE_FREDRICK = 6; // at the entrance: reclaim closed-stall proceeds
    static final int PHASE_SHOUT = 7;    // at the entrance: stand still + advertise surplus gear (shout-sell)

    /** Fredrick, Hired-Merchant Union chief — stands in the FM entrance (WZ life, verified). */
    static final int FREDRICK_NPC = 9030000;
    /** Within this many px of Fredrick counts as "at the counter" (cab/shop/gacha convention). */
    private static final int FREDRICK_TRIGGER_RADIUS_PX = 500;

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

    /** Chance a login-time bot with a closed stall / uncollected proceeds at Fredrick starts its
     *  market day immediately. High on purpose: at steady state nearly all of these bots would
     *  HAVE a live stall — a restart closed it — so this is state restoration, not phase seeding. */
    private static final double STALL_REBUILD_LOGIN_CHANCE = 0.5;
    /** Mean minutes of one market trip (travel + browse + setup), for the steady-state
     *  mid-trip-at-login fraction — same shape as {@link BotBreakManager#loginBreakChance}. */
    private static final double MARKET_TRIP_MEAN_MIN = 15.0;
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
    /** Shout-sell stand (PHASE_SHOUT): how long the bot stands at the entrance advertising per stint,
     *  and how much longer a chill "market day" lingers (not much else to do). */
    private static final long SHOUT_STAND_MIN_MS = 60_000L;
    private static final long SHOUT_STAND_MAX_MS = 150_000L;
    private static final int SHOUT_STAND_CHILL_FACTOR = 4;
    /** Chance a bot with surplus gear stands to shout-sell on the way out of an FM trip: high on a
     *  normal break (owner: "high chance to stand a bit on exit leg"), near-certain on a chill day. */
    private static final double SHOUT_STAND_BREAK_CHANCE = 0.75;
    private static final double SHOUT_STAND_CHILL_CHANCE = 0.95;
    /** Chance a normal break with sellable gear (but no other market reason) becomes a DEDICATED
     *  trip whose whole point is to go stand and shout-sell — rarer on a break, common on a chill day
     *  (chill already always trips, so the exit-leg stand covers it). */
    private static final double SHOUT_SELL_BREAK_TRIP_CHANCE = 0.15;
    /** Reposition cadence while standing: every so often the bot ambles to a NEW random spot on the
     *  entrance floor — humanlike "move around" and, more importantly, un-bunches bots that arrive on
     *  the same portal (owner: don't stack; pick random spots and occasionally reposition). */
    private static final long SHOUT_REPOSITION_MIN_MS = 12_000L;
    private static final long SHOUT_REPOSITION_MAX_MS = 30_000L;
    /** Stall slot cap (HiredMerchant.addItem refuses past 16). */
    static final int STALL_SLOT_CAP = 16;
    /** Bounded bargain purchases per trip (wallet + WTP are the real limits; this bounds dwell). */
    private static final int MAX_BARGAIN_BUYS = 2;
    /** Bounded stall-spot candidates consumed (unreachable or refused) before browse-only. */
    private static final int MAX_PLACE_TRIES = 3;
    /** Slot-column spacing: canPlaceStore rejects another merchant within ~152px (23000
     *  distance-squared), so stalls line up a touch wider than that. */
    static final int STALL_SPACING_PX = 170;
    /** Min gap to any other stall (distance-squared). The server's canPlaceStore blocks at ~151px
     *  (23000 sq) but its check isn't atomic with publish, so two bots that clear it in the same beat
     *  stack illegally; the bot enforces a wider 200px both when picking a slot AND again right before
     *  publishing, so a stall that appeared mid-walk bumps it to the next slot instead of overlapping. */
    private static final int STALL_MIN_SPACING_SQ = 200 * 200;
    /** How far out (in slot columns, each way) to hunt for a free spot on the floor strip. */
    private static final int MAX_STALL_SLOT_STEPS = 8;
    /** Give up walking to one candidate spot after this long without net progress. */
    private static final long PLACE_WALK_STUCK_MS = 12_000L;
    /** Re-visit an open stall every few hours to reprice/restock (living-economy S2). Short vs the
     *  ~24h forceClose so sold-out slots refill and stale asks track belief across a market day —
     *  the visit rides the same break/satiation cadence, so it's a detour on a town break, not a
     *  dedicated trek. */
    private static final int STALL_SERVICE_MIN_MS = 3 * 3_600_000;
    private static final int STALL_SERVICE_MAX_MS = 6 * 3_600_000;
    /** Reprice step aggressiveness (fraction of the ask→belief gap closed per visit) and the floor
     *  on how far one visit may cut an ask (never below half, nor the NPC sell-back). */
    private static final double REPRICE_PRESSURE = 0.5;
    private static final double REPRICE_MAX_DROP = 0.5;
    /** Undercut margin below the cheapest visible competing ask (design sec 5); matches BotMarketSimTest. */
    private static final double UNDERCUT_FRACTION = 0.02;

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

    /** Lowest LEGITIMATE price any NPC shop charges for the item (0 = not NPC-sold): the buyer's
     *  standing outside option. Backed by the scroll manager's cached shopitems reverse index
     *  (SSOT — includes the GM/junk-listing filter), not a parallel query. */
    @FunctionalInterface
    interface NpcShopPrice {
        int price(int itemId);
    }
    static NpcShopPrice npcShopPrice = BotScrollManager::marketBuyPriceMeso;

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

    /** Fredrick reclaim op — the SAME all-or-nothing server logic players trigger through the
     *  Fredrick UI (canRetrieveFromFredrick gate inside). Seamed for tests. */
    @FunctionalInterface
    interface FredrickRetrieve {
        void retrieve(Character bot);
    }
    static FredrickRetrieve fredrickRetrieve = bot -> net.server.Server.getInstance()
            .getChannelDependencies().fredrickProcessor().fredrickRetrieveItems(bot.getClient());

    /**
     * Fredrick holds proceeds for this bot: closed-stall merchant mesos and/or stored items. A
     * LIVE stall still owns its items/mesos (saveItems writes the same MERCHANT store), so there
     * is nothing to reclaim until it closes. One DB read; called only on entrance arrivals.
     */
    static boolean hasFredrickHoldings(Character bot) {
        if (bot.getWorldServer().getHiredMerchant(bot.getId()) != null) {
            return false;
        }
        if (bot.getMerchantMeso() != 0) {
            return true;
        }
        try {
            return !client.inventory.ItemFactory.MERCHANT.loadItems(bot.getId(), false).isEmpty();
        } catch (Exception e) {
            return false; // DB hiccup: skip this pass, the next trip re-checks
        }
    }

    // ---- pure pricing core (unit-tested) -------------------------------------------------------

    /**
     * Unit ask for a listing: the belief-vs-anchor base ({@link BotMarketMath#askBase} — market
     * belief overrides the cost/reproduction anchor downward as evidence accrues, salvage floors it),
     * marked up by the confidence-scaled opening margin (design sec 5). 0 = nothing to go on, don't
     * list.
     */
    static int unitAsk(double perceivedUnit, double privateConfidence, double anchorUnit, double salvageUnit) {
        double base = BotMarketMath.askBase(perceivedUnit, privateConfidence, anchorUnit, salvageUnit);
        if (base <= 0) {
            return 0;
        }
        double margin = BotMarketMath.openingMargin(0.5, privateConfidence); // trait wiring: S4
        long ask = Math.round(base * (1.0 + margin));
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, ask));
    }

    /**
     * SSOT unit reservation for a rolled equip (no seller margin): the calibrated reproduction curve
     * as a sanity-capped anchor, the bot's banded belief overriding it downward, NPC salvage as the
     * hard floor. The lowest unit price the seller will accept — used by the shout responder to decide
     * whether a buy offer covers the piece.
     */
    static long equipReservationUnit(BotMarketBook book, BotScrollManager.EquipQuote quote, long now) {
        long key = BotMarketMath.priceKey(quote.itemId(), quote.band());
        long salvage = npcSell.price(quote.itemId(), 1);
        double anchor = Math.min(calibratedCurveQuote(book, quote, now), BotMarketMath.reproSanityCap(salvage));
        return Math.round(BotMarketMath.askBase(
                book.perceivedPrice(key, now), book.perceivedConfidence(key, now), anchor, salvage));
    }

    /**
     * SSOT advertised unit ask for a rolled equip (reservation + opening margin). Both the stall
     * listing path ({@link #evaluateEquipListings}) and the shout path
     * ({@link BotShoutTradeManager}) price through this — no parallel reproduction-cost ask.
     */
    static int equipUnitAsk(BotMarketBook book, BotScrollManager.EquipQuote quote, long now) {
        long key = BotMarketMath.priceKey(quote.itemId(), quote.band());
        long salvage = npcSell.price(quote.itemId(), 1);
        double anchor = Math.min(calibratedCurveQuote(book, quote, now), BotMarketMath.reproSanityCap(salvage));
        return unitAsk(book.perceivedPrice(key, now), book.perceivedConfidence(key, now), anchor, salvage);
    }

    /** After-fee premium of selling the stack on a stall vs just NPC-selling it — the quantity a
     *  listing must justify. <= 0 means the NPC counter is the standing better bid. */
    static long listingPremium(int unitAsk, int quantity, long npcSellWholeStack) {
        long gross = (long) unitAsk * Math.max(1, quantity);
        return gross - Trade.getFee(gross) - npcSellWholeStack;
    }

    /** A stall slot must out-earn the time the trip represents: ~this many seconds of the
     *  farming-cost scaffold ({@link BotScrollManager#FARM_MESO_PER_SECOND}; both retire together
     *  at P3). Keeps NPC staples (potions) off the stall without a ban — their premium is real
     *  but tiny, so it never covers the bother of a slot. */
    private static final double LISTING_WORTH_SECONDS = 30.0;

    static long slotWorthMesos() {
        return Math.round(BotScrollManager.FARM_MESO_PER_SECOND * LISTING_WORTH_SECONDS);
    }

    /** A planned stall listing: one bag stack -> one merchant slot. */
    record ListingPlan(Item item, short bundles, short perBundle, int unitPrice) {
        long bundlePrice() {
            return (long) unitPrice * Math.max(1, perBundle);
        }
    }

    /** One SHELF stack's evaluation trail: the ask math plus why it did or didn't make the stall
     *  (the market debug endpoint renders these verbatim). {@code plan == null} -> not listed. */
    record ListingVerdict(int itemId, int quantity, int ask, long npcSellBack, int npcShopPrice,
                          long premium, String verdict, ListingPlan plan) {}

    /**
     * Select the USE-shelf surplus worth listing: SHELF-tier stacks (never RUNWAY - those are the
     * bot's own supplies; JUNK is worthless NPC fodder), premium-ranked. Prices: book perception
     * blended over the shelf keep-value cost basis.
     */
    static List<ListingPlan> selectListings(BotEntry entry, Character bot, long now) {
        return selectListings(bot, BotMarketBook.of(entry, bot), now);
    }

    /** Book-parameterized core, so read-only callers (the debug endpoint) can pass a detached
     *  replica instead of touching the tick-thread-owned book on {@code entry}. */
    static List<ListingPlan> selectListings(Character bot, BotMarketBook book, long now) {
        List<ListingPlan> picked = new ArrayList<>();
        for (ListingVerdict v : evaluateListings(bot, book, now)) {
            if (v.plan() != null) {
                picked.add(v.plan());
            }
        }
        return picked;
    }

    /**
     * Evaluate every SHELF-tier bag stack for the stall, premium-ranked. The ask comes from the
     * bot's book over its keep-value cost basis, CAPPED just under the NPC shop price when an NPC
     * sells the item (the buyer's standing outside option — an ask at the counter price can never
     * clear), and a stack lists only when the after-fee premium over NPC-selling it covers a
     * slot's worth of farming time. Cheap NPC staples price themselves out naturally, no ban.
     * Top {@link #STALL_SLOT_CAP} by premium carry plans; the rest keep their verdicts.
     */
    static List<ListingVerdict> evaluateListings(Character bot, BotMarketBook book, long now) {
        List<ListingVerdict> out = new ArrayList<>();
        Map<Item, BotInventoryManager.UseClass> classes = BotInventoryManager.classifyBagUse(bot);
        for (Map.Entry<Item, BotInventoryManager.UseClass> e : classes.entrySet()) {
            if (e.getValue().tier() != BotInventoryManager.UseTier.SHELF) {
                continue;
            }
            Item item = e.getKey();
            int id = item.getItemId();
            int qty = Math.max(1, item.getQuantity());
            long npcWhole = npcSell.price(id, qty);
            int shopPrice = npcShopPrice.price(id);
            if (!tradeable.test(id)) {
                out.add(new ListingVerdict(id, qty, 0, npcWhole, shopPrice, 0, "untradeable", null));
                continue;
            }
            long key = BotMarketMath.priceKey(id, 0);
            double costBasisUnit = e.getValue().keepValue() / qty;
            double salvageUnit = npcWhole / (double) qty;
            int ask = unitAsk(book.perceivedPrice(key, now), book.perceivedConfidence(key, now), costBasisUnit, salvageUnit);
            ask = (int) Math.min(Integer.MAX_VALUE, BotMarketMath.humanizeAsk(ask, bot.getId()));
            if (shopPrice > 0 && ask >= shopPrice) {
                ask = shopPrice - 1; // undercut the counter or don't bother
            }
            if (ItemConstants.isRechargeable(id)) {
                // Throwing stars / bullets are a rechargeable SET: never split, recharged to max for
                // pennies, so the whole stack is worth ONE flat set price - not the belief's per-unit
                // ask x count (a stack of 5 and 5000 are worth the same). Anchor to the flat NPC set
                // cost (npcWhole = getPrice = wholePrice + tiny recharge) and undercut it, spread back
                // over the stack so the single whole-stack bundle carries that flat, count-free price.
                ask = (int) Math.max(1, Math.min(Integer.MAX_VALUE, Math.max(1, npcWhole - 1) / qty));
            }
            if (ask <= 0) {
                out.add(new ListingVerdict(id, qty, 0, npcWhole, shopPrice, 0, "no price basis", null));
                continue;
            }
            long premium = listingPremium(ask, qty, npcWhole);
            if (premium <= 0) {
                out.add(new ListingVerdict(id, qty, ask, npcWhole, shopPrice, premium,
                        "npc sale pays better", null));
                continue;
            }
            if (premium < slotWorthMesos()) {
                out.add(new ListingVerdict(id, qty, ask, npcWhole, shopPrice, premium,
                        "premium not worth a slot", null));
                continue;
            }
            // One merchant slot per bag stack: a single bundle holding the whole stack.
            out.add(new ListingVerdict(id, qty, ask, npcWhole, shopPrice, premium, "list",
                    new ListingPlan(item, (short) 1, (short) qty, ask)));
        }
        evaluateEquipListings(bot, book, now, out);
        out.sort(java.util.Comparator.comparingLong(ListingVerdict::premium).reversed());
        int slots = 0;
        for (int i = 0; i < out.size(); i++) {
            ListingVerdict v = out.get(i);
            if (v.plan() != null && ++slots > STALL_SLOT_CAP) {
                out.set(i, new ListingVerdict(v.itemId(), v.quantity(), v.ask(), v.npcSellBack(),
                        v.npcShopPrice(), v.premium(), "crowded out (slot cap)", null));
            }
        }
        return out;
    }

    /**
     * Evaluate the valuables shelf ({@link BotInventoryManager#collectMarketableEquips}) for the
     * stall: each rolled piece is its own verdict/plan (the server clears equips one at a time —
     * two same-id equips carry different rolls, so no bundling). The ask blends the bot's belief
     * at the piece's BANDED price key over the reproduction-curve quote as cost basis; when the
     * bot has traded bands of this item before, the curve is first pinned to that evidence
     * ({@link BotMarketMath#curveCalibration}). The NPC counter only caps CLEAN pieces — a shop
     * copy substitutes a band-0 base, never a scrolled roll.
     */
    private static void evaluateEquipListings(Character bot, BotMarketBook book, long now,
                                              List<ListingVerdict> out) {
        BotEntry entry = BotManager.getInstance().getEntryByBotCharId(bot.getId());
        for (Equip eq : BotInventoryManager.collectMarketableEquips(entry, bot)) {
            int id = eq.getItemId();
            long npcWhole = npcSell.price(id, 1);
            int shopPrice = npcShopPrice.price(id);
            if (!tradeable.test(id)) {
                out.add(new ListingVerdict(id, 1, 0, npcWhole, shopPrice, 0, "untradeable", null));
                continue;
            }
            BotScrollManager.EquipQuote quote = BotScrollManager.equipMarketQuote(entry, bot, eq);
            if (quote == null || quote.curveQuoteMeso() <= 0) {
                out.add(new ListingVerdict(id, 1, 0, npcWhole, shopPrice, 0, "no price basis", null));
                continue;
            }
            int ask = equipUnitAsk(book, quote, now);
            ask = (int) Math.min(Integer.MAX_VALUE, BotMarketMath.humanizeAsk(ask, bot.getId()));
            if (quote.band() == 0 && shopPrice > 0 && ask >= shopPrice) {
                ask = shopPrice - 1; // a clean piece competes with the NPC counter; a roll doesn't
            }
            if (ask <= 0) {
                out.add(new ListingVerdict(id, 1, 0, npcWhole, shopPrice, 0, "no price basis", null));
                continue;
            }
            long premium = listingPremium(ask, 1, npcWhole);
            if (premium <= 0) {
                out.add(new ListingVerdict(id, 1, ask, npcWhole, shopPrice, premium,
                        "npc sale pays better", null));
                continue;
            }
            if (premium < slotWorthMesos()) {
                out.add(new ListingVerdict(id, 1, ask, npcWhole, shopPrice, premium,
                        "premium not worth a slot", null));
                continue;
            }
            out.add(new ListingVerdict(id, 1, ask, npcWhole, shopPrice, premium, "list",
                    new ListingPlan(eq, (short) 1, (short) 1, ask)));
        }
    }

    /** Highest band probed for calibration evidence — covers every real scroll outcome. */
    private static final int CALIBRATION_BAND_PROBE = 12;

    /** The reproduction curve pinned to the bands of this item the bot has price beliefs about;
     *  the raw curve quote when it has none. */
    private static double calibratedCurveQuote(BotMarketBook book, BotScrollManager.EquipQuote quote,
                                               long now) {
        List<BotMarketMath.Sample> observed = new ArrayList<>();
        for (int b = 0; b <= CALIBRATION_BAND_PROBE; b++) {
            long k = BotMarketMath.priceKey(quote.itemId(), b);
            double conf = book.privateConfidence(k, now);
            if (conf <= 0) {
                continue;
            }
            double price = book.perceivedPrice(k, now);
            if (price > 0) {
                observed.add(new BotMarketMath.Sample(b, price, conf));
            }
        }
        double pinned = BotMarketMath.quoteFromCurve(
                BotMarketMath.curveCalibration(observed, quote.bandCurve()),
                quote.bandCurve(), quote.band());
        return pinned > 0 ? pinned : quote.curveQuoteMeso();
    }

    /** Price-key quality band of any item: rolled equips band by quality, everything else 0. */
    static int bandOf(Item item) {
        return item instanceof Equip eq
                ? BotScrollManager.equipQualityBand(ItemInformationProvider.getInstance(), eq)
                : 0;
    }

    /** Stacks that justify a trip on their own: NPC-shop staples only ever tag along — a bag of
     *  potions is never the REASON to walk to the market. */
    static int tripWorthyCount(List<ListingPlan> listings) {
        int n = 0;
        for (ListingPlan p : listings) {
            if (npcShopPrice.price(p.item().getItemId()) <= 0) {
                n++;
            }
        }
        return n;
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
        if (entry.fmPlanPending) {
            return; // an off-thread plan is already in flight
        }
        // The listing plan is HEAVY since equips joined the shelf (reproduction DP + farm costs
        // per piece) — same lesson as scheduleScrollPlan: never on the bot tick thread. Compute
        // on the shared decide pool, then start the trip back on the scheduler thread.
        entry.fmPlanPending = true;
        BotGrindAdvisor.DECIDE_POOL.execute(() -> {
            boolean stallServiceDue;
            boolean fredrickDue;
            List<ListingPlan> listable;
            long t0 = BotPerformanceMonitor.start();
            try {
                long planNow = System.currentTimeMillis();
                stallServiceDue = entry.nextStallServiceAtMs > 0 && planNow >= entry.nextStallServiceAtMs;
                fredrickDue = fredrickPickupDue(entry, bot, planNow);
                listable = selectListings(entry, bot, planNow);
                entry.fmHasShoutSurplus = !BotInventoryManager.collectMarketableEquips(entry, bot).isEmpty();
            } catch (RuntimeException e) {
                entry.fmPlanPending = false;
                return; // WZ/inventory hiccup off-thread — the next scan retries
            } finally {
                BotPerformanceMonitor.recordSince("fm-plan", t0);
            }
            entry.fmLastTripWorthy = tripWorthyCount(listable) >= MIN_LISTINGS_TO_TRIP;
            boolean sd = stallServiceDue;
            boolean fd = fredrickDue;
            List<ListingPlan> plans = listable;
            BotManager.after(0, () -> {
                entry.fmPlanPending = false;
                startMarketTrip(entry, bot, sd, fd, plans);
            });
        });
    }

    /** Arm the market errand from a computed plan — scheduler thread. Re-checks the gating state
     *  (it may have moved while the plan computed off-thread); a stale plan just no-ops. */
    private static void startMarketTrip(BotEntry entry, Character bot, boolean stallServiceDue,
                                        boolean fredrickDue, List<ListingPlan> listable) {
        long now = System.currentTimeMillis();
        if (!BotAutopilotManager.isActive(entry) || bot.getMap() == null) {
            return;
        }
        boolean chilling = entry.chillSession;
        if (!chilling && !BotBreakManager.onRestBreak(entry, bot, now)) {
            return;
        }
        if (entry.fmErrandMapId != -1 || entry.gachaErrandMapId != -1
                || entry.questErrandMapId != -1 || entry.jobErrandMapId != -1) {
            return;
        }
        // A chilling bot will also just go browse (its book still learns); a break-bot needs a
        // reason - and NPC-shop staples don't count as one (they only tag along). Proceeds
        // waiting at Fredrick ARE a reason of their own: a bot whose whole surplus sold and
        // closed to Fredrick would otherwise never trip again to collect its wealth. A social roll
        // also sends an empty-handed break-bot in just to hang out - the FM stays busy and its book
        // still learns from browsing (satiation gates repeats).
        boolean social = !chilling
                && ThreadLocalRandom.current().nextDouble() < BotManager.cfg.FM_SOCIAL_BREAK_CHANCE;
        // A dedicated shout-sell trip: a break bot with sellable gear but no other market reason goes
        // to stand at the entrance and hawk it — rare on a break, but chill days always trip anyway
        // (owner spec). The exit-leg shout stand then does the actual selling.
        boolean shoutTrip = !chilling && entry.fmHasShoutSurplus
                && ThreadLocalRandom.current().nextDouble() < SHOUT_SELL_BREAK_TRIP_CHANCE;
        if (!stallServiceDue && !fredrickDue && tripWorthyCount(listable) < MIN_LISTINGS_TO_TRIP
                && !chilling && !social && !shoutTrip) {
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
        entry.fmVisitedMarket = false;
        entry.fmShoutedThisTrip = false;
        entry.fmShoutUntilMs = 0L;
        entry.fmPlannedListings = listable; // staged at the stall without re-pricing on-tick
        entry.fmErrandProgress.begin(now);
        entry.fmPhaseDeadlineAtMs = now + ERRAND_TIMEOUT_MS;
        trace(entry, "trip armed: town=" + town + " listable=" + listable.size()
                + " tripworthy=" + tripWorthyCount(listable)
                + " stallService=" + stallServiceDue + " fredrick=" + fredrickDue);
        reply.accept(entry, stallServiceDue ? BotMarketChatter.tripService()
                : fredrickDue && tripWorthyCount(listable) < MIN_LISTINGS_TO_TRIP
                        ? BotMarketChatter.tripFredrick()
                : tripWorthyCount(listable) >= MIN_LISTINGS_TO_TRIP
                        ? BotMarketChatter.tripSell()
                        : BotMarketChatter.tripWindow());
    }

    /**
     * Steady-state login seeding (the {@link BotBreakManager#loginBreakChance} trick applied to
     * the market): a random snapshot of an established population has bots mid-market-day and
     * stalls live, but a restart wipes that state (open stalls close to Fredrick) and the normal
     * path only rebuilds it once break RNG parks a reasoned bot townside — leaving the FM empty
     * for the first hour. At login, a bot with a market reason rolls to start its market day now:
     * backlog holders (closed stall / proceeds to restore) at {@link #STALL_REBUILD_LOGIN_CHANCE},
     * ordinary sellers at their steady-state mid-trip fraction. The seed only arms a town break
     * and an immediate scan — {@link #tickScan}'s regular reason checks still own the decision,
     * so nothing is faked and a bot whose reason evaporates simply rests.
     */
    static void maybeSeedLoginMarketDay(BotEntry entry, Character bot, long now) {
        if (!BotManager.cfg.FM_MARKET_ENABLED || bot == null || bot.getMap() == null) {
            return;
        }
        // NOT the tickScan isActive gate: at login the autopilot hasn't decided yet
        // (autopilotMapId still -1), which silently killed every seed on the first live round.
        // What actually matters here is "no online owner supervising" — companions stay put.
        if (entry.owner != null && entry.owner != bot && entry.owner.isLoggedin()) {
            return;
        }
        if (entry.fmErrandMapId != -1 || entry.gachaErrandMapId != -1
                || entry.questErrandMapId != -1 || entry.jobErrandMapId != -1) {
            return;
        }
        BotPersonality p = entry.personality != null ? entry.personality : BotPersonality.defaults();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        // The DB probe only runs behind its passed roll; logins are staggered by the fast-start
        // ramp. NO inventory/pricing scan here (heavy since equips joined the shelf) — the seed
        // just wakes the scan, and tickScan's off-thread plan owns the reason check.
        boolean seed = rnd.nextDouble() < STALL_REBUILD_LOGIN_CHANCE && hasFredrickHoldings(bot);
        if (!seed) {
            seed = rnd.nextDouble() < loginMarketTripChance(p.breakFreqPerHour());
        }
        if (!seed) {
            return;
        }
        if (!entry.chillSession && !BotBreakManager.onRestBreak(entry, bot, now)) {
            BotBreakManager.startTownBreak(entry, bot, now); // routes to town via restErrand if afield
        }
        entry.nextFmScanAtMs = now; // first scan fires as soon as the bot is resting townside
    }

    /** Steady-state probability a seller is mid-market-trip at a random login instant:
     *  trips/hr (≈ its break cadence, since reasoned rest breaks become trips) × trip length. */
    static double loginMarketTripChance(double breakFreqPerHour) {
        return Math.min(0.35, Math.max(0.0, breakFreqPerHour) * MARKET_TRIP_MEAN_MIN / 60.0);
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
        if (fredrickPickupDue(entry, bot, now)) {
            return true;
        }
        // Cached verdict from the last off-thread listing plan — this runs on break-destination
        // decides (tick thread), which must never pay the full pricing scan.
        return entry.fmLastTripWorthy;
    }

    /** Slow-cadence probe (one DB read per bot-hour): is Fredrick holding proceeds worth a trip?
     *  Cached on the entry; cleared the moment a pickup succeeds ({@link #tickFredrick}). */
    static boolean fredrickPickupDue(BotEntry entry, Character bot, long now) {
        if (now >= entry.nextFredrickProbeAtMs) {
            entry.nextFredrickProbeAtMs = now + 3_600_000L + BotManager.randMs(0, 1_800_000);
            entry.fredrickPickupPending = hasFredrickHoldings(bot);
        }
        return entry.fredrickPickupPending;
    }

    // ---- errand tick ---------------------------------------------------------------------------

    /** Drives an active market session; true when the tick is consumed. Wrapped so the FM errand
     *  state machine (travel/browse/stall/fredrick/shout-stand) reports as its own
     *  {@code common-fm-errand} perf section instead of hiding inside the grind dispatch. */
    static boolean tickErrand(BotEntry entry, Character bot, boolean runAiTick) {
        if (entry.fmErrandMapId == -1) {
            return false;
        }
        long perfStart = BotPerformanceMonitor.start();
        try {
            return tickErrandBody(entry, bot, runAiTick);
        } finally {
            BotPerformanceMonitor.recordSince("common-fm-errand", perfStart);
        }
    }

    private static boolean tickErrandBody(BotEntry entry, Character bot, boolean runAiTick) {
        long now = System.currentTimeMillis();
        if (entry.fmErrandProgress.stalled(now, ERRAND_TIMEOUT_MS) || now > entry.fmPhaseDeadlineAtMs) {
            trace(entry, "watchdog: " + (now > entry.fmPhaseDeadlineAtMs ? "phase deadline" : "no progress")
                    + " in " + FM_PHASE_NAMES[entry.fmPhase] + " at map " + bot.getMapId());
            leaveVisitedStall(entry); // don't leak a merchant visitor slot if we fizzle mid-browse
            // NEVER release the errand while still inside the FM maps: they're off the world
            // graph, so a bot dropped here has no route anywhere and strands (live-observed).
            // A fizzle inside pivots to the exit walk; a wedged exit walk falls back to the
            // exact thing the exit portal script (market00.js) does — warp to the saved town.
            if (!isFmMap(bot.getMapId())) {
                finishErrand(entry, bot, BotMarketChatter.tripFizzled());
                return false;
            }
            if (entry.fmPhase == PHASE_EXIT) {
                warpOutOfMarket(bot);
                finishErrand(entry, bot, null);
                return false;
            }
            reply.accept(entry, BotMarketChatter.tripFizzled());
            advancePhase(entry, PHASE_EXIT, now);
            entry.fmErrandProgress.begin(now); // fresh progress clock for the exit legs
            return true;
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
                    advancePhase(entry, nextFromEntrance(entry, bot, false), now);
                    return true;
                }
                Portal market = bot.getMap() != null ? bot.getMap().getPortal("market00") : null;
                if (market == null) {
                    finishErrand(entry, bot, BotMarketChatter.noEntrance());
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
                    finishErrand(entry, bot, BotMarketChatter.marketPacked());
                    return false;
                }
                entry.fmErrandProgress.touch(now);
                return BotTravelManager.walkToPortalAndEnter(entry, bot, roomPortal, now, runAiTick);
            }
            case PHASE_SETUP -> {
                return tickSetup(entry, bot, runAiTick, now);
            }
            case PHASE_FREDRICK -> {
                return tickFredrick(entry, bot, runAiTick, now);
            }
            case PHASE_SHOUT -> {
                return tickShout(entry, bot, runAiTick, now);
            }
            case PHASE_BROWSE -> {
                return tickBrowse(entry, bot, runAiTick, now);
            }
            case PHASE_EXIT -> {
                if (!isFmMap(bot.getMapId())) {
                    finishErrand(entry, bot, null); // back in the world
                    return false;
                }
                if (entry.fmRoomMapId != -1 && bot.getMapId() == FM_ENTRANCE) {
                    // room -> entrance hop done: fresh deadline for the entrance -> town leg,
                    // plus the Fredrick stop when he still holds proceeds — the bag is at its
                    // emptiest right here (stall stock just left it), so the all-or-nothing
                    // reclaim has its best odds.
                    entry.fmRoomMapId = -1;
                    entry.fmPhaseDeadlineAtMs = now + PHASE_DEADLINE_MS;
                    int next = nextFromEntrance(entry, bot, true);
                    if (next != PHASE_EXIT) {
                        advancePhase(entry, next, now);
                        return true;
                    }
                }
                // Before walking out, maybe stand a while at the entrance to shout-sell surplus gear
                // so shoppers can click-invite (owner spec). Decided once per trip (banked via
                // fmShoutedThisTrip); runs after any Fredrick stop since that resumes to PHASE_EXIT.
                if (bot.getMapId() == FM_ENTRANCE && !entry.fmShoutedThisTrip) {
                    entry.fmShoutedThisTrip = true;
                    if (shoutWorthy(entry, bot)) {
                        entry.fmStandSpot = null;
                        advancePhase(entry, PHASE_SHOUT, now);
                        return true;
                    }
                }
                Portal out = bot.getMap() != null ? bot.getMap().getPortal("out00") : null;
                if (out == null) {
                    warpOutOfMarket(bot); // an FM map without out00 shouldn't exist — script-mirror out
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

    static boolean isFmMap(int mapId) {
        return mapId == FM_ENTRANCE || constants.game.GameConstants.isFreeMarketRoom(mapId);
    }

    /** Arriving at the entrance (inbound or heading out): swing by Fredrick first when he holds
     *  closed-stall proceeds for this bot and this trip hasn't settled with him yet. */
    private static int nextFromEntrance(BotEntry entry, Character bot, boolean exiting) {
        entry.fmVisitedMarket = true; // the trip reached the market — full satiation applies
        if (entry.fmFredrickState != 2 && hasFredrickHoldings(bot)) {
            entry.fmFredrickOnExit = exiting;
            entry.fmStandSpot = null;
            return PHASE_FREDRICK;
        }
        return exiting ? PHASE_EXIT : PHASE_TO_ROOM;
    }

    /** The trip's carried listing plan, minus anything that left the bag since planning. */
    private static List<ListingPlan> validPlans(Character bot, List<ListingPlan> plans) {
        List<ListingPlan> valid = new ArrayList<>(plans.size());
        for (ListingPlan p : plans) {
            if (BotInventoryManager.hasItem(bot, p.item()) && p.item().getQuantity() >= p.perBundle()) {
                valid.add(p);
            }
        }
        return valid;
    }

    /**
     * Walk to Fredrick and reclaim closed-stall proceeds (items + merchant mesos). The reclaim is
     * ALL-OR-NOTHING server logic (canRetrieveFromFredrick): with a too-full bag nothing moves
     * and Fredrick simply keeps holding — failures retry on the exit leg and then on later trips
     * (hourly probe), after the normal sell-trash/resupply cycles have freed bag space. While
     * anything remains uncollected the trip stays browse-only (see tickSetup: opening a stall
     * would DELETE the stored rows). Never loses items.
     */
    private static boolean tickFredrick(BotEntry entry, Character bot, boolean runAiTick, long now) {
        if (bot.getMapId() != FM_ENTRANCE) { // bumped out mid-walk — rejoin the machine
            entry.fmPhase = entry.fmFredrickOnExit ? PHASE_EXIT : PHASE_ENTER;
            return true;
        }
        int resumePhase = entry.fmFredrickOnExit ? PHASE_EXIT : PHASE_TO_ROOM;
        server.life.NPC fredrick = bot.getMap().getNPCById(FREDRICK_NPC);
        if (fredrick == null) {
            entry.fmFredrickState = 2; // no counter on this map somehow — carry on
            advancePhase(entry, resumePhase, now);
            return true;
        }
        Point npcPos = fredrick.getPosition();
        if (entry.fmStandSpot == null) {
            entry.fmStandSpot = BotTravelManager.pickReachableApproachPoint(
                    entry, bot, npcPos, BotTravelManager.APPROACH_SPREAD_PX, FREDRICK_TRIGGER_RADIUS_PX);
            entry.fmStandBestDist = Integer.MAX_VALUE;
            entry.fmStandStuckSinceMs = now;
        }
        Point stand = entry.fmStandSpot;
        Point botPos = bot.getPosition();
        int distToNpc = Math.abs(botPos.x - npcPos.x) + Math.abs(botPos.y - npcPos.y);
        int distToStand = Math.abs(botPos.x - stand.x) + Math.abs(botPos.y - stand.y);
        if (distToNpc > FREDRICK_TRIGGER_RADIUS_PX && distToStand > 24) {
            if (distToStand < entry.fmStandBestDist - 4) {
                entry.fmStandBestDist = distToStand; // net progress re-arms the walk watchdog
                entry.fmStandStuckSinceMs = now;
            }
            if (now - entry.fmStandStuckSinceMs > PLACE_WALK_STUCK_MS) {
                entry.fmFredrickState = entry.fmFredrickOnExit ? 2 : 1; // counter unreachable now
                entry.fmStandSpot = null;
                advancePhase(entry, resumePhase, now);
                return true;
            }
            BotTravelManager.pinMoveTarget(entry, stand);
            BotTravelManager.movementStep.step(entry, stand, runAiTick);
            entry.fmErrandProgress.touch(now);
            return true;
        }
        BotTravelManager.clearMoveTargetPin(entry);
        entry.fmStandSpot = null;
        entry.marketBusy = true;
        try {
            fredrickRetrieve.retrieve(bot);
        } finally {
            entry.marketBusy = false;
        }
        trace(entry, "fredrick retrieve tried; still holding=" + hasFredrickHoldings(bot));
        if (!hasFredrickHoldings(bot)) {
            entry.fmFredrickState = 2;
            entry.fredrickPickupPending = false;
            reply.accept(entry, BotMarketChatter.fredrickCollected());
        } else if (!entry.fmFredrickOnExit) {
            entry.fmFredrickState = 1; // bag too full — retry on the way out
        } else {
            entry.fmFredrickState = 2;
            reply.accept(entry, BotMarketChatter.fredrickPartial());
        }
        advancePhase(entry, resumePhase, now);
        return true;
    }

    /** True while the bot is actively standing at the entrance to shout-sell (PHASE_SHOUT of a live
     *  FM errand). {@link BotShoutTradeManager} reads this to drive the fast emission cadence and to
     *  accept walk-up buyers — and to stand its opportunistic emission down while the stand owns it. */
    static boolean isShoutStanding(BotEntry entry) {
        return entry.fmErrandMapId != -1 && entry.fmPhase == PHASE_SHOUT;
    }

    /** Would this bot bother to stand and shout-sell right now? Needs surplus gear to advertise and
     *  (unless it's a chill "market day") an audience that could click-invite; then a high/near-certain
     *  roll (break/chill). Rolled ONCE at the exit transition, not per tick. */
    private static boolean shoutWorthy(BotEntry entry, Character bot) {
        if (!BotManager.cfg.FM_MARKET_ENABLED || bot.getMap() == null) {
            return false;
        }
        boolean chill = entry.chillSession;
        if (!chill && bot.getMap().getAllPlayers().size() < 2) {
            return false; // nobody around to sell to
        }
        if (BotInventoryManager.collectMarketableEquips(entry, bot).isEmpty()) {
            return false; // nothing worth advertising
        }
        BotPersonality p = entry.personality != null ? entry.personality : BotPersonality.defaults();
        double chance = chill
                ? SHOUT_STAND_CHILL_CHANCE
                : SHOUT_STAND_BREAK_CHANCE * (0.4 + 0.6 * p.chattiness());
        return ThreadLocalRandom.current().nextDouble() < chance;
    }

    /**
     * Stand at the FM entrance advertising surplus gear (owner spec: a deliberate shout-sell state so
     * shoppers can click-invite). Walk to a RANDOM spot on the entrance floor (bots that arrive on the
     * same portal must not stack — owner), hold there while {@link BotShoutTradeManager#emitAtStand}
     * shouts on a fast cadence and answers walk-up buyers, and every so often amble to a new random
     * spot so it moves around instead of freezing. A trade in progress freezes the dwell (never walk
     * off mid-sale). Then exit to out00.
     */
    private static boolean tickShout(BotEntry entry, Character bot, boolean runAiTick, long now) {
        if (bot.getMapId() != FM_ENTRANCE) {
            entry.fmStandSpot = null; // bumped off the entrance — just head out
            advancePhase(entry, PHASE_EXIT, now);
            return true;
        }
        // A shopper's trade window owns the bot now: hold still, keep the stand + watchdog alive, and
        // linger a touch after so a quick follow-up sale can happen.
        if (entry.shoutTradeActive() || bot.getTrade() != null) {
            BotTravelManager.clearMoveTargetPin(entry);
            entry.fmErrandProgress.touch(now);
            entry.fmPhaseDeadlineAtMs = Math.max(entry.fmPhaseDeadlineAtMs, now + PHASE_DEADLINE_MS);
            if (entry.fmShoutUntilMs > 0) {
                entry.fmShoutUntilMs = Math.max(entry.fmShoutUntilMs, now + 15_000L);
            }
            return true;
        }
        // Arm the dwell budget + first spot ONCE (break short, chill lingers).
        if (entry.fmShoutUntilMs == 0L) {
            long base = BotManager.randMs((int) SHOUT_STAND_MIN_MS, (int) SHOUT_STAND_MAX_MS);
            long dwell = entry.chillSession ? (long) SHOUT_STAND_CHILL_FACTOR * base : base;
            entry.fmShoutUntilMs = now + dwell;
            entry.fmPhaseDeadlineAtMs = now + dwell + PHASE_DEADLINE_MS; // don't let the watchdog cut it short
            entry.nextShoutEmitMs = now + BotManager.randMs(2_000, 8_000); // first shout shortly after settling
            entry.fmFidgetAtMs = now + BotManager.randMs((int) SHOUT_REPOSITION_MIN_MS, (int) SHOUT_REPOSITION_MAX_MS);
            entry.fmStandSpot = pickStandSpot(bot);
            entry.fmStandBestDist = Integer.MAX_VALUE;
            entry.fmStandStuckSinceMs = now;
            reply.accept(entry, BotMarketChatter.shoutStand());
        }
        Point stand = entry.fmStandSpot;
        boolean settled = stand != null && !entry.inAir && !entry.climbing
                && Math.abs(bot.getPosition().x - stand.x) + Math.abs(bot.getPosition().y - stand.y) <= 24;
        // Occasionally amble to a new random spot (move around + un-bunch), leaving time to settle.
        if (settled && now >= entry.fmFidgetAtMs && now < entry.fmShoutUntilMs - 6_000L) {
            entry.fmStandSpot = pickStandSpot(bot);
            entry.fmStandBestDist = Integer.MAX_VALUE;
            entry.fmStandStuckSinceMs = now;
            entry.fmFidgetAtMs = now + BotManager.randMs((int) SHOUT_REPOSITION_MIN_MS, (int) SHOUT_REPOSITION_MAX_MS);
            stand = entry.fmStandSpot;
            settled = false;
        }
        // Walk toward the (possibly new) spot; give up and settle in place if it can't be reached.
        if (stand != null && !settled) {
            int dist = Math.abs(bot.getPosition().x - stand.x) + Math.abs(bot.getPosition().y - stand.y);
            if (dist < entry.fmStandBestDist - 4) {
                entry.fmStandBestDist = dist;
                entry.fmStandStuckSinceMs = now;
            }
            if (now - entry.fmStandStuckSinceMs > PLACE_WALK_STUCK_MS) {
                entry.fmStandSpot = new Point(bot.getPosition()); // can't reach it — settle here
            } else {
                BotTravelManager.pinMoveTarget(entry, stand);
                BotTravelManager.movementStep.step(entry, stand, runAiTick);
                entry.fmErrandProgress.touch(now);
                return true;
            }
        }
        BotTravelManager.clearMoveTargetPin(entry);
        entry.fmErrandProgress.touch(now);
        BotShoutTradeManager.emitAtStand(entry, bot, now);
        if (now >= entry.fmShoutUntilMs) {
            entry.fmStandSpot = null;
            advancePhase(entry, PHASE_EXIT, now);
        }
        return true;
    }

    /**
     * A RANDOM reachable ground spot on the entrance floor strip (columns every
     * {@link #STALL_SPACING_PX}, same ground-snap + same-level guard as {@link #pickStallSpot}, kept
     * clear of portals). Random rather than a fixed "best" so bots arriving on the same portal spread
     * out instead of all converging on one spot (owner). Falls back to a one-column step off the
     * arrival point (never the portal itself), then to standing put.
     */
    private static Point pickStandSpot(Character bot) {
        server.maps.MapleMap map = bot.getMap();
        Point pos = bot.getPosition();
        if (map == null || map.getFootholds() == null) {
            return new Point(pos);
        }
        List<Point> candidates = new ArrayList<>();
        for (int step = 1; step <= MAX_STALL_SLOT_STEPS; step++) {
            for (int dir : new int[] {1, -1}) {
                Point spot = BotPhysicsEngine.pointBelowIndexed(map,
                        new Point(pos.x + dir * step * STALL_SPACING_PX, pos.y - 30));
                if (spot != null && Math.abs(spot.y - pos.y) <= 60 && !nearAnyPortal(map, spot)) {
                    candidates.add(spot);
                }
            }
        }
        if (!candidates.isEmpty()) {
            return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        }
        int dir = ThreadLocalRandom.current().nextBoolean() ? 1 : -1; // no clear column: at least step off the portal
        Point off = BotPhysicsEngine.pointBelowIndexed(map, new Point(pos.x + dir * STALL_SPACING_PX, pos.y - 30));
        return off != null && Math.abs(off.y - pos.y) <= 60 ? off : new Point(pos);
    }

    /**
     * The town this bot's market session returns to: the FREE_MARKET saved location the town
     * portal script stamped on the way in (non-destructive peek — {@code getSavedLocation}
     * CLEARS on read and the exit portal script still needs it). Henesys when unset (mirrors
     * market00.js's own fallback).
     */
    static int fmReturnTownMapId(Character bot) {
        int saved = bot.peekSavedLocation(SavedLocationType.FREE_MARKET.name());
        return saved > 0 ? saved : constants.id.MapId.HENESYS;
    }

    /** Last resort when the exit WALK wedges: exactly what the exit portal script (market00.js)
     *  does — consume the FREE_MARKET saved location and warp to it (Henesys fallback). */
    private static void warpOutOfMarket(Character bot) {
        int town = bot.getSavedLocation(SavedLocationType.FREE_MARKET.name());
        if (town <= 0) {
            town = constants.id.MapId.HENESYS;
        }
        bot.changeMap(town, "market00");
    }

    private static final String[] FM_PHASE_NAMES =
            {"TRAVEL", "ENTER", "TO_ROOM", "SETUP", "BROWSE", "EXIT", "FREDRICK", "SHOUT"};

    /** Trip trace on the MARKET_TX_CONSOLE flag: a handful of lines per trip, invaluable when a
     *  live funnel stalls somewhere between the town portal and a published stall. */
    private static void trace(BotEntry entry, String msg) {
        if (BotManager.cfg.MARKET_TX_CONSOLE) {
            log.info("fm[{}] {}", entry.bot != null ? entry.bot.getName() : "?", msg);
        }
    }

    private static void advancePhase(BotEntry entry, int phase, long now) {
        trace(entry, "phase -> " + FM_PHASE_NAMES[phase]);
        entry.fmPhase = phase;
        entry.fmPhaseDeadlineAtMs = now + PHASE_DEADLINE_MS;
        entry.fmErrandProgress.touch(now);
    }

    /**
     * Entrance -> room, humanlike (owner rule: closer rooms first, but not always): roulette
     * weighted toward the FRONT rooms and toward rooms that already have stalls — that's where
     * the market is (stock to browse for buyers, foot traffic for sellers), so activity clusters
     * into a real marketplace instead of scattering over 22 mostly-empty rooms. The random tail
     * still sends the occasional bot deep, so far rooms never fully die.
     */
    private static Portal pickRoomPortal(BotEntry entry, Character bot) {
        // Committed for this trip: keep walking to the SAME door. This runs every TO_ROOM tick,
        // and re-rolling the roulette per tick flip-flopped the walk target between 22 portals -
        // bots oscillated at the entrance until the watchdog fizzled EVERY trip (live market
        // deadlock: two restarts with zero stalls). The old picker was per-tick-stable by
        // construction (bot-id hash); a roulette must bank its winner instead.
        if (entry.fmRoomMapId != -1) {
            for (int i = 1; i <= 22; i++) {
                Portal p = bot.getMap().getPortal(String.format("in%02d", i));
                if (p != null && p.getTargetMapId() == entry.fmRoomMapId) {
                    return p;
                }
            }
            entry.fmRoomMapId = -1; // committed room's door not on this map - re-roll below
        }
        List<Portal> candidates = new ArrayList<>();
        for (int i = 1; i <= 22; i++) {
            Portal p = bot.getMap().getPortal(String.format("in%02d", i));
            if (p != null && constants.game.GameConstants.isFreeMarketRoom(p.getTargetMapId())) {
                candidates.add(p);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        Map<Integer, Integer> stallsByRoom = new java.util.HashMap<>();
        for (HiredMerchant hm : bot.getWorldServer().getActiveMerchants()) {
            stallsByRoom.merge(hm.getMapId(), 1, Integer::sum);
        }
        double[] weights = new double[candidates.size()];
        double total = 0;
        for (int i = 0; i < candidates.size(); i++) {
            int stalls = stallsByRoom.getOrDefault(candidates.get(i).getTargetMapId(), 0);
            weights[i] = (1.0 + 2.0 * stalls) / (1.0 + i / 2.0);
            total += weights[i];
        }
        double roll = ThreadLocalRandom.current().nextDouble() * total;
        int idx = 0;
        while (idx < weights.length - 1 && (roll -= weights[idx]) > 0) {
            idx++;
        }
        Portal chosen = candidates.get(idx);
        entry.fmRoomMapId = chosen.getTargetMapId();
        return chosen;
    }

    /** In the room: walk to a free slot on this floor, place + stock + publish, then browse. */
    private static boolean tickSetup(BotEntry entry, Character bot, boolean runAiTick, long now) {
        // A live stall from a previous session (or no stock worth listing) -> browse-only trip.
        // Listings were planned off-thread at trip start (startMarketTrip); staging only
        // re-validates the items still exist — no re-pricing on the tick thread.
        // A live stall from a previous session: tend it (reprice + restock) instead of opening a
        // new one, then browse — the "next session: collect/restock/reprice" step (design 8.2).
        HiredMerchant liveStall = bot.getWorldServer().getHiredMerchant(bot.getId());
        if (liveStall != null) {
            serviceLiveStall(entry, bot, liveStall, now);
            advancePhase(entry, PHASE_BROWSE, now);
            return true;
        }
        List<ListingPlan> listings = validPlans(bot, entry.fmPlannedListings);
        // NEVER open while Fredrick still holds proceeds: stocking a new stall saves over the
        // MERCHANT store, whose save DELETEs the uncollected rows first (saveItemsMerchant) -
        // the item loss the real client prevents by refusing to open until you collect. The
        // entrance stop already tried to collect this trip; if the bag couldn't take it all,
        // this trip is browse-only and the pickup re-trips on the hourly probe.
        String skip = listings.isEmpty()
                        ? "no valid listings (" + entry.fmPlannedListings.size() + " planned)"
                : hasFredrickHoldings(bot) ? "fredrick still holds proceeds"
                : !ensurePermit(entry, bot) ? "no permit" : null;
        if (skip != null) {
            trace(entry, "setup -> browse-only: " + skip);
            advancePhase(entry, PHASE_BROWSE, now);
            return true;
        }
        if (entry.fmStandSpot == null) {
            entry.fmStandSpot = pickStallSpot(entry, bot);
            if (entry.fmStandSpot == null) {
                advancePhase(entry, PHASE_BROWSE, now); // this floor is full - browse and go
                return true;
            }
            entry.fmStandBestDist = Integer.MAX_VALUE;
            entry.fmStandStuckSinceMs = now;
        }
        Point stand = entry.fmStandSpot;
        int dist = Math.abs(bot.getPosition().x - stand.x) + Math.abs(bot.getPosition().y - stand.y);
        if (entry.inAir || entry.climbing || dist > 24) {
            if (dist < entry.fmStandBestDist - 4) {
                entry.fmStandBestDist = dist; // net progress re-arms the walk watchdog
                entry.fmStandStuckSinceMs = now;
            }
            if (now - entry.fmStandStuckSinceMs > PLACE_WALK_STUCK_MS) {
                nextPlacementTry(entry, now); // can't reach this slot - try the next one over
                return true;
            }
            BotTravelManager.pinMoveTarget(entry, stand);
            BotTravelManager.movementStep.step(entry, stand, runAiTick);
            entry.fmErrandProgress.touch(now);
            return true;
        }
        BotTravelManager.clearMoveTargetPin(entry);
        if (!PlayerInteractionHandler.canPlaceStore(bot) || stallSpotTaken(bot)) {
            nextPlacementTry(entry, now); // occupied (or a stall appeared mid-walk) - slide to the next slot
            return true;
        }
        openAndStockStall(entry, bot, listings, now);
        advancePhase(entry, PHASE_BROWSE, now);
        return true;
    }

    /** Consume the current candidate slot (unreachable or refused) and move to the next; out of
     *  tries -> browse-only, never a fizzled errand over placement. */
    private static void nextPlacementTry(BotEntry entry, long now) {
        entry.fmPlaceTries++;
        entry.fmStandSpot = null;
        entry.fmErrandProgress.touch(now);
        if (entry.fmPlaceTries >= MAX_PLACE_TRIES) {
            advancePhase(entry, PHASE_BROWSE, now);
        }
    }

    /**
     * A stall spot on the floor strip the bot is standing on: slot columns every
     * {@link #STALL_SPACING_PX} out from where it entered (per-bot fill direction for variety),
     * ground-snapped to the SAME walking level and screened by the same rules
     * {@link PlayerInteractionHandler#canPlaceStore} enforces (portal buffer, merchant spacing)
     * so the final check passes. A purely horizontal walk converges - the old cross-platform
     * targets anchored off the exit portal were often unreachable and timed the phase out
     * (live-observed fizzle loop). {@code fmPlaceTries} skips slots already consumed this
     * session. Null = no free slot on this floor.
     */
    private static Point pickStallSpot(BotEntry entry, Character bot) {
        server.maps.MapleMap map = bot.getMap();
        if (map == null || map.getFootholds() == null) {
            return null;
        }
        Point pos = bot.getPosition();
        List<MapObject> stalls = map.getMapObjectsInRange(pos, Double.POSITIVE_INFINITY,
                List.of(MapObjectType.HIRED_MERCHANT));
        boolean rightFirst = (bot.getId() & 1) == 0;
        int skipped = 0;
        for (int step = 0; step <= MAX_STALL_SLOT_STEPS; step++) {
            for (int side = 0; side < (step == 0 ? 1 : 2); side++) {
                int dir = (side == 0) == rightFirst ? 1 : -1;
                Point spot = BotPhysicsEngine.pointBelowIndexed(map,
                        new Point(pos.x + dir * step * STALL_SPACING_PX, pos.y - 30));
                if (spot == null || Math.abs(spot.y - pos.y) > 60) {
                    continue; // ran off this floor strip (edge, stairwell gap, lower level)
                }
                if (nearAnyPortal(map, spot)) {
                    continue; // keep every doorway/arrival point clear (canPlaceStore only checks
                              // teleport portals, which misses script exits like out00 - live bug)
                }
                if (nearStall(stalls, spot)) {
                    continue;
                }
                if (skipped++ < entry.fmPlaceTries) {
                    continue; // consumed on an earlier try this session
                }
                return spot;
            }
        }
        return null;
    }

    private static boolean nearAnyPortal(server.maps.MapleMap map, Point spot) {
        for (Portal p : map.getPortals()) {
            if (p.getPosition() != null && p.getPosition().distance(spot) < 130.0) {
                return true;
            }
        }
        return false;
    }

    private static boolean nearStall(List<MapObject> stalls, Point spot) {
        for (MapObject o : stalls) {
            if (o.getPosition().distanceSq(spot) < STALL_MIN_SPACING_SQ) {
                return true;
            }
        }
        return false;
    }

    /** Live 200px spacing re-check at the actual stand position, right before opening: catches a stall
     *  another bot published while this one walked to its slot (canPlaceStore's own 151px window can
     *  miss it, and its check isn't atomic with publish). */
    private static boolean stallSpotTaken(Character bot) {
        List<MapObject> stalls = bot.getMap().getMapObjectsInRange(bot.getPosition(),
                Double.POSITIVE_INFINITY, List.of(MapObjectType.HIRED_MERCHANT));
        for (MapObject o : stalls) {
            if (o instanceof HiredMerchant hm && hm.getOwnerId() != bot.getId()
                    && o.getPosition().distanceSq(bot.getPosition()) < STALL_MIN_SPACING_SQ) {
                return true;
            }
        }
        return false;
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
        reply.accept(entry, BotMarketChatter.permitBought());
        return true;
    }

    /** Create + stock + publish under the marketBusy tick gate; tape LIST rows per slot. */
    private static void openAndStockStall(BotEntry entry, Character bot, List<ListingPlan> listings, long now) {
        entry.marketBusy = true;
        try {
            HiredMerchant merchant = HiredMerchant.createFor(bot, stallName(bot), stallSkin(bot));
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
                        plan.item().getItemId(), bandOf(plan.item()), plan.bundles() * plan.perBundle(),
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
            entry.nextStallServiceAtMs = now + BotManager.randMs(STALL_SERVICE_MIN_MS, STALL_SERVICE_MAX_MS);
            trace(entry, "stall published: " + listed + " slots at map " + bot.getMapId());
            reply.accept(entry, BotMarketChatter.stallOpened(listed));
        } catch (RuntimeException e) {
            log.warn("stall setup failed for {}: {}", bot.getName(), e.toString());
        } finally {
            entry.marketBusy = false;
        }
    }

    /**
     * Tend an already-open stall on a re-visit (living-economy S2): drop sold-out slots, reprice the
     * survivors toward current belief, and restock any free slots from freshly-marketable bag stock.
     * Reprice + purge happen atomically under the stall's item monitor
     * ({@link HiredMerchant#botServiceReprice}) so buys can't interleave; restock reuses the same
     * add + inventory-debit pair as opening. Headless (world-scoped stall lookup) — works from any FM
     * room the bot happens to browse. All under the {@code marketBusy} tick gate; one save at the end.
     */
    private static void serviceLiveStall(BotEntry entry, Character bot, HiredMerchant merchant, long now) {
        entry.marketBusy = true;
        try {
            BotMarketBook book = BotMarketBook.of(entry, bot);
            Map<Long, Long> competingAsks = scanCompetingAsks(bot);
            int free = merchant.botServiceReprice(psi -> serviceReprice(bot, book, psi, competingAsks, now));
            int restocked = 0;
            for (ListingPlan plan : validPlans(bot, entry.fmPlannedListings)) {
                if (restocked >= free) {
                    break;
                }
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
                        plan.item().getItemId(), bandOf(plan.item()), plan.bundles() * plan.perBundle(),
                        plan.unitPrice(), bot.getId(), null, bot.getMapId());
                restocked++;
            }
            try {
                merchant.saveItems(false);
            } catch (Exception e) {
                log.warn("stall service saveItems failed for {}: {}", bot.getName(), e.toString());
            }
            entry.nextStallServiceAtMs = now + BotManager.randMs(STALL_SERVICE_MIN_MS, STALL_SERVICE_MAX_MS);
            trace(entry, "stall serviced: restocked " + restocked + " of " + free + " free slots");
            if (restocked > 0) {
                reply.accept(entry, BotMarketChatter.stallRestocked(restocked));
            }
        } finally {
            entry.marketBusy = false;
        }
    }

    /**
     * One-time scan of every OTHER currently-open stall on this map: cheapest unit ask per banded
     * priceKey (design sec 5 undercut evidence — "just under the cheapest competing ask"). Computed
     * once per stall-service visit (not per slot) since the room's merchant list doesn't change
     * mid-service; reuses the same {@code MapObjectType.HIRED_MERCHANT} enumeration tickBrowse scans.
     */
    private static Map<Long, Long> scanCompetingAsks(Character bot) {
        Map<Long, Long> best = new HashMap<>();
        for (MapObject o : bot.getMap().getMapObjectsInRange(bot.getPosition(),
                Double.POSITIVE_INFINITY, List.of(MapObjectType.HIRED_MERCHANT))) {
            if (!(o instanceof HiredMerchant hm) || hm.getOwnerId() == bot.getId() || !hm.isOpen()) {
                continue;
            }
            for (PlayerShopItem psi : hm.getItems()) {
                if (!psi.isExist() || psi.getBundles() <= 0) {
                    continue;
                }
                Item it = psi.getItem();
                long unit = Math.round(psi.getPrice() / (double) Math.max(1, it.getQuantity()));
                if (unit <= 0) {
                    continue;
                }
                long key = BotMarketMath.priceKey(it.getItemId(), bandOf(it));
                best.merge(key, unit, Math::min);
            }
        }
        return best;
    }

    /**
     * New per-bundle price for a live slot: step the current ask toward the best evidence at the
     * slot's banded key — the bot's own belief undercut just below the cheapest visible competing
     * ask ({@link BotMarketMath#undercutTarget}, {@link #UNDERCUT_FRACTION}; never chasing above the
     * bot's own perceived value) — by {@link #REPRICE_PRESSURE}, floored so one visit never cuts an
     * ask below {@link #REPRICE_MAX_DROP} of itself nor below the per-unit NPC sell-back. A
     * belief-less slot with no competition keeps its price. Reuses {@link BotMarketMath#repriceAsk}
     * and {@link BotMarketMath#undercutTarget} — no parallel pricing.
     */
    private static int serviceReprice(Character bot, BotMarketBook book, PlayerShopItem psi,
                                      Map<Long, Long> competingAsks, long now) {
        Item it = psi.getItem();
        int perBundle = Math.max(1, it.getQuantity());
        long key = BotMarketMath.priceKey(it.getItemId(), bandOf(it));
        double perceived = book.perceivedPrice(key, now);
        double confidence = book.privateConfidence(key, now);
        double curUnitAsk = psi.getPrice() / (double) perBundle;
        long npcUnit = npcSell.price(it.getItemId(), 1);
        double reservation = Math.max(npcUnit, curUnitAsk * REPRICE_MAX_DROP);
        long competingUnitAsk = competingAsks.getOrDefault(key, 0L);
        double newUnit = repriceWithUndercut(curUnitAsk, perceived, confidence, reservation, competingUnitAsk);
        long humanUnit = BotMarketMath.humanizeAsk(Math.round(newUnit), bot.getId());
        long newBundle = humanUnit * perBundle;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, newBundle));
    }

    /**
     * Core reprice decision (design sec 5), pulled out of {@link #serviceReprice} so the undercut
     * wiring is unit-testable without a live stall/Character: undercut just under the cheapest
     * visible competing ask (never chasing above the bot's own perception), then step
     * gap-proportionally toward that evidence, floored at {@code reservation}.
     */
    static double repriceWithUndercut(double curUnitAsk, double perceived, double confidence,
                                      double reservation, double competingUnitAsk) {
        double evidence = BotMarketMath.undercutTarget(perceived, competingUnitAsk, UNDERCUT_FRACTION);
        return BotMarketMath.repriceAsk(curUnitAsk, evidence, confidence, REPRICE_PRESSURE, reservation);
    }

    /** Browse every other stall on this room map: observations always, bargains sparingly.
     *  Returns how many other stalls there were (0 = empty room, the caller cuts the dwell). */
    /**
     * Browse the room like a shopper (owner spec, SoloMapling borrow): walk up to each stall with a
     * ±50px approach jitter (don't pile on the marker), register as a VISIBLE visitor, dwell a beat
     * while reading it + maybe buying, then move on — until the browse budget runs out. Replaces the
     * old instant one-tick sweep so browsing takes time and shows up in the merchant.
     */
    private static boolean tickBrowse(BotEntry entry, Character bot, boolean runAiTick, long now) {
        // Arm the browse plan once: snapshot the room's other OPEN stalls, shuffle, set the budget.
        if (entry.fmBrowseEndMs == 0L) {
            List<Integer> owners = new ArrayList<>();
            for (MapObject o : bot.getMap().getMapObjectsInRange(bot.getPosition(),
                    Double.POSITIVE_INFINITY, List.of(MapObjectType.HIRED_MERCHANT))) {
                if (o instanceof HiredMerchant hm && hm.getOwnerId() != bot.getId() && hm.isOpen()) {
                    owners.add(hm.getOwnerId());
                }
            }
            java.util.Collections.shuffle(owners, ThreadLocalRandom.current());
            entry.fmBrowseOwners = owners.stream().mapToInt(Integer::intValue).toArray();
            entry.fmBrowseIdx = 0;
            entry.fmVisitOwnerId = -1;
            entry.fmStandSpot = null;
            entry.fmBrowseUntilMs = 0L;
            int factor = entry.chillSession ? CHILL_DWELL_FACTOR : 1; // market day lingers
            entry.fmBrowseEndMs = now + (owners.isEmpty()
                    ? BotManager.randMs(3_000, 8_000) // empty room: glance around and go
                    : (long) factor * BotManager.randMs((int) BROWSE_MIN_MS, (int) BROWSE_MAX_MS));
        }
        entry.fmErrandProgress.touch(now);

        // Dwelling inside a stall we've registered at: leave once the dwell ends.
        if (entry.fmVisitOwnerId != -1) {
            if (now < entry.fmBrowseUntilMs) {
                return true; // looking over the wares
            }
            leaveVisitedStall(entry);
            entry.fmBrowseIdx++;
            entry.fmStandSpot = null;
        }

        // Empty room: just glance around for the short budget, then go.
        if (entry.fmBrowseOwners.length == 0) {
            if (now < entry.fmBrowseEndMs) {
                return true;
            }
            advancePhase(entry, PHASE_EXIT, now);
            return true;
        }
        // Budget spent or every stall seen -> head out.
        if (now >= entry.fmBrowseEndMs || entry.fmBrowseIdx >= entry.fmBrowseOwners.length) {
            advancePhase(entry, PHASE_EXIT, now);
            return true;
        }

        HiredMerchant target = openStallOf(bot, entry.fmBrowseOwners[entry.fmBrowseIdx]);
        if (target == null) {
            entry.fmBrowseIdx++; // stall closed/left since the snapshot — skip it
            entry.fmStandSpot = null;
            return true;
        }
        if (entry.fmStandSpot == null) {
            entry.fmStandSpot = approachSpot(bot, target.getPosition());
            entry.fmStandBestDist = Integer.MAX_VALUE;
            entry.fmStandStuckSinceMs = now;
        }
        Point stand = entry.fmStandSpot;
        int dist = Math.abs(bot.getPosition().x - stand.x) + Math.abs(bot.getPosition().y - stand.y);
        if (!entry.inAir && !entry.climbing && dist <= 40) {
            // Arrived: register as a visible visitor (may fail if the 3 slots are full — still browse),
            // read the stall + maybe buy, and linger a beat.
            BotTravelManager.clearMoveTargetPin(entry);
            target.addVisitor(bot);
            entry.fmVisitOwnerId = target.getOwnerId();
            observeStall(entry, bot, BotMarketBook.of(entry, bot), target, now);
            entry.fmBrowseUntilMs = now + BotManager.randMs(1_000, 2_500);
            return true;
        }
        // Still walking up, with the same stuck watchdog as the other FM walks.
        if (dist < entry.fmStandBestDist - 4) {
            entry.fmStandBestDist = dist;
            entry.fmStandStuckSinceMs = now;
        }
        if (now - entry.fmStandStuckSinceMs > PLACE_WALK_STUCK_MS) {
            entry.fmBrowseIdx++; // can't reach this stall — skip it
            entry.fmStandSpot = null;
            return true;
        }
        BotTravelManager.pinMoveTarget(entry, stand);
        BotTravelManager.movementStep.step(entry, stand, runAiTick);
        return true;
    }

    /** The bot's currently-OPEN target stall by owner id in this room (null if it closed/left). */
    private static HiredMerchant openStallOf(Character bot, int ownerId) {
        HiredMerchant hm = bot.getWorldServer().getHiredMerchant(ownerId);
        return hm != null && hm.isOpen() && hm.getMapId() == bot.getMapId() ? hm : null;
    }

    /** Release our visitor slot on the stall we were browsing (safe if it already closed/removed us).
     *  Uses {@code entry.bot} so cleanup paths without a Character in hand (clearFmErrand) can call it. */
    private static void leaveVisitedStall(BotEntry entry) {
        if (entry.fmVisitOwnerId == -1) {
            return;
        }
        Character bot = entry.bot;
        if (bot != null && bot.getWorldServer() != null) {
            HiredMerchant hm = bot.getWorldServer().getHiredMerchant(entry.fmVisitOwnerId);
            if (hm != null) {
                hm.removeVisitor(bot);
            }
        }
        entry.fmVisitOwnerId = -1;
    }

    /** A ground point beside a stall to walk to — same-floor snap with a ±50px jitter so bots don't
     *  pile onto the exact stall marker (SoloMapling's randomized-approach-point borrow). */
    private static Point approachSpot(Character bot, Point stallPos) {
        int dx = ThreadLocalRandom.current().nextInt(-50, 51);
        Point snapped = BotPhysicsEngine.pointBelowIndexed(bot.getMap(),
                new Point(stallPos.x + dx, stallPos.y - 30));
        return snapped != null ? snapped : new Point(stallPos);
    }

    /** Scan one stall's listings for a bargain — the per-stall body the browse loop runs on arrival
     *  at each merchant. A listing ask is an advertisement, not a clearing, so it no longer moves the
     *  price belief (that echo poisoned beliefs to remake cost); browsing still reads perceived value
     *  to spot underpriced buys. */
    private static void observeStall(BotEntry entry, Character bot, BotMarketBook book,
                                     HiredMerchant merchant, long now) {
        List<PlayerShopItem> items = merchant.getItems();
        for (int slot = 0; slot < items.size(); slot++) {
            PlayerShopItem psi = items.get(slot);
            if (!psi.isExist() || psi.getBundles() <= 0) {
                continue;
            }
            int perBundle = Math.max(1, psi.getItem().getQuantity());
            double unit = (double) psi.getPrice() / perBundle;
            long key = BotMarketMath.priceKey(psi.getItem().getItemId(), bandOf(psi.getItem()));
            maybeBargainBuy(entry, bot, book, merchant, slot, psi, unit, key, now);
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
        boolean gearUpgrade = false;
        if (psi.getItem() instanceof Equip stallEq) {
            // Gear demand: a rolled piece is bought as a combat UPGRADE within the buyer's own
            // combat ceiling — no prior price belief needed, which is what lets a fresh equip
            // market clear at all (and those clearings then teach everyone's books the bands).
            long ceiling = BotScrollManager.equipBuyCeilingMeso(bot, stallEq);
            if (ceiling <= 0 || psi.getPrice() > ceiling) {
                return; // not wearable / no upgrade / priced above its combat worth to this bot
            }
            gearUpgrade = true;
        } else if (psi.getItem().getItemId() / 10000 == BotScrollManager.SCROLL_ITEM_PREFIX) {
            // Scroll demand: like the equip branch, a fresh scroll market can't clear on belief alone.
            // Buy under a real willingness-to-pay ceiling = min over positive values of the combat-demand
            // worth and the replacement (obtain) cost. When a belief exists it still vetoes overpaying via
            // the margin check; with no belief the ceiling alone decides — which is what lets it clear.
            int scrollId = psi.getItem().getItemId();
            double ceiling = Double.POSITIVE_INFINITY;
            double combat = BotScrollManager.scrollCombatCeilingMeso(scrollId);
            if (combat > 0) {
                ceiling = combat;
            }
            double replacement = BotScrollManager.scrollMarketValueMeso(bot, scrollId);
            if (replacement > 0 && replacement < ceiling) {
                ceiling = replacement;
            }
            // Ceiling is PER-UNIT; psi.getPrice() is the whole bundle — compare the unit ask
            // (the wallet check below still pays the full bundle price).
            if (!Double.isFinite(ceiling) || unitAsk > ceiling) {
                return; // no ceiling, or priced above what the scroll is worth to this bot
            }
            double perceived = book.perceivedPrice(key, now);
            if (perceived > 0) {
                double margin = BotMarketMath.openingMargin(0.5, book.privateConfidence(key, now));
                if (unitAsk > perceived * (1.0 - Math.min(0.5, margin))) {
                    return; // belief still vetoes overpaying above the bot's own ask floor
                }
            }
        } else {
            double perceived = book.perceivedPrice(key, now);
            if (perceived <= 0) {
                return; // no idea what it's worth - not a bargain, just unknown
            }
            double margin = BotMarketMath.openingMargin(0.5, book.privateConfidence(key, now));
            if (unitAsk > perceived * (1.0 - Math.min(0.5, margin))) {
                return;
            }
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
            reply.accept(entry, BotMarketChatter.bargainBuy(gearUpgrade));
        } catch (RuntimeException e) {
            log.warn("bargain buy failed for {}: {}", bot.getName(), e.toString());
        } finally {
            entry.marketBusy = false;
        }
    }

    /** Deterministic per-bot stall sign (ASCII, invariant 3). A flavor corpus so a room of stalls
     *  reads like a real market instead of a wall of "surplus sale"; two independent hashes pick a
     *  headline and an optional tag so ~40 signs cover the population without a per-bot field. */
    private static final String[] STALL_HEADLINES = {
            "cheap stuff", "fair prices", "surplus sale", "come look", "good deals here",
            "clearance", "everything must go", "loot for sale", "grab a bargain", "quality goods",
            "no scams here", "leftovers", "priced to move", "trader's corner", "market finds",
            "extras and spares", "gear and scrolls", "stock up here", "haggle welcome", "fresh drops",
    };
    private static final String[] STALL_TAGS = {
            "", "", "", " (cheap!)", " - buy now", " ~ open", " - lvl up gear",
            " * good stock *", " - fair only", " - real prices",
    };

    private static String stallName(Character bot) {
        int id = bot.getId();
        long tagHash = (id * 2654435761L) >>> 8; // second, independent hash so headline/tag vary apart
        String headline = STALL_HEADLINES[Math.floorMod(id, STALL_HEADLINES.length)];
        String tag = STALL_TAGS[Math.floorMod(tagHash, STALL_TAGS.length)];
        return headline + tag;
    }

    static void finishErrand(BotEntry entry, Character bot, String say) {
        boolean visited = entry.fmVisitedMarket; // read before clearFmErrand wipes it
        clearFmErrand(entry);
        BotPersonality p = entry.personality != null ? entry.personality : BotPersonality.defaults();
        // Satiation: sessions ride the break cadence; meso-focused wiring lands with the S4 traits.
        // A FIZZLE (never reached the market — walk timeout, unreachable portal) retries on a
        // short backoff instead: burning the full satiation on a failed walk locked bots out of
        // the market for hours (live round: 'holy' fizzled at Rien, next scan pushed ~7h).
        long gap = visited
                ? Math.round(3_600_000L * (2.0 + 6.0 * ThreadLocalRandom.current().nextDouble())
                        * (1.5 - 0.5 * p.chattiness()))
                : BotManager.randMs(10 * 60_000, 25 * 60_000);
        trace(entry, "trip done: visited=" + visited + " nextScanIn=" + (gap / 60_000) + "min");
        entry.nextFmScanAtMs = System.currentTimeMillis() + gap;
        if (say != null) {
            reply.accept(entry, say);
        }
    }

    static void clearFmErrand(BotEntry entry) {
        BotTravelManager.clearMoveTargetPin(entry);
        leaveVisitedStall(entry); // release any merchant visitor slot before tearing down the errand
        entry.fmBrowseEndMs = 0L;
        entry.fmBrowseOwners = null;
        entry.fmBrowseIdx = 0;
        entry.fmErrandMapId = -1;
        entry.fmRoomMapId = -1;
        entry.fmPhase = PHASE_TRAVEL;
        entry.fmPhaseDeadlineAtMs = 0L;
        entry.fmBrowseUntilMs = 0L;
        entry.fmPlaceTries = 0;
        entry.fmBargainBuys = 0;
        entry.fmStandSpot = null;
        entry.fmStandBestDist = Integer.MAX_VALUE;
        entry.fmStandStuckSinceMs = 0L;
        entry.fmFredrickState = 0;
        entry.fmFredrickOnExit = false;
        entry.fmVisitedMarket = false;
        entry.fmShoutUntilMs = 0L;
        entry.fmShoutedThisTrip = false;
        entry.fmFidgetAtMs = 0L;
        entry.fmPlannedListings = List.of();
        entry.fmErrandProgress.clear();
        entry.marketBusy = false;
    }
}
