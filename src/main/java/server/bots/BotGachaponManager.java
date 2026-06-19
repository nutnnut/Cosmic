/*
    This file is part of the OdinMS Maple Story Server.
    Bot gachapon loop (AI companion feature): bots spend NX earned from looted NX cards on
    gachapon, choosing the best town's pool by expected value, then roll legally at the NPC.
*/
package server.bots;

import client.Character;
import client.inventory.manipulator.InventoryManipulator;
import constants.id.NpcId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.CashShop;
import server.ItemInformationProvider;
import server.gachapon.Gachapon;
import server.life.NPC;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

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
        double seconds(int fromMapId, int toMapId);
    }
    static TravelSeconds travelSeconds = (from, to) -> {
        if (from == to) {
            return 0.0;
        }
        // floodSeconds always computes ferrySeconds(transportationTime) up front, so a non-null
        // transportationTime is required even with PORTALS_ONLY (no ferry hop is taken). Mirrors
        // BotQuestManager's errand seam; passing null,null NPE'd on the bot tick (Bowgurl@200010000).
        java.util.Map<Integer, Double> flood = BotTravelCost.floodSeconds(from,
                BotAutopilotManager.MAX_TRAVEL_HOPS, BotWorldGraph.RouteOptions.PORTALS_ONLY, ms -> ms);
        Double one = flood.get(to);
        return one == null ? 99_999.0 : 2.0 * one;
    };

    /** Bot chat output, behind a seam so tests capture replies without the BotManager singleton. */
    static java.util.function.BiConsumer<BotEntry, String> reply =
            (entry, text) -> BotManager.getInstance().botReply(entry, text);

    // ---- EV advisor --------------------------------------------------------------------------

    /** A reachable gachapon town and its score (expected NX-equivalent value per roll, net of the
     *  ticket price and amortized travel cost). */
    record TownEv(int npcId, int mapId, double evPerRoll, double netScore) {}

    /** Expected item value of one roll at a town: sum over tiers of P(tier) * average pool item
     *  value in that tier, drawing local+global uniformly (the exact {@code getItem} distribution).
     *  Non-common pools are value-floored so cosmetic uniques are actually chased. */
    static double expectedValuePerRoll(Character bot, int npcId) {
        double ev = 0.0;
        int[] weights = {TIER_COMMON, TIER_UNCOMMON, TIER_RARE};
        double[] floors = {0.0, UNCOMMON_VALUE_FLOOR, RARE_VALUE_FLOOR};
        for (int tier = 0; tier < 3; tier++) {
            int[] items = pool.items(npcId, tier);
            if (items.length == 0) {
                continue;
            }
            double sum = 0.0;
            for (int itemId : items) {
                sum += Math.max(floors[tier], itemValue.value(bot, itemId));
            }
            double avg = sum / items.length;
            ev += (weights[tier] / TIER_TOTAL) * avg;
        }
        return ev;
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
        List<TownEv> out = new ArrayList<>();
        for (int npcId : TOWN_GACHAPON_NPCS) {
            int mapId = gachaponTownMap(npcId);
            if (mapId < 0) {
                continue;
            }
            double travel = travelSeconds.seconds(fromMapId, mapId);
            if (travel >= 99_999.0) {
                continue; // unreachable within the hop cap
            }
            double ev = expectedValuePerRoll(bot, npcId);
            // Amortize travel as an NX-equivalent penalty per trip (TRAVEL_NX_PER_SECOND keeps it a
            // tie-breaker between similar pools, not a dominant term).
            double net = (ev - price) - travel * TRAVEL_NX_PER_SECOND;
            out.add(new TownEv(npcId, mapId, ev, net));
        }
        out.sort((a, b) -> Double.compare(b.netScore(), a.netScore()));
        return out;
    }
    // Per-second NX-equivalent travel penalty (visible knob). At ~0.5 NX/s a 60s round trip costs
    // 30 NX of "score" - enough to break ties toward nearer pools without overriding a richer one.
    static final double TRAVEL_NX_PER_SECOND = 0.5;

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
        if (entry.gachaErrandMapId != -1 || entry.questErrandMapId != -1) {
            return; // one errand at a time
        }
        // Spend only spare NX above the reserve, enough for at least one ticket.
        int price = ticketPrice.nx();
        int spendable = nxBalance.nx(bot) - BotManager.cfg.GACHA_NX_RESERVE;
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

    private static void beginErrand(BotEntry entry, Character bot, TownEv town) {
        entry.gachaErrandNpcId = town.npcId();
        entry.gachaErrandMapId = town.mapId();
        entry.gachaErrandStartedAtMs = System.currentTimeMillis();
        entry.gachaTicketsThisTrip = 0;
        entry.gachaNextRollAtMs = 0L;
        reply.accept(entry, "feeling lucky, gonna hit the gachapon");
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
        if (System.currentTimeMillis() - entry.gachaErrandStartedAtMs > ERRAND_TIMEOUT_MS) {
            finishErrand(entry, bot, "couldn't get to the gachapon, never mind");
            return false;
        }
        if (bot.getMapId() != entry.gachaErrandMapId) {
            return BotTravelManager.tickTravel(entry, bot, entry.gachaErrandMapId,
                    BotAutopilotManager.MAX_TRAVEL_HOPS, runAiTick, false);
        }
        NPC npc = bot.getMap().getNPCById(entry.gachaErrandNpcId);
        if (npc == null) {
            finishErrand(entry, bot, "huh, no gachapon machine here, never mind");
            return false;
        }
        Point npcPos = npc.getPosition();
        Point botPos = bot.getPosition();
        if (entry.inAir || entry.climbing || manhattan(botPos, npcPos) > NPC_TRIGGER_RADIUS_PX) {
            BotTravelManager.pinMoveTarget(entry, npcPos);
            BotTravelManager.movementStep.step(entry, npcPos, runAiTick);
            return true;
        }
        // At the machine: pace the rolls so it reads as a human feeding tickets one at a time.
        long now = System.currentTimeMillis();
        if (now < entry.gachaNextRollAtMs) {
            BotTravelManager.settleStandingDwell(entry); // stand at the machine, not walk-in-place
            return true; // mid-pace between rolls
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

    /** Buy + roll one ticket. Returns true to keep rolling, false when the trip should end (out of
     *  NX/reserve, trip cap hit, or no inventory room). Mirrors {@code doGachapon}'s body for the
     *  roll effect; the buy is the allowed cash-shop abstraction. Package-visible for the seam test. */
    static boolean rollOnce(BotEntry entry, Character bot) {
        if (entry.gachaTicketsThisTrip >= BotManager.cfg.GACHA_TICKETS_PER_TRIP) {
            return false;
        }
        int price = ticketPrice.nx();
        if (price <= 0 || nxBalance.nx(bot) - BotManager.cfg.GACHA_NX_RESERVE < price) {
            return false; // can't afford another without dipping into the reserve
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
        Gachapon.GachaponItem item = roll.roll(entry.gachaErrandNpcId);
        if (item == null) {
            return true; // shouldn't happen on a valid NPC; skip and keep going
        }
        // doGachapon's potion quantity rule: stackable potions (id/10000 == 200) come in 100s.
        short qty = (short) (item.getId() / 10000 == 200 ? 100 : 1);
        grantItem.grant(bot, item.getId(), qty);
        announceAndLog(entry, bot, item);
        return true;
    }

    /** Audit log of a pull; seam over {@link Gachapon#log} (WZ-backed name lookup) so the loop is
     *  test-free. */
    @FunctionalInterface
    interface GachaLog {
        void log(Character bot, int itemId, String map);
    }
    static GachaLog gachaLog = Gachapon::log;

    private static void announceAndLog(BotEntry entry, Character bot, Gachapon.GachaponItem item) {
        try {
            gachaLog.log(bot, item.getId(), bot.getMap() != null ? bot.getMap().getMapName() : "");
        } catch (RuntimeException ignored) {
            // logging is best-effort
        }
        // Shout only on a notable (uncommon/rare) pull, rate-limited.
        if (item.getTier() > 0) {
            long now = System.currentTimeMillis();
            if (now >= entry.gachaNextRareChatAtMs) {
                entry.gachaNextRareChatAtMs = now + RARE_CHAT_CD_MS;
                String name = gachaItemName(item.getId());
                reply.accept(entry, "gacha: got " + name + "!");
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

    private static void finishErrand(BotEntry entry, Character bot, String say) {
        clearGachaErrand(entry);
        if (say != null) {
            reply.accept(entry, say);
        }
    }

    static void clearGachaErrand(BotEntry entry) {
        BotTravelManager.clearMoveTargetPin(entry);
        entry.gachaErrandMapId = -1;
        entry.gachaErrandNpcId = 0;
        entry.gachaErrandStartedAtMs = 0L;
        entry.gachaTicketsThisTrip = 0;
        entry.gachaNextRollAtMs = 0L;
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** The town map a gachapon NPC lives on. Mirrors the {@code maps[]} index in
     *  {@code NPCConversationManager.doGachapon} (Ludibrium/El Nath have no town map there - they
     *  return -1 and are skipped, matching the script which only resolves the listed towns). */
    static int gachaponTownMap(int npcId) {
        return switch (npcId) {
            case NpcId.GACHAPON_HENESYS -> constants.id.MapId.HENESYS;
            case NpcId.GACHAPON_ELLINIA -> constants.id.MapId.ELLINIA;
            case NpcId.GACHAPON_PERION -> constants.id.MapId.PERION;
            case NpcId.GACHAPON_KERNING -> constants.id.MapId.KERNING_CITY;
            case NpcId.GACHAPON_SLEEPYWOOD -> constants.id.MapId.SLEEPYWOOD;
            case NpcId.GACHAPON_MUSHROOM_SHRINE -> constants.id.MapId.MUSHROOM_SHRINE;
            case NpcId.GACHAPON_NLC -> constants.id.MapId.NEW_LEAF_CITY;
            case NpcId.GACHAPON_NAUTILUS -> constants.id.MapId.NAUTILUS_HARBOR;
            default -> -1; // Showa (instanced), Ludibrium, El Nath: not town-resolvable here
        };
    }

    private static int manhattan(Point a, Point b) {
        return Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
    }
}
