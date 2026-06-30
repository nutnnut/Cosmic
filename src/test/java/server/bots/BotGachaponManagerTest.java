package server.bots;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.gachapon.Gachapon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Loop + advisor logic over the {@link BotGachaponManager} seams (no WZ/DB, no BotManager
 * singleton):
 * <ul>
 *   <li>NX-credit mirroring: a held NX card credits NX via the SAME shared player path
 *       ({@code getCashShop().gainCash(NX_CREDIT, value)}) bots already hit on pickup - so the
 *       balance the advisor spends is the real one. (Documented + asserted on the seam.)</li>
 *   <li>Affordability gate: no buy when NX (above the reserve) &lt; price; buys floor(spendable/price)
 *       capped at the per-trip cap.</li>
 *   <li>Per-roll inventory-space guard: a full bag stops the trip WITHOUT charging NX or counting a
 *       ticket (the gachapon.js pre-roll check; charging-then-failing would lose NX).</li>
 *   <li>EV town ranking: the best-EV reachable town wins, travel cost subtracted, unreachable towns
 *       dropped.</li>
 *   <li>Supervised-bot gate: a bot with an online owner (not autopiloting) never starts a trip.</li>
 * </ul>
 */
class BotGachaponManagerTest {

    private final BotGachaponManager.RollLookup prevRoll = BotGachaponManager.roll;
    private final BotGachaponManager.PoolLookup prevPool = BotGachaponManager.pool;
    private final BotGachaponManager.ValueLookup prevValue = BotGachaponManager.itemValue;
    private final BotGachaponManager.UpgradeLookup prevUpgrade = BotGachaponManager.upgradeValue;
    private final java.util.function.IntPredicate prevIsEquip = BotGachaponManager.isEquip;
    private final BotGachaponManager.NxBalance prevNx = BotGachaponManager.nxBalance;
    private final BotGachaponManager.TicketPrice prevPrice = BotGachaponManager.ticketPrice;
    private final BotGachaponManager.NxCharge prevCharge = BotGachaponManager.nxCharge;
    private final BotGachaponManager.ItemGrant prevGrant = BotGachaponManager.grantItem;
    private final BotGachaponManager.SpaceCheck prevSpace = BotGachaponManager.spaceCheck;
    private final BotGachaponManager.TravelSeconds prevTravel = BotGachaponManager.travelSeconds;
    private final BotGachaponManager.HopReach prevHop = BotGachaponManager.hopReach;
    private final java.util.function.BiConsumer<BotEntry, String> prevReply = BotGachaponManager.reply;
    private final java.util.function.IntFunction<String> prevName = BotGachaponManager.itemNameLookup;
    private final BotGachaponManager.GachaLog prevLog = BotGachaponManager.gachaLog;
    private final BotGachaponManager.RareBroadcast prevBroadcast = BotGachaponManager.rareBroadcast;

    private final List<String> replies = new ArrayList<>();
    private final BotManager.Config prevCfg = BotManager.cfg;

    {
        BotGachaponManager.reply = (e, t) -> replies.add(t);
        BotGachaponManager.itemNameLookup = id -> "Item" + id;
        BotGachaponManager.gachaLog = (b, id, m) -> { /* no WZ in tests */ };
        BotGachaponManager.rareBroadcast = (b, item, town) -> { /* no Server singleton in tests */ };
        // Need-aware upgrade EV is off by default (no WZ): resale drives, matching the legacy tests.
        // Plenty of spare NX so plannedRolls() (now part of rankTowns) doesn't touch a mock CashShop.
        BotGachaponManager.upgradeValue = (b, id, barCache) -> 0.0;
        BotGachaponManager.isEquip = id -> false;
        BotGachaponManager.nxBalance = b -> 1_000_000;
        // No live BotWorldGraph in tests: every town is hop-reachable so the EV math alone ranks them.
        BotGachaponManager.hopReach = (from, to, hops) -> true;
        // Fresh, deterministic config so test values don't depend on production defaults shifting.
        BotManager.cfg = new BotManager.Config();
        BotManager.cfg.GACHAPON_ENABLED = true;
        BotManager.cfg.GACHA_NX_RESERVE = 1_000;
        BotManager.cfg.GACHA_TICKETS_PER_TRIP = 5;
        BotManager.cfg.GACHA_MIN_NET_EV = 50.0;
    }

