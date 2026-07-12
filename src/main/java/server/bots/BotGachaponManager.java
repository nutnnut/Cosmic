/*
    This file is part of the OdinMS Maple Story Server.
    Bot gachapon loop (AI companion feature): bots spend NX earned from looted NX cards on
    gachapon, choosing the best town's pool by expected value, then roll legally at the NPC.
*/
package server.bots;

import client.Character;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.id.NpcId;
import net.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.CashShop;
import server.ItemInformationProvider;
import server.gachapon.Gachapon;
import server.life.NPC;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Earn NX from loot, spend it on gachapon, chase uniques. Two halves:
 *
 * <ul>
 *   <li><b>NX from cards is NOT here.</b> Looted NX cards already credit account NX on pickup via
 *       shared player code ({@link Character#pickupItem} -> {@code getCashShop().gainCash(NX_CREDIT,
 *       100|250)}), which bots hit through {@code bot.pickupItem(drop)} in
 *       {@link BotInventoryManager}. A bot is a {@link Character} with its own {@link CashShop}, so
 *       it holds and spends NX with no extra code. Rebuilding that here would duplicate player code.</li>
 *   <li><b>Gachapon advisor + execute</b> ({@link #tickScan}/{@link #tickErrand}): when a bot has
 *       spare NX, score every reachable gachapon town by expected item value per roll (net of the
 *       NX it costs), pick the best, travel to its NPC, buy tickets and roll. Only on autopilot -
 *       supervised bots stay at the owner's side (owner-perks rule), exactly like quest errands.</li>
 * </ul>
 *
 * <p><b>Legality.</b> The roll itself is the player SSOT: {@link Gachapon#process(int)} (the same
 * random pick {@code NPCConversationManager.doGachapon} runs), then the item is added with
 * {@link InventoryManipulator#addById} - the same legal grant {@link BotFerryManager} uses for
 * tickets. The bot physically walks within {@link #NPC_TRIGGER_RADIUS_PX} of the gachapon NPC
 * before rolling, the same behavior standard as {@link BotQuestManager} / {@link BotShopManager}.
 *
 * <p><b>Ticket abstraction (the ONE allowed shortcut).</b> Real players buy ticket {@code 5220000}
 * in the cash shop UI with NX, then hand it to the NPC. Nobody watches a bot, so instead of
 * scripting the cash-shop packets the bot does the server-side effect directly: a REAL affordability
 * check against account NX, the SAME balance mutation the cash shop uses
 * ({@code gainCash(NX_CREDIT, -price)}), then {@code addById(5220000)}. Everything downstream of the
 * ticket (the NPC roll) is the normal legal path.
 */
final class BotGachaponManager {

    private static final Logger log = LoggerFactory.getLogger(BotGachaponManager.class);

    /** Gachapon ticket cash item (the {@code ticketId} in scripts/npc/gachapon.js). */
    static final int GACHAPON_TICKET = 5220000;

    /** Within this many px of the gachapon NPC counts as "at the machine" - matches cab/shop/quest. */
    static final int NPC_TRIGGER_RADIUS_PX = 500;

    /** "Settled at my own stand spot" tolerance (mirrors BotFerryManager.FERRY_ARRIVE_PX). */
    private static final int STAND_ARRIVE_PX = 18;

    // Tier weights mirrored from Gachapon.GachaponType (90/8/2 common/uncommon/rare, identical on
    // every town so they don't discriminate; visible here so the EV math is self-contained).
    private static final int TIER_COMMON = 90;
    private static final int TIER_UNCOMMON = 8;
    private static final int TIER_RARE = 2;
    private static final double TIER_TOTAL = TIER_COMMON + TIER_UNCOMMON + TIER_RARE;

    // Jittered scan cadence so a party of bots doesn't decide to gacha in lock-step.
    private static final long SCAN_MIN_MS = 60_000L;
    private static final long SCAN_MAX_MS = 120_000L;

    // Give up on a gacha trip that can't reach the NPC in time (mirrors the quest-errand giveup).
    static final long ERRAND_TIMEOUT_MS = 120_000L;

    // Per-roll humanlike pacing + the inventory-space guard between rolls (a roll can drop an equip
    // that fills the last EQUIP slot; the next roll must re-check before granting).
    private static final long ROLL_DELAY_MIN_MS = 1_500L;
    private static final long ROLL_DELAY_MAX_MS = 3_500L;

    // Rate-limit the "got <rare>!" chat line so a lucky streak doesn't spam.
    private static final long RARE_CHAT_CD_MS = 20_000L;

    // A rare/uncommon pull is worth more to a future buyer than its NPC price implies (cosmetic
    // uniques like capes often sell for 0). Floor each non-common pool item's value so the advisor
    // actually chases them - the whole point of the feature.
    private static final double UNCOMMON_VALUE_FLOOR = 200.0;
    private static final double RARE_VALUE_FLOOR = 1_500.0;

    private BotGachaponManager() {}

    // ---- seams (real APIs need WZ/DB; tests swap these) --------------------------------------

    /** The legal gachapon roll: {@link Gachapon#process(int)} - the SSOT random pick. Seamed so
     *  tests can assert the loop without the WZ-backed item pools / Randomizer. */
    @FunctionalInterface
    interface RollLookup {
        Gachapon.GachaponItem roll(int npcId);
    }
    static RollLookup roll = npcId -> Gachapon.getInstance().process(npcId);

    /** A town's pool item ids for a tier (local + the shared GLOBAL pool, exactly the set
     *  {@code GachaponType.getItem} draws from). Seam over the public {@link Gachapon.GachaponType}. */
    @FunctionalInterface
    interface PoolLookup {
        int[] items(int npcId, int tier);
    }
    static PoolLookup pool = (npcId, tier) -> {
        Gachapon.GachaponType g = Gachapon.GachaponType.getByNpcId(npcId);
        if (g == null) {
            return new int[0];
        }
        int[] local = g.getItems(tier);
        int[] global = Gachapon.GachaponType.GLOBAL.getItems(tier);
        int[] merged = new int[local.length + global.length];
        System.arraycopy(local, 0, merged, 0, local.length);
        System.arraycopy(global, 0, merged, local.length, global.length);
        return merged;
    };

    /** Value of one pool item by id (no rolled Equip exists, so this is id-based, NOT the
     *  above-base stat scores). Reuses the equip-value SSOT for gear (offense from BASE stats) and
     *  the NPC-price SSOT as a trade-value proxy for everything else. Seamed (WZ-backed). */
    @FunctionalInterface
    interface ValueLookup {
        double value(Character bot, int itemId);
    }
    static ValueLookup itemValue = (bot, itemId) ->
            // Value every pull (equip or not) by NPC resale -- the meso scale the pool EV and ticket
            // price share. An offense-"upgrade" score (the old shared-SSOT call here) can't live on that
            // scale, and expectedValuePerRoll AVERAGES over the whole pool, so a single upgrade item
            // can't move the town ranking even at the right scale. Rare/cosmetic chase is handled by the
            // per-tier value floors in expectedValuePerRoll, not here. (Reverts the equip branch of
            // f246e4322; targeting upgrades would need a best-possible-pull EV model, not an average.)
            ItemInformationProvider.getInstance().getPrice(itemId, 1);

    /** Need-aware value of PULLING an equip: its upgrade gain to THIS bot over what it would wear,
     *  via the grind-drop valuation SSOT ({@link BotGrindAdvisor#catalogAcquireGain}) - the SAME
     *  scale the advisor picks farm maps on, NOT resale. 0 for non-equips/downgrades/unmet reqs.
     *  {@code barCache} is shared across a pool/town pass so owned-bars compute once per slot.
     *  DPS-score units; {@link #expectedUpgradePerRoll} converts to NX. Seamed (WZ-backed). */
    @FunctionalInterface
    interface UpgradeLookup {
        double gain(Character bot, int itemId, Map<Short, Double> barCache);
    }
    static UpgradeLookup upgradeValue = (bot, itemId, barCache) -> {
        try {
            return BotGrindAdvisor.catalogAcquireGain(
                    bot, ItemInformationProvider.getInstance(), itemId, barCache);
        } catch (RuntimeException e) {
            return 0.0; // invalid id / WZ unavailable - no upgrade signal, resale still drives
        }
    };

    /** Account NX balance (NX_CREDIT) - where looted NX cards land. Seam over {@link CashShop}. */
    @FunctionalInterface
    interface NxBalance {
        int nx(Character bot);
    }
    static NxBalance nxBalance = bot -> bot.getCashShop().getCash(CashShop.NX_CREDIT);

    /** The real per-ticket NX price. Resolved from the cash-shop commodity SSOT (the single-ticket
     *  SN for item 5220000), falling back to the verified 800 NX if the WZ lookup is unavailable.
     *  Seamed so tests don't need CashItemFactory. */
    @FunctionalInterface
    interface TicketPrice {
        int nx();
    }
    static final int GACHAPON_TICKET_NX_FALLBACK = 800; // Commodity.img SN 10101513, count=1
    static final int GACHAPON_TICKET_SINGLE_SN = 10101513;
    static TicketPrice ticketPrice = () -> {
        try {
            CashShop.CashItem ci = CashShop.CashItemFactory.getItem(GACHAPON_TICKET_SINGLE_SN);
            if (ci != null && ci.getItemId() == GACHAPON_TICKET && ci.getPrice() > 0) {
                return ci.getPrice();
            }
        } catch (RuntimeException e) {
            // WZ not loaded (e.g. early boot) - fall through to the verified constant.
        }
        return GACHAPON_TICKET_NX_FALLBACK;
    };

    /** Deduct NX through the same mutation the cash shop charges with. Seamed for tests. */
    @FunctionalInterface
    interface NxCharge {
        void charge(Character bot, int nx);
    }
    static NxCharge nxCharge = (bot, nx) -> bot.getCashShop().gainCash(CashShop.NX_CREDIT, -nx);

    /** Grant a rolled item legally (the same path {@link BotFerryManager} grants tickets with). */
    @FunctionalInterface
    interface ItemGrant {
        void grant(Character bot, int itemId, short quantity);
    }
    static ItemGrant grantItem =
            (bot, itemId, qty) -> InventoryManipulator.addById(bot.getClient(), itemId, qty);

    /** Inventory-space guard before a grant (mirrors the script's canHold check). Seamed. */
    @FunctionalInterface
    interface SpaceCheck {
        boolean hasRoom(Character bot, int itemId, short quantity);
    }
    static SpaceCheck spaceCheck =
            (bot, itemId, qty) -> InventoryManipulator.checkSpace(bot.getClient(), itemId, qty, "");

    /** Round-trip travel seconds to a town map; seam over {@link BotTravelCost}. Large finite
     *  fallback when unreachable so the EV sort de-prioritizes it instead of NaN/Infinity. */
    @FunctionalInterface
    interface TravelSeconds {
        double seconds(int fromMapId, int toMapId, BotWorldGraph.RouteOptions options);
    }
    static TravelSeconds travelSeconds = (from, to, options) -> {
        if (from == to) {
            return 0.0;
        }
        // floodSeconds always computes ferrySeconds(transportationTime) up front, so a non-null
        // transportationTime is required. Mirrors BotQuestManager's errand seam; passing null,null
        // NPE'd on the bot tick (Bowgurl@200010000). Options carry the bot's real travel means
        // (return scroll + taxis) so a scroll-reachable far town isn't over-charged as a long walk.
        java.util.Map<Integer, Double> flood = BotTravelCost.floodSeconds(from,
                BotAutopilotManager.MAX_TRAVEL_HOPS, options, ms -> ms);
        Double one = flood.get(to);
        return one == null ? 99_999.0 : 2.0 * one;
    };

    /** Hop-reachability gate: is {@code toMapId} within {@code maxHops} hops of {@code fromMapId} on the
     *  live {@link BotWorldGraph}, under the bot's real travel {@code options} (return scroll + taxis)?
     *  Seamed (graph-backed) so tests rank towns without live topology. */
    @FunctionalInterface
    interface HopReach {
        boolean within(int fromMapId, int toMapId, int maxHops, BotWorldGraph.RouteOptions options);
    }
    static HopReach hopReach = (from, to, hops, options) -> BotWorldGraph.route(from, to, hops, options) != null;

    /** The bot's real travel means for ranking — return scroll + meso taxis, no ferry. Seamed so tests
     *  rank towns without stubbing the bot's job/meso/level that {@link BotAutopilotManager#travelOptions}
     *  reads to build the options. */
    static java.util.function.Function<Character, BotWorldGraph.RouteOptions> travelOptions =
            bot -> BotAutopilotManager.travelOptions(bot, false);

    /** Bot chat output, behind a seam so tests capture replies without the BotManager singleton. */
    static java.util.function.BiConsumer<BotEntry, String> reply =
            (entry, text) -> BotManager.getInstance().botReply(entry, text);

    /** Server-wide "got a(n) <item>" notice for a notable (tier>0) pull - the exact broadcast a real
     *  player triggers in {@code NPCConversationManager.doGachapon}. Seamed so the loop test doesn't
     *  need the Server singleton / packet layer. */
    @FunctionalInterface
    interface RareBroadcast {
        void announce(Character bot, Item displayItem, String town);
    }
    static RareBroadcast rareBroadcast = (bot, displayItem, town) ->
            Server.getInstance().broadcastMessage(bot.getWorld(),
                    PacketCreator.gachaponMessage(displayItem, town, bot));

    // ---- EV advisor --------------------------------------------------------------------------

    /** A reachable gachapon town. {@code evPerRoll} = the per-roll NX value that drives it
     *  (max of resale and need-upgrade EV); {@code netScore} = the whole-TRIP net (planned rolls x
     *  (evPerRoll - price), minus travel) on which towns are ranked. */
    record TownEv(int npcId, int mapId, double evPerRoll, double netScore) {}

    /** Per-(tier,item) valuator for {@link #expectedPerRoll}. */
    @FunctionalInterface
    private interface PoolItemValue {
        double value(int tier, int itemId);
    }

    /** Tier-weighted expected value of one roll at a town: sum over tiers of P(tier) * average value
     *  of that tier's pool, drawing local+global uniformly (the exact {@code getItem} distribution).
     *  The per-item valuator is the only thing that varies between the resale and upgrade EVs. */
    private static double expectedPerRoll(int npcId, PoolItemValue v) {
        double ev = 0.0;
        int[] weights = {TIER_COMMON, TIER_UNCOMMON, TIER_RARE};
        for (int tier = 0; tier < 3; tier++) {
            int[] items = pool.items(npcId, tier);
            if (items.length == 0) {
                continue;
            }
            double sum = 0.0;
            for (int itemId : items) {
                sum += v.value(tier, itemId);
            }
            ev += (weights[tier] / TIER_TOTAL) * (sum / items.length);
        }
        return ev;
    }

    /** Expected RESALE value of one roll (the "fun/meso/uniques" motive). Non-common pools are
     *  value-floored so cosmetic uniques are actually chased. */
    static double expectedValuePerRoll(Character bot, int npcId) {
        double[] floors = {0.0, UNCOMMON_VALUE_FLOOR, RARE_VALUE_FLOOR};
        return expectedPerRoll(npcId, (tier, id) -> Math.max(floors[tier], itemValue.value(bot, id)));
    }

    /** Expected UPGRADE NX of one roll for THIS bot (the "best gacha that suits self" motive): the
     *  grind-advisor gear-upgrade SSOT averaged over the pool, converted to NX. Equip upgrades only;
     *  cosmetics/dupes/downgrades score ~0. {@code itemGainCache} memoizes each item's gain across
     *  towns (the shared GLOBAL pool is scored once); {@code barCache} memoizes owned-bars per slot. */
    static double expectedUpgradePerRoll(Character bot, int npcId,
            Map<Short, Double> barCache, Map<Integer, Double> itemGainCache) {
        double scoreEv = expectedPerRoll(npcId,
                (tier, id) -> itemGainCache.computeIfAbsent(id, k -> upgradeValue.gain(bot, k, barCache)));
        return scoreEv * BotManager.cfg.GACHA_UPGRADE_NX_PER_SCORE;
    }

    /** The town-resolvable gachapon NPCs (the set {@code doGachapon}'s {@code maps[]} can place).
     *  Showa is instanced and Ludibrium/El Nath have no town map in the script, so they're excluded
     *  - a bot can't legally walk to a machine it can't resolve a home town for. */
    static final int[] TOWN_GACHAPON_NPCS = {
            NpcId.GACHAPON_HENESYS, NpcId.GACHAPON_ELLINIA, NpcId.GACHAPON_PERION,
            NpcId.GACHAPON_KERNING, NpcId.GACHAPON_SLEEPYWOOD, NpcId.GACHAPON_MUSHROOM_SHRINE,
            NpcId.GACHAPON_NLC, NpcId.GACHAPON_NAUTILUS,
    };

    /** Rank reachable gachapon towns by net score: EV per roll minus the ticket price minus a small
     *  travel penalty (so a far town must out-value a near one). Best first; empty if none beats the
     *  bar. The ticket price is the same NX a real purchase costs, so EV is directly comparable. */
    static List<TownEv> rankTowns(Character bot, int fromMapId) {
        int price = ticketPrice.nx();
        int rolls = plannedRolls(bot, price);
        // The bot's real travel means (return scroll + meso taxis), so reachability and cost match how
        // the bot would actually get there — a rich town a free scroll away isn't excluded as a long
        // walk. No ferry: the bot won't ferry out just for gacha. SSOT with BotTravelManager routing.
        BotWorldGraph.RouteOptions options = travelOptions.apply(bot);
        // Owned-bars (per slot) and per-item upgrade gains are bot-global, not town-specific, so one
        // cache each spans the whole pass: the shared GLOBAL pool is scored once, not per town.
        Map<Short, Double> barCache = new HashMap<>();
        Map<Integer, Double> itemGainCache = new HashMap<>();
        List<TownEv> out = new ArrayList<>();
        for (int npcId : TOWN_GACHAPON_NPCS) {
            int mapId = gachaponTownMap(npcId);
            if (mapId < 0) {
                continue;
            }
            double travel = travelSeconds.seconds(fromMapId, mapId, options);
            if (travel >= 99_999.0) {
                continue; // unreachable within the hop cap
            }
            if (!hopReach.within(fromMapId, mapId, GACHA_MAX_HOPS, options)) {
                continue; // farther than GACHA_MAX_HOPS even with scroll/taxi — too far to wander for gacha
            }
            // Whichever motive is stronger drives the roll - gear upgrades for THIS bot OR raw
            // resale/uniques - both already in NX, so no scale mixing. The need-aware term is the
            // grind-advisor's own gear-upgrade SSOT, so the bot values a pool the same way it values
            // a farm map: by the upgrades it can actually use.
            double rollValue = Math.max(expectedValuePerRoll(bot, npcId),
                    expectedUpgradePerRoll(bot, npcId, barCache, itemGainCache));
            // Amortize travel over the rolls the bot will ACTUALLY do this trip: a far town must
            // out-earn a near one across the whole session, so cheap "I'm here anyway" trips stay
            // local and only a saved-up bankroll justifies going out of the way.
            double tripNet = rolls * (rollValue - price) - travel * TRAVEL_NX_PER_SECOND;
            out.add(new TownEv(npcId, mapId, rollValue, tripNet));
        }
        out.sort((a, b) -> Double.compare(b.netScore(), a.netScore()));
        return out;
    }
    // Per-second NX-equivalent travel penalty (visible knob). At ~0.5 NX/s a 60s round trip costs
    // 30 NX of "score" - enough to break ties toward nearer pools without overriding a richer one.
    static final double TRAVEL_NX_PER_SECOND = 0.5;
    /** Cap on how far (portal hops from the break spot) a bot will travel for a gacha trip. */
    static final int GACHA_MAX_HOPS = 5;

    /** The NX floor gacha never dips below: the flat reserve, plus the store-permit price while
     *  the bot is saving for one ({@link BotFreeMarketManager#savingForPermit}) — a would-be
     *  merchant funds its stall before it gambles. */
    static int nxFloor(Character bot) {
        int floor = BotManager.cfg.GACHA_NX_RESERVE;
        BotEntry entry = BotManager.getInstance().getEntryByBotCharId(bot.getId());
        if (BotFreeMarketManager.savingForPermit(entry, bot)) {
            floor += BotFreeMarketManager.permitPrice.nx();
        }
        return floor;
    }

    /** Rolls the bot can afford above the reserve, capped at the per-trip ceiling - the count travel
     *  is amortized over, so saving up enables (and justifies) a longer / farther trip. */
    static int plannedRolls(Character bot, int price) {
        if (price <= 0) {
            return 0;
        }
        int spendable = nxBalance.nx(bot) - nxFloor(bot);
        return Math.max(0, Math.min(BotManager.cfg.GACHA_TICKETS_PER_TRIP, spendable / price));
    }

    /** True when a town is chosen for gear upgrades (need-EV beats resale-EV) - the trips that should
     *  pivot away once the upgrade is pulled (resale trips never deplete, so they don't pivot). */
    static boolean isUpgradeDriven(Character bot, int npcId) {
        return expectedUpgradePerRoll(bot, npcId, new HashMap<>(), new HashMap<>())
                > expectedValuePerRoll(bot, npcId);
    }

    // ---- scan: decide whether to start a gacha trip ------------------------------------------

    static void tickScan(BotEntry entry, Character bot) {
        if (!BotManager.cfg.GACHAPON_ENABLED || bot.getMap() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < entry.nextGachaScanAtMs) {
            return;
        }
        entry.nextGachaScanAtMs = now + BotManager.randMs((int) SCAN_MIN_MS, (int) SCAN_MAX_MS);

        // Supervised bots stay put - same owner-perks gate as quest errands. Only autopilot wanders.
        if (!BotAutopilotManager.isActive(entry)) {
            return;
        }
        // Gacha is a rest-break activity, not a mid-grind detour: only roll while parked at a rest
        // destination (a town or a chosen nearby safe map), never during a short in-place grind break.
        if (!BotBreakManager.onRestBreak(entry, bot, now)) {
            return;
        }
        if (entry.gachaErrandMapId != -1 || entry.questErrandMapId != -1 || entry.fmErrandMapId != -1) {
            return; // one errand at a time
        }
        // Spend only spare NX above the floor (reserve + permit savings), enough for one ticket.
        int price = ticketPrice.nx();
        int spendable = nxBalance.nx(bot) - nxFloor(bot);
        if (price <= 0 || spendable < price) {
            return;
        }
        List<TownEv> ranked = rankTowns(bot, bot.getMapId());
        if (ranked.isEmpty()) {
            return;
        }
        TownEv best = ranked.get(0);
        // Only go if the best pool's net score clears the worthwhile bar (EV beats price + travel by
        // a visible margin) - otherwise hoard the NX for a better/closer pool later.
        if (best.netScore() < BotManager.cfg.GACHA_MIN_NET_EV) {
            return;
        }
        beginErrand(entry, bot, best);
    }

    /** Debug/GM force-start: kick off a gacha trip now, bypassing the scan gates (NX worthwhile bar,
     *  cadence). npcId<=0 picks the best reachable town; a given npcId is used as-is. Returns a status
     *  line for the operator. {@link #rollOnce} still honors the NX reserve, so this forces the TRIP,
     *  not free rolls. */
    static String forceErrand(BotEntry entry, Character bot, int npcId) {
        if (!BotAutopilotManager.isActive(entry)) {
            return bot.getName() + ": not on autopilot (errand tick won't run)";
        }
        int mapId;
        if (npcId > 0) {
            mapId = gachaponTownMap(npcId);
            if (mapId < 0) {
                return "npc " + npcId + " has no town map";
            }
        } else {
            List<TownEv> ranked = rankTowns(bot, bot.getMapId());
            if (ranked.isEmpty()) {
                return "no reachable gachapon town from map " + bot.getMapId();
            }
            npcId = ranked.get(0).npcId();
            mapId = ranked.get(0).mapId();
        }
        entry.questErrandMapId = -1; // gacha tick runs after quest tick; clear so it isn't shadowed
        entry.gachaErrandNpcId = npcId;
        entry.gachaErrandMapId = mapId;
        entry.gachaErrandProgress.begin(System.currentTimeMillis());
        resetTripCounters(entry, bot);
        entry.gachaUpgradeDriven = isUpgradeDriven(bot, npcId);
        reply.accept(entry, "console: forcing a gachapon trip to map " + mapId);
        return bot.getName() + " -> gacha trip: npc " + npcId + " @ map " + mapId;
    }

    /** Reset per-trip counters and compute this trip's NX budget = a personality fraction of spare NX
     *  (above the reserve). The budget replaces the old flat per-trip ticket cap as the real spend limit.
     *  Shared by the scan-start and the GM force-start paths. */
    private static void resetTripCounters(BotEntry entry, Character bot) {
        entry.gachaTicketsThisTrip = 0;
        entry.gachaNextRollAtMs = 0L;
        entry.gachaSpentThisTrip = 0;
        entry.gachaStandSpot = null;
        BotPersonality p = entry.personality != null ? entry.personality : BotPersonality.defaults();
        long spare = nxBalance.nx(bot) - nxFloor(bot);
        entry.gachaTripBudgetNx = Math.max(0L, Math.round(spare * p.gachaSpendFrac()));
    }

    private static void beginErrand(BotEntry entry, Character bot, TownEv town) {
        entry.gachaErrandNpcId = town.npcId();
        entry.gachaErrandMapId = town.mapId();
        entry.gachaErrandProgress.begin(System.currentTimeMillis());
        resetTripCounters(entry, bot);
        entry.gachaUpgradeDriven = isUpgradeDriven(bot, town.npcId());
        reply.accept(entry, entry.gachaUpgradeDriven
                ? "saw some gear i want at the gachapon, heading over"
                : "feeling lucky, gonna hit the gachapon");
    }

    // ---- errand tick: travel, walk to NPC, roll ----------------------------------------------

    /** Drives an active gacha trip. Returns true when this tick is consumed (traveling toward the
     *  town, walking to the NPC, or pacing between rolls). Mirrors {@link BotQuestManager#tickErrand}
     *  and is called from {@link BotAutopilotManager#tick} BEFORE the grind destination, so the
     *  errand takes precedence; on completion it clears state and lets autopilot resume grinding. */
    static boolean tickErrand(BotEntry entry, Character bot, boolean runAiTick) {
        if (entry.gachaErrandMapId == -1) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (entry.gachaErrandProgress.stalled(now, ERRAND_TIMEOUT_MS)) {
            boolean reachedTown = bot.getMapId() == entry.gachaErrandMapId;
            finishErrand(entry, bot, "couldn't get to the gachapon (stuck on map " + bot.getMapId()
                    + (reachedTown ? ", at town but not at NPC" : ", still in transit") + "), never mind");
            return false;
        }
        if (bot.getMapId() != entry.gachaErrandMapId) {
            boolean moved = BotTravelManager.tickTravel(entry, bot, entry.gachaErrandMapId,
                    BotAutopilotManager.MAX_TRAVEL_HOPS, runAiTick, false);
            entry.gachaErrandProgress.record(bot, moved, now); // hop/ferry progress refreshes the deadline
            return moved;
        }
        NPC npc = bot.getMap().getNPCById(entry.gachaErrandNpcId);
        if (npc == null) {
            finishErrand(entry, bot, "huh, no gachapon machine here, never mind");
            return false;
        }
        Point npcPos = npc.getPosition();
        Point botPos = bot.getPosition();
        // Each bot stands at its own jittered reachable foothold near the machine instead of all walking
        // onto the exact NPC pixel (which piled them on one spot). Picked once on arrival; widened to the
        // trigger radius so an off-graph machine is still reachable (same util as taxi/job approaches).
        if (entry.gachaStandSpot == null) {
            entry.gachaStandSpot = BotTravelManager.pickReachableApproachPoint(
                    entry, bot, npcPos, BotTravelManager.APPROACH_SPREAD_PX, NPC_TRIGGER_RADIUS_PX);
        }
        Point stand = entry.gachaStandSpot;
        if (entry.inAir || entry.climbing || manhattan(botPos, stand) > STAND_ARRIVE_PX) {
            BotTravelManager.pinMoveTarget(entry, stand);
            BotTravelManager.movementStep.step(entry, stand, runAiTick);
            return true;
        }
        // At the machine: rolling is legit progress, so keep the no-progress deadline from firing
        // mid-trip. Pace the rolls so it reads as a human feeding tickets one at a time.
        entry.gachaErrandProgress.touch(now);
        if (now < entry.gachaNextRollAtMs) {
            return true; // mid-pace between rolls (common-tick settle stands it at the machine)
        }
        if (!rollOnce(entry, bot)) {
            BotTravelManager.clearMoveTargetPin(entry);
            finishErrand(entry, bot, null);
            return false;
        }
        entry.gachaNextRollAtMs = now + BotManager.randMs((int) ROLL_DELAY_MIN_MS, (int) ROLL_DELAY_MAX_MS);
        return true;
    }

    // The script's pre-roll space gate: a free slot in EQUIP/USE/SETUP/ETC each, checked BEFORE
    // paying (the reward type isn't known until after the roll). Representative stackable item ids,
    // one per inventory type - the same shape gachapon.js uses (1302000/2000000/3010001/4000000).
    private static final int[] FREE_SLOT_PROBE_IDS = {1302000, 2000000, 3010001, 4000000};

    /** Buy + roll one ticket. Returns true to keep rolling, false when the trip should end (trip NX
     *  budget spent, can't afford another without dipping the reserve, or no inventory room). Mirrors
     *  {@code doGachapon}'s body for the roll effect; the buy is the allowed cash-shop abstraction.
     *  Package-visible for the seam test. */
    static boolean rollOnce(BotEntry entry, Character bot) {
        int price = ticketPrice.nx();
        if (price <= 0) {
            return false;
        }
        // Personality NX budget (a fraction of spare NX) is the spend cap, replacing the old flat ticket
        // count. Also never dip the reserve regardless of budget.
        if (entry.gachaSpentThisTrip + price > entry.gachaTripBudgetNx
                || nxBalance.nx(bot) - nxFloor(bot) < price) {
            return false;
        }
        // Inventory-space guard BEFORE charging (mirrors gachapon.js, which checks one free slot in
        // every inventory type before consuming the ticket - the reward type is unknown until the
        // roll, so a generic free-slot-per-type gate is the only correct pre-roll check). Charging
        // first then failing to grant would lose NX the player path never loses.
        for (int probeId : FREE_SLOT_PROBE_IDS) {
            if (!spaceCheck.hasRoom(bot, probeId, (short) 1)) {
                reply.accept(entry, "bag's full, can't keep gambling");
                return false;
            }
        }
        // Abstracted purchase: real affordability + space already verified. Charge the same NX the
        // cash shop would, then roll (the SSOT pick) and grant the reward via the legal add path.
        nxCharge.charge(bot, price);
        entry.gachaTicketsThisTrip++;
        entry.gachaSpentThisTrip += price;
        Gachapon.GachaponItem item = roll.roll(entry.gachaErrandNpcId);
        if (item == null) {
            return true; // shouldn't happen on a valid NPC; skip and keep going
        }
        // doGachapon's potion quantity rule: stackable potions (id/10000 == 200) come in 100s.
        short qty = (short) (item.getId() / 10000 == 200 ? 100 : 1);
        grantItem.grant(bot, item.getId(), qty);
        announceAndLog(entry, bot, item);
        // Pivot: an upgrade-driven trip ends once the pull it came for is in the bag - that just-worn
        // upgrade raises the owned-bar, collapsing this pool's need-EV. When a roll no longer beats its
        // ticket price the trip stops; autopilot resumes and the next scan re-ranks toward a now-better
        // pool ("got what i need, aim elsewhere"). Bounded to equip pulls so the recompute is rare.
        // ponytail: pool recompute on the tick thread, only after an equip pull; move to DECIDE_POOL if
        // a bot ever pulls equips fast enough for this to show on the perf monitor.
        if (entry.gachaUpgradeDriven && isEquip.test(item.getId())) {
            double rollValue = Math.max(expectedValuePerRoll(bot, entry.gachaErrandNpcId),
                    expectedUpgradePerRoll(bot, entry.gachaErrandNpcId, new HashMap<>(), new HashMap<>()));
            if (rollValue <= price) {
                reply.accept(entry, "got what i came for, heading back");
                return false;
            }
        }
        return true;
    }

    /** True when an item id is an equip (has equip stats) - gates the pivot recompute to equip pulls.
     *  Seamed (WZ-backed) so tests drive the pivot without the provider; WZ-unavailable reads false. */
    static java.util.function.IntPredicate isEquip = itemId -> {
        try {
            return ItemInformationProvider.getInstance().getEquipStats(itemId) != null;
        } catch (RuntimeException e) {
            return false;
        }
    };

    /** Audit log of a pull; seam over {@link Gachapon#log} (WZ-backed name lookup) so the loop is
     *  test-free. */
    @FunctionalInterface
    interface GachaLog {
        void log(Character bot, int itemId, String map);
    }
    static GachaLog gachaLog = Gachapon::log;

    private static void announceAndLog(BotEntry entry, Character bot, Gachapon.GachaponItem item) {
        String town = bot.getMap() != null ? bot.getMap().getMapName() : "";
        try {
            gachaLog.log(bot, item.getId(), town);
        } catch (RuntimeException ignored) {
            // logging is best-effort
        }
        // Notable (uncommon/rare) pull: the same tier>0 gate doGachapon uses.
        if (item.getTier() > 0) {
            long now = System.currentTimeMillis();
            if (now >= entry.gachaNextRareChatAtMs) {
                entry.gachaNextRareChatAtMs = now + RARE_CHAT_CD_MS;
                String name = gachaItemName(item.getId());
                reply.accept(entry, "gacha: got " + name + "!");
            }
            // World-wide notice, exactly like a real player's pull (the bot path was missing this).
            try {
                int id = item.getId();
                short qty = (short) (id / 10000 == 200 ? 100 : 1);
                Item display = isEquip.test(id) ? ItemInformationProvider.getInstance().getEquipById(id) : null;
                if (display == null) {
                    display = new Item(id, (short) 0, qty);
                }
                rareBroadcast.announce(bot, display, town);
            } catch (RuntimeException ignored) {
                // broadcast is best-effort; a bad id / missing Server must not break the roll loop
            }
        }
    }

    /** Item display name from id; seam over the WZ provider so the chat line stays test-free. The
     *  reply path ({@code BotManager.botReply}) sanitizes to ASCII, so no sanitizing is needed here. */
    static java.util.function.IntFunction<String> itemNameLookup =
            id -> {
                String n = ItemInformationProvider.getInstance().getName(id);
                return n == null ? ("item " + id) : n;
            };

    private static String gachaItemName(int itemId) {
        try {
            return itemNameLookup.apply(itemId);
        } catch (RuntimeException e) {
            return "item " + itemId;
        }
    }

    static void finishErrand(BotEntry entry, Character bot, String say) {
        clearGachaErrand(entry);
        // Personality satiation: after a trip the bot won't reconsider gachapon for hours/days (a few times
        // a day for a high-appetite bot, once every few days for a low one). This jittered interval is the
        // cadence control that kills the old "roll, leave, immediately come back" yo-yo - the scan clock was
        // set at trip START and a trip outlasts it, so it used to already be expired on finish.
        BotPersonality p = entry.personality != null ? entry.personality : BotPersonality.defaults();
        long gap = Math.round(p.gachaIntervalMs() * (0.5 + ThreadLocalRandom.current().nextDouble())); // 0.5..1.5x
        entry.nextGachaScanAtMs = System.currentTimeMillis() + gap;
        if (say != null) {
            reply.accept(entry, say);
        }
    }

    static void clearGachaErrand(BotEntry entry) {
        BotTravelManager.clearMoveTargetPin(entry);
        entry.gachaErrandMapId = -1;
        entry.gachaErrandNpcId = 0;
        entry.gachaErrandProgress.clear();
        entry.gachaTicketsThisTrip = 0;
        entry.gachaNextRollAtMs = 0L;
        entry.gachaUpgradeDriven = false;
        entry.gachaStandSpot = null;
        entry.gachaTripBudgetNx = 0L;
        entry.gachaSpentThisTrip = 0;
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** The map a gachapon NPC actually STANDS on — the bot travels here to roll, so it must be the NPC's
     *  real life-map, not the town's "main" map. Most sit in the town hub, but a couple live in a side
     *  room (Henesys Market, Nautilus mid-floor); using the town hub there sent the bot to an empty map
     *  ("no gachapon machine here"). Verified vs Map.wz life data per NPC. Ludibrium/El Nath have no
     *  bot-resolvable gachapon here and return -1 (skipped). */
    static int gachaponTownMap(int npcId) {
        return switch (npcId) {
            case NpcId.GACHAPON_HENESYS -> 100000100; // Henesys Market (NOT 100000000 Henesys main)
            case NpcId.GACHAPON_ELLINIA -> constants.id.MapId.ELLINIA;
            case NpcId.GACHAPON_PERION -> constants.id.MapId.PERION;
            case NpcId.GACHAPON_KERNING -> constants.id.MapId.KERNING_CITY;
            case NpcId.GACHAPON_SLEEPYWOOD -> constants.id.MapId.SLEEPYWOOD;
            case NpcId.GACHAPON_MUSHROOM_SHRINE -> constants.id.MapId.MUSHROOM_SHRINE;
            case NpcId.GACHAPON_NLC -> constants.id.MapId.NEW_LEAF_CITY;
            case NpcId.GACHAPON_NAUTILUS -> 120000200; // Nautilus mid-floor (NOT 120000000 harbor main)
            default -> -1; // Showa (instanced), Ludibrium, El Nath: not town-resolvable here
        };
    }

    private static int manhattan(Point a, Point b) {
        return Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
    }
}