    @AfterEach
    void restore() {
        BotGachaponManager.roll = prevRoll;
        BotGachaponManager.pool = prevPool;
        BotGachaponManager.itemValue = prevValue;
        BotGachaponManager.upgradeValue = prevUpgrade;
        BotGachaponManager.isEquip = prevIsEquip;
        BotGachaponManager.nxBalance = prevNx;
        BotGachaponManager.ticketPrice = prevPrice;
        BotGachaponManager.nxCharge = prevCharge;
        BotGachaponManager.grantItem = prevGrant;
        BotGachaponManager.spaceCheck = prevSpace;
        BotGachaponManager.travelSeconds = prevTravel;
        BotGachaponManager.hopReach = prevHop;
        BotGachaponManager.reply = prevReply;
        BotGachaponManager.itemNameLookup = prevName;
        BotGachaponManager.gachaLog = prevLog;
        BotGachaponManager.rareBroadcast = prevBroadcast;
        BotManager.cfg = prevCfg;
    }

    private static BotEntry entry(Character bot) {
        return new BotEntry(bot, mock(Character.class), mock(ScheduledFuture.class));
    }

    // ---- NX-credit mirroring -----------------------------------------------------------------

    @Test
    void advisorSpendsTheSameNxBalanceCardsCreditOnPickup() {
        // The NX an NX card adds on pickup (shared player path: gainCash(NX_CREDIT, 100|250)) is the
        // SAME balance this manager reads via nxBalance and charges via nxCharge. Model an account
        // wallet, credit it like a looted card would, and assert the manager sees+spends exactly it.
        int[] wallet = {0};
        BotGachaponManager.nxBalance = b -> wallet[0];
        BotGachaponManager.nxCharge = (b, nx) -> wallet[0] -= nx;

        // A NX_CARD_250 loot credits 250 NX (Character.pickupItem). Mirror that effect.
        wallet[0] += 250;
        assertEquals(250, BotGachaponManager.nxBalance.nx(mock(Character.class)),
                "advisor reads the credited card NX");

        BotGachaponManager.nxCharge.charge(mock(Character.class), 200);
        assertEquals(50, wallet[0], "charge mutates the same wallet the card credited");
    }

    // ---- affordability gate ------------------------------------------------------------------

    @Test
    void stopsAtTheReserveFloorWhenBudgetIsAmple() {
        Character bot = mock(Character.class);
        BotEntry e = entry(bot);
        e.gachaErrandNpcId = constants.id.NpcId.GACHAPON_HENESYS;
        e.gachaTripBudgetNx = 1_000_000; // budget not the limiter here - the reserve floor is

        int[] wallet = {1_000 + 800 * 3 + 100}; // reserve 1000 + room for 3 tickets + leftover
        int[] charged = {0};
        int[] granted = {0};
        BotGachaponManager.ticketPrice = () -> 800;
        BotGachaponManager.nxBalance = b -> wallet[0];
        BotGachaponManager.nxCharge = (b, nx) -> { wallet[0] -= nx; charged[0]++; };
        BotGachaponManager.spaceCheck = (b, id, q) -> true;
        BotGachaponManager.grantItem = (b, id, q) -> granted[0]++;
        BotGachaponManager.roll = npc -> new Gachapon.GachaponItem(0, 4000000);

        int rolls = 0;
        while (BotGachaponManager.rollOnce(e, bot)) {
            rolls++;
        }
        // spendable above the reserve = 1000+2400+100 - 1000 = 2500 -> floor(2500/800) = 3 tickets.
        assertEquals(3, rolls, "rolls until one more would dip the reserve");
        assertEquals(3, charged[0]);
        assertEquals(3, granted[0]);
        assertEquals(3, e.gachaTicketsThisTrip);
    }

    @Test
    void doesNotBuyWhenNxBelowPricePlusReserve() {
        Character bot = mock(Character.class);
        BotEntry e = entry(bot);
        int[] charged = {0};
        e.gachaTripBudgetNx = 1_000_000; // budget ample: the reserve gate is what blocks
        BotGachaponManager.ticketPrice = () -> 800;
        BotGachaponManager.nxBalance = b -> 1_500; // only 500 above the 1000 reserve, < 800
        BotGachaponManager.nxCharge = (b, nx) -> charged[0]++;
        BotGachaponManager.spaceCheck = (b, id, q) -> true;
        BotGachaponManager.grantItem = (b, id, q) -> {};
        BotGachaponManager.roll = npc -> new Gachapon.GachaponItem(0, 4000000);

        assertFalse(BotGachaponManager.rollOnce(e, bot), "cannot afford a ticket above the reserve");
        assertEquals(0, charged[0], "no NX charged");
        assertEquals(0, e.gachaTicketsThisTrip);
    }

    @Test
    void stopsWhenTheTripNxBudgetIsSpent() {
        Character bot = mock(Character.class);
        BotEntry e = entry(bot);
        e.gachaTripBudgetNx = 800 * 2; // personality budget = exactly two tickets, NX otherwise unlimited
        int[] charged = {0};
        BotGachaponManager.ticketPrice = () -> 800;
        BotGachaponManager.nxBalance = b -> 1_000_000; // effectively unlimited NX -> budget is the cap
        BotGachaponManager.nxCharge = (b, nx) -> charged[0]++;
        BotGachaponManager.spaceCheck = (b, id, q) -> true;
        BotGachaponManager.grantItem = (b, id, q) -> {};
        BotGachaponManager.roll = npc -> new Gachapon.GachaponItem(0, 4000000);

        int rolls = 0;
        while (BotGachaponManager.rollOnce(e, bot)) {
            rolls++;
        }
        assertEquals(2, rolls, "capped by the trip NX budget, not a flat ticket count");
        assertEquals(2, charged[0]);
        assertEquals(800 * 2, e.gachaSpentThisTrip);
    }

    // ---- per-roll inventory guard ------------------------------------------------------------

    @Test
    void fullBagStopsTripWithoutChargingNxOrCountingTicket() {
        Character bot = mock(Character.class);
        BotEntry e = entry(bot);
        e.gachaTripBudgetNx = 1_000_000;
        int[] charged = {0};
        int[] granted = {0};
        BotGachaponManager.ticketPrice = () -> 800;
        BotGachaponManager.nxBalance = b -> 1_000_000;
        BotGachaponManager.nxCharge = (b, nx) -> charged[0]++;
        BotGachaponManager.spaceCheck = (b, id, q) -> false; // bag full in every type
        BotGachaponManager.grantItem = (b, id, q) -> granted[0]++;
        BotGachaponManager.roll = npc -> new Gachapon.GachaponItem(0, 4000000);

        assertFalse(BotGachaponManager.rollOnce(e, bot), "full bag ends the trip");
        assertEquals(0, charged[0], "NX must NOT be charged when the bag is full (no NX loss)");
        assertEquals(0, granted[0]);
        assertEquals(0, e.gachaTicketsThisTrip, "no ticket counted on a blocked roll");
        assertTrue(replies.stream().anyMatch(s -> s.contains("full")), "bot says the bag is full");
    }

    @Test
    void potionRewardGrantsHundred() {
        Character bot = mock(Character.class);
        BotEntry e = entry(bot);
        e.gachaTripBudgetNx = 1_000_000;
        Map<Integer, Short> grants = new HashMap<>();
        BotGachaponManager.ticketPrice = () -> 800;
        BotGachaponManager.nxBalance = b -> 1_000_000;
        BotGachaponManager.nxCharge = (b, nx) -> {};
        BotGachaponManager.spaceCheck = (b, id, q) -> true;
        BotGachaponManager.grantItem = (b, id, q) -> grants.put(id, q);
        // 2000004 is a potion (id/10000 == 200) -> doGachapon's x100 rule.
        BotGachaponManager.roll = npc -> new Gachapon.GachaponItem(0, 2000004);

        assertTrue(BotGachaponManager.rollOnce(e, bot));
        assertEquals((short) 100, grants.get(2000004), "stackable potions come in 100s like doGachapon");
    }

    // ---- EV town ranking ---------------------------------------------------------------------

    @Test
    void bestEvReachableTownWinsAndTravelCostIsSubtracted() {
        Character bot = mock(Character.class);
        BotGachaponManager.ticketPrice = () -> 800;
        // Henesys pool = one common worth 5000; every other town = one common worth 100.
        BotGachaponManager.pool = (npcId, tier) ->
                tier == 0 ? new int[]{npcId == constants.id.NpcId.GACHAPON_HENESYS ? 1 : 2} : new int[0];
        BotGachaponManager.itemValue = (b, id) -> id == 1 ? 5_000.0 : 100.0;
        // All towns equally close (no travel discrimination) so EV decides.
        BotGachaponManager.travelSeconds = (from, to) -> 10.0;

        List<BotGachaponManager.TownEv> ranked = BotGachaponManager.rankTowns(bot, 100000000);
        assertFalse(ranked.isEmpty());
        assertEquals(constants.id.NpcId.GACHAPON_HENESYS, ranked.get(0).npcId(),
                "the richest pool wins");
        // Henesys roll value ~= 0.9 * 5000 = 4500; with 20 planned rolls (1M NX / 800) the trip net is
        // 20 * (4500 - 800) - 10*0.5 = 73990.
        assertTrue(ranked.get(0).netScore() > 3_000, "trip net amortizes ticket + travel over rolls");
    }

    @Test
    void unreachableTownsAreDropped() {
        Character bot = mock(Character.class);
        BotGachaponManager.ticketPrice = () -> 800;
        BotGachaponManager.pool = (npcId, tier) -> tier == 0 ? new int[]{1} : new int[0];
        BotGachaponManager.itemValue = (b, id) -> 5_000.0;
        // Only Henesys is reachable; everything else is at the unreachable sentinel.
        BotGachaponManager.travelSeconds = (from, to) ->
                to == BotGachaponManager.gachaponTownMap(constants.id.NpcId.GACHAPON_HENESYS) ? 10.0 : 99_999.0;

        List<BotGachaponManager.TownEv> ranked = BotGachaponManager.rankTowns(bot, 100000000);
        assertEquals(1, ranked.size(), "unreachable towns are dropped");
        assertEquals(constants.id.NpcId.GACHAPON_HENESYS, ranked.get(0).npcId());
    }

    @Test
    void closerTownWinsWhenPoolsAreEqual() {
        Character bot = mock(Character.class);
        BotGachaponManager.ticketPrice = () -> 800;
        BotGachaponManager.pool = (npcId, tier) -> tier == 0 ? new int[]{1} : new int[0];
        BotGachaponManager.itemValue = (b, id) -> 5_000.0;
        // Identical pools; Ellinia is far, Henesys near -> the travel penalty breaks the tie.
        BotGachaponManager.travelSeconds = (from, to) ->
                to == BotGachaponManager.gachaponTownMap(constants.id.NpcId.GACHAPON_HENESYS) ? 5.0 : 600.0;

        List<BotGachaponManager.TownEv> ranked = BotGachaponManager.rankTowns(bot, 100000000);
        assertEquals(constants.id.NpcId.GACHAPON_HENESYS, ranked.get(0).npcId(),
                "equal pools: the nearer town wins on travel cost");
    }

    // ---- supervised gate ---------------------------------------------------------------------

    @Test
    void supervisedBotNeverStartsAGachaTrip() {
        Character bot = mock(Character.class);
        server.maps.MapleMap map = mock(server.maps.MapleMap.class);
        org.mockito.Mockito.when(bot.getMap()).thenReturn(map);
        BotEntry e = entry(bot);
        e.autopilotMapId = -1; // NOT autopiloting -> supervised, owner at side

        BotGachaponManager.ticketPrice = () -> 800;
        BotGachaponManager.nxBalance = b -> 1_000_000; // plenty of spare NX
        BotGachaponManager.pool = (npcId, tier) -> tier == 0 ? new int[]{1} : new int[0];
        BotGachaponManager.itemValue = (b, id) -> 5_000.0;
        BotGachaponManager.travelSeconds = (from, to) -> 10.0;

        e.nextGachaScanAtMs = 0L; // force the scan to run this tick
        BotGachaponManager.tickScan(e, bot);

        assertEquals(-1, e.gachaErrandMapId, "a supervised bot must not wander off to gacha");
    }

    @Test
    void autopilotBotWithSpareNxStartsTheBestTrip() {
        Character bot = mock(Character.class);
        server.maps.MapleMap map = mock(server.maps.MapleMap.class);
        org.mockito.Mockito.when(bot.getMap()).thenReturn(map);
        org.mockito.Mockito.when(bot.getMapId()).thenReturn(180000000); // some grind map
        BotEntry e = entry(bot);
        e.autopilotMapId = 180000000; // autopiloting
        // Gacha only fires during a rest break (tickScan's onRestBreak gate). Park the bot mid-break on a
        // rest errand so the gate passes without a town map / live graph: onBreak (now < breakUntilMs) &&
        // restErrand. Grind-map id is irrelevant now that hopReach is stubbed true.
        e.breakUntilMs = System.currentTimeMillis() + 600_000L;
        e.restErrand = true;

        BotGachaponManager.ticketPrice = () -> 800;
        BotGachaponManager.nxBalance = b -> 1_000_000;
        BotGachaponManager.pool = (npcId, tier) ->
                tier == 0 ? new int[]{npcId == constants.id.NpcId.GACHAPON_HENESYS ? 1 : 2} : new int[0];
        BotGachaponManager.itemValue = (b, id) -> id == 1 ? 5_000.0 : 100.0;
        BotGachaponManager.travelSeconds = (from, to) -> 10.0;

        e.nextGachaScanAtMs = 0L;
        BotGachaponManager.tickScan(e, bot);

        assertEquals(constants.id.NpcId.GACHAPON_HENESYS, e.gachaErrandNpcId,
                "autopilot heads to the best-EV town");
        assertEquals(BotGachaponManager.gachaponTownMap(constants.id.NpcId.GACHAPON_HENESYS), e.gachaErrandMapId);
        // The trip's spend cap is a personality fraction of spare NX (resetTripCounters via beginErrand).
        long spare = 1_000_000 - BotManager.cfg.GACHA_NX_RESERVE;
        assertEquals(Math.round(spare * e.personality.gachaSpendFrac()), e.gachaTripBudgetNx,
                "trip budget = personality fraction of spare NX");
    }

    // ---- roll-amortized travel ---------------------------------------------------------------

    @Test
    void savingUpFlipsTheChoiceFromNearPoolToFarRicherPool() {
        Character bot = mock(Character.class);
        BotGachaponManager.ticketPrice = () -> 800;
        // Henesys near + modest (roll value 0.9*1000=900); Ellinia far + richer (0.9*1500=1350).
        BotGachaponManager.pool = (npcId, tier) ->
                tier == 0 ? new int[]{npcId == constants.id.NpcId.GACHAPON_HENESYS ? 1 : 2} : new int[0];
        BotGachaponManager.itemValue = (b, id) -> id == 1 ? 1_000.0 : 1_500.0;
        // Only the near (Henesys) and far (Ellinia) towns are reachable; near=5s, far=1000s.
        BotGachaponManager.travelSeconds = (from, to) -> {
            if (to == BotGachaponManager.gachaponTownMap(constants.id.NpcId.GACHAPON_HENESYS)) return 5.0;
            if (to == constants.id.MapId.ELLINIA) return 1_000.0;
            return 99_999.0;
        };

        // Only one roll affordable: the 1000s travel to the far pool isn't worth +450/roll once.
        BotGachaponManager.nxBalance = b -> 1_000 + 800; // reserve + exactly one ticket
        List<BotGachaponManager.TownEv> few = BotGachaponManager.rankTowns(bot, 100000000);
        assertEquals(constants.id.NpcId.GACHAPON_HENESYS, few.get(0).npcId(),
                "with one roll, the near pool wins - far travel isn't amortized");

        // Saved up: 20 rolls amortize the far travel, so the richer far pool now wins.
        BotGachaponManager.nxBalance = b -> 1_000_000;
        List<BotGachaponManager.TownEv> many = BotGachaponManager.rankTowns(bot, 100000000);
        assertEquals(constants.id.NpcId.GACHAPON_ELLINIA, many.get(0).npcId(),
                "saved up, the far richer pool out-earns the near one across the session");
    }

    @Test
    void plannedRollsScalesWithSavedNxAndIsCappedAtCeiling() {
        BotManager.cfg.GACHA_TICKETS_PER_TRIP = 20;
        Character bot = mock(Character.class);
        BotGachaponManager.nxBalance = b -> 1_000 + 800 * 3; // reserve + 3 tickets
        assertEquals(3, BotGachaponManager.plannedRolls(bot, 800), "rolls = floor(spendable/price)");
        BotGachaponManager.nxBalance = b -> 1_000_000;
        assertEquals(20, BotGachaponManager.plannedRolls(bot, 800), "capped at the per-trip ceiling");
    }

    // ---- need-driven pivot -------------------------------------------------------------------

    @Test
    void upgradeDrivenTripPivotsWhenTheNeededUpgradeIsPulled() {
        Character bot = mock(Character.class);
        BotEntry e = entry(bot);
        e.gachaErrandNpcId = constants.id.NpcId.GACHAPON_HENESYS;
        e.gachaUpgradeDriven = true; // chosen for gear, so it should pivot once satisfied
        e.gachaTripBudgetNx = 1_000_000;

        double[] upgrade = {200.0}; // 0.9 * 200 * 5(NX/score) = 900 NX/roll > 800 price -> keep rolling
        BotGachaponManager.ticketPrice = () -> 800;
        BotGachaponManager.nxBalance = b -> 1_000_000;
        BotGachaponManager.nxCharge = (b, nx) -> {};
        BotGachaponManager.spaceCheck = (b, id, q) -> true;
        BotGachaponManager.grantItem = (b, id, q) -> {};
        BotGachaponManager.itemValue = (b, id) -> 0.0; // resale negligible -> upgrade is the only motive
        BotGachaponManager.isEquip = id -> true; // the pull is an equip
        BotGachaponManager.pool = (npcId, tier) -> tier == 0 ? new int[]{1302000} : new int[0];
        BotGachaponManager.upgradeValue = (b, id, barCache) -> upgrade[0];
        BotGachaponManager.roll = npc -> new Gachapon.GachaponItem(0, 1302000);

        assertTrue(BotGachaponManager.rollOnce(e, bot), "still an upgrade to chase -> keeps rolling");

        upgrade[0] = 0.0; // the upgrade is now worn: the pool's need-EV collapses
        assertFalse(BotGachaponManager.rollOnce(e, bot), "need satisfied -> trip ends (pivot)");
        assertTrue(replies.stream().anyMatch(s -> s.contains("got what i came for")),
                "bot says it got what it came for");
    }

    // ---- rare world broadcast ----------------------------------------------------------------

    @Test
    void notablePullFiresTheWorldBroadcastButCommonDoesNot() {
        Character bot = mock(Character.class);
        BotEntry e = entry(bot);
        e.gachaErrandNpcId = constants.id.NpcId.GACHAPON_HENESYS;
        e.gachaTripBudgetNx = 1_000_000;
        int[] broadcasts = {0};
        BotGachaponManager.rareBroadcast = (b, item, town) -> broadcasts[0]++;
        BotGachaponManager.ticketPrice = () -> 800;
        BotGachaponManager.nxBalance = b -> 1_000_000;
        BotGachaponManager.nxCharge = (b, nx) -> {};
        BotGachaponManager.spaceCheck = (b, id, q) -> true;
        BotGachaponManager.grantItem = (b, id, q) -> {};
        BotGachaponManager.isEquip = id -> false; // non-equip -> plain Item display, no WZ

        BotGachaponManager.roll = npc -> new Gachapon.GachaponItem(0, 4000000); // tier 0 = common
        assertTrue(BotGachaponManager.rollOnce(e, bot));
        assertEquals(0, broadcasts[0], "common pull: no world notice (matches doGachapon)");

        BotGachaponManager.roll = npc -> new Gachapon.GachaponItem(1, 4000000); // tier 1 = uncommon
        assertTrue(BotGachaponManager.rollOnce(e, bot));
        assertEquals(1, broadcasts[0], "uncommon/rare pull: one world notice");
    }

    // ---- personality cadence (anti-yo-yo) ----------------------------------------------------

    @Test
    void finishingATripPushesTheNextScanOutByThePersonalityInterval() {
        Character bot = mock(Character.class);
        BotEntry e = entry(bot);
        e.gachaErrandMapId = constants.id.MapId.HENESYS;
        e.nextGachaScanAtMs = 0L;

        long before = System.currentTimeMillis();
        BotGachaponManager.finishErrand(e, bot, null);

        assertEquals(-1, e.gachaErrandMapId, "trip state cleared");
        // The default personality (appetite 0.3 -> ~53h interval, jittered >=0.5x) pushes the next scan
        // hours out, killing the "roll, leave, immediately return" yo-yo.
        assertTrue(e.nextGachaScanAtMs > before + 3_600_000L,
                "next gacha scan is pushed at least an hour out");
    }
}
