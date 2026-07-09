package server.bots;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BotFerryManagerTest {

    private static final BotFerryManager.FerryRoute ELLINIA = BotFerryManager.ELLINIA_TO_ORBIS;
    private static final BotFerryManager.FerryRoute ORBIS = BotFerryManager.ORBIS_TO_ELLINIA;

    // Fire NPC/portal actions on the in-range tick instead of waiting out the humanlike dwell pause.
    @BeforeEach void instantDwell() { BotManager.dwellInstant = true; }
    @AfterEach void resetDwell() { BotManager.dwellInstant = false; }

    private record Fixture(BotEntry entry, Character bot, MapleMap map) {}

    private static Fixture fixture(int mapId, Point botPos) {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(mapId);
        when(bot.getPosition()).thenReturn(botPos);
        when(map.getId()).thenReturn(mapId);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.lastMapId = mapId;
        return new Fixture(entry, bot, map);
    }

    private static Portal portalTo(MapleMap map, int id, int targetMapId, Point pos) {
        Portal portal = mock(Portal.class);
        when(portal.getId()).thenReturn(id);
        when(portal.getTargetMapId()).thenReturn(targetMapId);
        when(portal.getType()).thenReturn(Portal.MAP_PORTAL);
        when(portal.getScriptName()).thenReturn(null);
        when(portal.getPortalStatus()).thenReturn(Portal.OPEN);
        when(portal.getPosition()).thenReturn(pos);
        when(map.getPortals()).thenReturn(List.of(portal));
        when(map.getPortal(id)).thenReturn(portal);
        return portal;
    }

    /** Swaps every ferry seam plus the movement/NPC seams; restore via close(). */
    private static final class Seams implements AutoCloseable {
        final List<Point> steps = new ArrayList<>();
        final List<Integer> ticketsBought = new ArrayList<>();
        final List<Integer> boarded = new ArrayList<>();
        final List<Integer> guided = new ArrayList<>();
        final List<Integer> startedRides = new ArrayList<>();
        boolean hasTicket = false;
        boolean gateOpen = false;
        boolean invaded = false;
        boolean instanceStartable = true;
        List<BotEntry> crewMates = List.of();
        Point npcPos = null;

        private final BotFerryManager.TicketCheck prevTicketCheck = BotFerryManager.ticketCheck;
        private final BotFerryManager.TicketShop prevTicketShop = BotFerryManager.ticketShop;
        private final BotFerryManager.BoardAction prevBoard = BotFerryManager.boardAction;
        private final BotFerryManager.GuideAction prevGuide = BotFerryManager.guideAction;
        private final BotFerryManager.StartInstanceAction prevStart = BotFerryManager.startInstanceAction;
        private final BotFerryManager.CrewLookup prevCrew = BotFerryManager.crewLookup;
        private final BotFerryManager.GateCheck prevGate = BotFerryManager.gateCheck;
        private final BotFerryManager.ThreatCheck prevThreat = BotFerryManager.threatCheck;
        private final BotTravelManager.MovementStep prevMovement = BotTravelManager.movementStep;
        private final BotTravelManager.TaxiNpcLocator prevLocator = BotTravelManager.taxiNpcLocator;

        Seams() {
            BotFerryManager.ticketCheck = (bot, itemId) -> hasTicket;
            BotFerryManager.ticketShop = (bot, route) -> {
                ticketsBought.add(route.ticketItemId());
                return true;
            };
            BotFerryManager.boardAction = (bot, route) -> {
                boarded.add(route.waitingMapId());
                return true;
            };
            BotFerryManager.guideAction = (bot, route) -> {
                guided.add(route.guideTargetMapId());
                return true;
            };
            BotFerryManager.startInstanceAction = (bot, route) -> {
                if (!instanceStartable) {
                    return false;
                }
                startedRides.add(route.destinationMapId());
                return true;
            };
            BotFerryManager.crewLookup = bot -> crewMates;
            BotFerryManager.gateCheck = (bot, eventName) -> gateOpen;
            BotFerryManager.threatCheck = (bot, eventName) -> invaded;
            BotTravelManager.movementStep = (entry, targetPos, runAiTick) -> steps.add(new Point(targetPos));
            BotTravelManager.taxiNpcLocator = (map, npcId) -> npcPos;
        }

        @Override
        public void close() {
            BotFerryManager.ticketCheck = prevTicketCheck;
            BotFerryManager.ticketShop = prevTicketShop;
            BotFerryManager.boardAction = prevBoard;
            BotFerryManager.guideAction = prevGuide;
            BotFerryManager.startInstanceAction = prevStart;
            BotFerryManager.crewLookup = prevCrew;
            BotFerryManager.gateCheck = prevGate;
            BotFerryManager.threatCheck = prevThreat;
            BotTravelManager.movementStep = prevMovement;
            BotTravelManager.taxiNpcLocator = prevLocator;
        }
    }

    @Test
    void shouldWalkToSellerThenBuyTicket() {
        Fixture f = fixture(101000300, new Point(0, 0));
        when(f.bot().getMeso()).thenReturn(5000);

        try (Seams seams = new Seams()) {
            seams.npcPos = new Point(800, 0);

            // Too far: walk toward the seller.
            assertTrue(BotFerryManager.tickBoarding(f.entry(), f.bot(), ELLINIA, 0L, true));
            assertEquals(List.of(new Point(800, 0)), seams.steps);
            assertTrue(seams.ticketsBought.isEmpty());

            // In range: buy.
            when(f.bot().getPosition()).thenReturn(new Point(400, 0));
            assertTrue(BotFerryManager.tickBoarding(f.entry(), f.bot(), ELLINIA, 0L, true));
            assertEquals(List.of(4031045), seams.ticketsBought);
        }
    }

    @Test
    void shouldHailSellerFromHereWhenApproachBudgetLapses() {
        Fixture f = fixture(101000300, new Point(0, 0));
        when(f.bot().getMeso()).thenReturn(5000);

        try (Seams seams = new Seams()) {
            seams.npcPos = new Point(800, 0);
            // Budget lapsed (deadline set and now past it) while still 800px from the seller on a dock
            // ledge the nav can't stand on: buy from here instead of failing the ferry hop. The seller
            // NPC is clickable map-wide in the real client.
            f.entry().followTravelDeadlineMs = 100L;
            assertTrue(BotFerryManager.tickBoarding(f.entry(), f.bot(), ELLINIA, 200L, true));
            assertEquals(List.of(4031045), seams.ticketsBought);
        }
    }

    @Test
    void shouldNotStartBuyingWithoutTicketMoney() {
        Fixture f = fixture(101000300, new Point(0, 0));
        when(f.bot().getMeso()).thenReturn(4999);

        try (Seams seams = new Seams()) {
            seams.npcPos = new Point(100, 0);
            assertFalse(BotFerryManager.tickBoarding(f.entry(), f.bot(), ELLINIA, 0L, true));
        }
    }

    @Test
    void shouldHandTicketToUsherWhenGateIsOpen() {
        Fixture f = fixture(101000300, new Point(0, 0));

        try (Seams seams = new Seams()) {
            seams.hasTicket = true;
            seams.gateOpen = true;
            seams.npcPos = new Point(100, 0);

            assertTrue(BotFerryManager.tickBoarding(f.entry(), f.bot(), ELLINIA, 0L, true));
            assertEquals(List.of(101000301), seams.boarded);
            // A leftover ticket (bought before a relog/shutdown interrupted the trip) must not be
            // re-bought: the seller and usher share this map, so a buy attempt would fire here first
            // if hasTicket weren't honored.
            assertTrue(seams.ticketsBought.isEmpty());
        }
    }

    @Test
    void shouldWaitBesideUsherWhileGateIsClosed() {
        Fixture f = fixture(101000300, new Point(0, 0));

        try (Seams seams = new Seams()) {
            seams.hasTicket = true;
            seams.gateOpen = false;
            seams.npcPos = new Point(100, 0);
            long now = System.currentTimeMillis();

            assertTrue(BotFerryManager.tickBoarding(f.entry(), f.bot(), ELLINIA, now, true));
            assertTrue(seams.boarded.isEmpty());
            assertTrue(f.entry().followTravelDeadlineMs > now); // legitimate wait keeps the budget alive
        }
    }

    @Test
    void shouldTakePlatformGuideOnOrbisSide() {
        Fixture f = fixture(200000100, new Point(0, 0));

        try (Seams seams = new Seams()) {
            seams.hasTicket = true;
            seams.npcPos = new Point(100, 0);

            assertTrue(BotFerryManager.tickBoarding(f.entry(), f.bot(), ORBIS, 0L, true));
            assertEquals(List.of(200000110), seams.guided);
            // Seller and platform guide share this map too: a leftover ticket must skip the seller
            // and go straight to the guide, not re-buy.
            assertTrue(seams.ticketsBought.isEmpty());
        }
    }

    @Test
    void shouldWalkBackThroughScriptedExitWhenTicketlessAtLeafreDock() {
        // The Leafre dock's only exits are the ticket-gated ferry and the SCRIPTED west00 (dracoout)
        // portal. A ticketless bot here must walk back to the seller's map and re-buy — before this
        // leg existed the dock was a permanent trap (live pile-up of ~36 bots).
        Fixture f = fixture(240000110, new Point(0, 0));
        when(f.bot().getMeso()).thenReturn(100000);
        Portal west00 = mock(Portal.class);
        when(west00.getPosition()).thenReturn(new Point(5, 0));
        when(west00.getPortalStatus()).thenReturn(Portal.OPEN);
        when(west00.getScriptName()).thenReturn("dracoout");
        when(f.map().getPortals()).thenReturn(List.of()); // no PLAIN portal back — scripted only
        when(f.map().getPortal("west00")).thenReturn(west00);
        // The real dracoout script warps to 240000100; a scripted portal that does NOT change the
        // map trips the script-no-land guard, so the mock must land the warp.
        org.mockito.Mockito.doAnswer(inv -> {
            when(f.bot().getMapId()).thenReturn(240000100);
            return null;
        }).when(west00).enterPortal(any());

        try (Seams seams = new Seams()) {
            seams.hasTicket = false;

            assertTrue(BotFerryManager.tickBoarding(f.entry(), f.bot(), BotFerryManager.LEAFRE_TO_ORBIS, 0L, true));
            verify(west00).enterPortal(any());
            assertTrue(seams.ticketsBought.isEmpty()); // buy happens after landing on the seller's map
        }
    }

    @Test
    void shouldWalkBackDownTheChainWhenTicketlessAtOrbisPier() {
        // Ticketless at the Orbis pier (usher map): walk back to the PREVIOUS chain map (the walkway),
        // not fail the hop — each landing re-plans until the bot is back at the seller.
        Fixture f = fixture(200000111, new Point(0, 0));
        when(f.bot().getMeso()).thenReturn(100000);
        Portal back = portalTo(f.map(), 1, 200000110, new Point(5, 0));

        try (Seams seams = new Seams()) {
            seams.hasTicket = false;

            assertTrue(BotFerryManager.tickBoarding(f.entry(), f.bot(), ORBIS, 0L, true));
            verify(back).enterPortal(any());
        }
    }

    @Test
    void shouldCrossWalkwayThroughPierPortal() {
        Fixture f = fixture(200000110, new Point(0, 0));
        Portal toPier = portalTo(f.map(), 1, 200000111, new Point(5, 0));

        try (Seams seams = new Seams()) {
            seams.hasTicket = true;

            assertTrue(BotFerryManager.tickBoarding(f.entry(), f.bot(), ORBIS, 0L, true));
            verify(toPier).enterPortal(any());
        }
    }

    @Test
    void shouldHideInCabinWhenBalrogsInvadeTheDeck() {
        Fixture f = fixture(200090010, new Point(300, 0));
        Portal cabinDoor = portalTo(f.map(), 1, 200090011, new Point(-2, 164));

        try (Seams seams = new Seams()) {
            seams.invaded = true;

            // Walk toward the cabin door first...
            assertTrue(BotFerryManager.tickTransit(f.entry(), f.bot(), 200000100, true));
            assertEquals(List.of(new Point(-2, 164)), seams.steps);

            // ...and slip inside once in range.
            when(f.bot().getPosition()).thenReturn(new Point(0, 160));
            assertTrue(BotFerryManager.tickTransit(f.entry(), f.bot(), 200000100, true));
            verify(cabinDoor).enterPortal(any());
        }
    }

    @Test
    void shouldRideQuietDeckWithoutMoving() {
        Fixture f = fixture(200090010, new Point(300, 0));
        Portal cabinDoor = portalTo(f.map(), 1, 200090011, new Point(-2, 164));

        try (Seams seams = new Seams()) {
            assertTrue(BotFerryManager.tickTransit(f.entry(), f.bot(), 200000100, true));
            assertTrue(seams.steps.isEmpty());
            verify(cabinDoor, never()).enterPortal(any());
        }
    }

    @Test
    void shouldFollowTargetIntoCabinEvenWithoutInvasion() {
        Fixture f = fixture(200090010, new Point(0, 160));
        Portal cabinDoor = portalTo(f.map(), 1, 200090011, new Point(-2, 164));

        try (Seams seams = new Seams()) {
            assertTrue(BotFerryManager.tickTransit(f.entry(), f.bot(), 200090011, true));
            verify(cabinDoor).enterPortal(any());
        }
    }

    @Test
    void shouldIgnoreMapsOutsideTheFerry() {
        Fixture f = fixture(100000000, new Point(0, 0));

        try (Seams seams = new Seams()) {
            assertFalse(BotFerryManager.tickTransit(f.entry(), f.bot(), 104000000, true));
            assertFalse(BotFerryManager.tickBoarding(f.entry(), f.bot(), ELLINIA, 0L, true));
        }
    }

    @Test
    void shouldResolveMultipleFerryLinesFromTheOrbisHub() {
        // The Orbis hall 200000100 boards four different lines: findFerryEdge must pick the one whose
        // destination matches (the single-route-per-map index would have collapsed these to one).
        assertTrue(BotFerryManager.findFerryEdge(200000100, 101000300) == BotFerryManager.ORBIS_TO_ELLINIA);
        assertTrue(BotFerryManager.findFerryEdge(200000100, 220000100) == BotFerryManager.ORBIS_TO_LUDIBRIUM);
        assertTrue(BotFerryManager.findFerryEdge(200000100, 240000100) == BotFerryManager.ORBIS_TO_LEAFRE);
        assertTrue(BotFerryManager.findFerryEdge(200000100, 260000100) == BotFerryManager.ORBIS_TO_ARIANT);
        assertTrue(BotFerryManager.routesBoardingAt(200000100).size() >= 4);
        // Return legs board at each far station back to Orbis.
        assertTrue(BotFerryManager.findFerryEdge(220000100, 200000100) == BotFerryManager.LUDIBRIUM_TO_ORBIS);
        assertTrue(BotFerryManager.findFerryEdge(240000100, 200000100) == BotFerryManager.LEAFRE_TO_ORBIS);
        assertTrue(BotFerryManager.findFerryEdge(260000100, 200000100) == BotFerryManager.ARIANT_TO_ORBIS);
        // A destination no line sails to resolves to null.
        assertFalse(BotFerryManager.findFerryEdge(200000100, 999999999) != null);
        // Solo rides (ticketItemId 0) resolve as edges too.
        assertTrue(BotFerryManager.findFerryEdge(200000161, 130000210) == BotFerryManager.ORBIS_TO_EREVE);
        assertTrue(BotFerryManager.findFerryEdge(101000400, 130000210) == BotFerryManager.ELLINIA_TO_EREVE);
        assertTrue(BotFerryManager.findFerryEdge(130000210, 101000400) == BotFerryManager.EREVE_TO_ELLINIA);
        assertTrue(BotFerryManager.findFerryEdge(104000000, 140020300) == BotFerryManager.LITH_TO_RIEN);
        assertTrue(BotFerryManager.findFerryEdge(140020300, 104000000) == BotFerryManager.RIEN_TO_LITH);
    }

    @Test
    void shouldStartFreeTrainInstanceAtTheTicketGate() {
        Fixture f = fixture(103000100, new Point(0, 0));
        when(f.bot().getMeso()).thenReturn(0);

        try (Seams seams = new Seams()) {
            seams.npcPos = new Point(100, 0);

            assertTrue(BotFerryManager.tickBoarding(f.entry(), f.bot(), BotFerryManager.KC_TO_KSQUARE, 0L, true));
            assertEquals(List.of(103000310), seams.startedRides); // boarded the train to Kerning Square
            verify(f.bot(), never()).gainMeso(anyInt(), anyBoolean()); // the train is free
        }
    }

    @Test
    void shouldPayFareThenStartTheCraneInstance() {
        Fixture f = fixture(200000141, new Point(0, 0));
        when(f.bot().getMeso()).thenReturn(1500);

        try (Seams seams = new Seams()) {
            seams.npcPos = new Point(100, 0);

            assertTrue(BotFerryManager.tickBoarding(f.entry(), f.bot(), BotFerryManager.ORBIS_TO_MULUNG, 0L, true));
            assertEquals(List.of(250000100), seams.startedRides);
            verify(f.bot()).gainMeso(-1500, false); // pay only once the crane actually departs
        }
    }

    @Test
    void shouldNotChargeFareWhenTheCraneLobbyIsFull() {
        Fixture f = fixture(200000141, new Point(0, 0));
        when(f.bot().getMeso()).thenReturn(1500);

        try (Seams seams = new Seams()) {
            seams.npcPos = new Point(100, 0);
            seams.instanceStartable = false;

            assertFalse(BotFerryManager.tickBoarding(f.entry(), f.bot(), BotFerryManager.ORBIS_TO_MULUNG, 0L, true));
            verify(f.bot(), never()).gainMeso(anyInt(), anyBoolean());
        }
    }

    @Test
    void shouldNotBoardTheCraneWithoutTheFare() {
        Fixture f = fixture(200000141, new Point(0, 0));
        when(f.bot().getMeso()).thenReturn(1499);

        try (Seams seams = new Seams()) {
            seams.npcPos = new Point(100, 0);
            assertFalse(BotFerryManager.tickBoarding(f.entry(), f.bot(), BotFerryManager.ORBIS_TO_MULUNG, 0L, true));
        }
    }

    @Test
    void shouldEnterTheScriptedBoardingPortalForTheReturnTrain() {
        Fixture f = fixture(103000310, new Point(0, 0));
        Portal out00 = mock(Portal.class);
        when(out00.getPosition()).thenReturn(new Point(0, 0));
        when(f.map().getPortal("out00")).thenReturn(out00);
        long now = System.currentTimeMillis();

        try (Seams seams = new Seams()) {
            assertTrue(BotFerryManager.tickBoarding(f.entry(), f.bot(), BotFerryManager.KSQUARE_TO_KC, now, true));
            verify(out00).enterPortal(any()); // its own script runs startInstance / the warp
            assertTrue(f.entry().followTravelDeadlineMs > now); // waiting for the ride keeps the budget alive
        }
    }

    @Test
    void shouldResolveTheNewEventManagerTransitEdges() {
        assertTrue(BotFerryManager.findFerryEdge(103000100, 600010001) == BotFerryManager.KC_TO_NLC);
        assertTrue(BotFerryManager.findFerryEdge(600010001, 103000100) == BotFerryManager.NLC_TO_KC);
        assertTrue(BotFerryManager.findFerryEdge(103000000, 540010000) == BotFerryManager.KC_TO_SINGAPORE);
        assertTrue(BotFerryManager.findFerryEdge(540010000, 103000000) == BotFerryManager.SINGAPORE_TO_KC);
        assertTrue(BotFerryManager.findFerryEdge(103000100, 103000310) == BotFerryManager.KC_TO_KSQUARE);
        assertTrue(BotFerryManager.findFerryEdge(103000310, 103000100) == BotFerryManager.KSQUARE_TO_KC);
        assertTrue(BotFerryManager.findFerryEdge(200000141, 250000100) == BotFerryManager.ORBIS_TO_MULUNG);
        assertTrue(BotFerryManager.findFerryEdge(250000100, 200000141) == BotFerryManager.MULUNG_TO_ORBIS);
        assertTrue(BotFerryManager.findFerryEdge(222020100, 222020200) == BotFerryManager.HELIOS_UP);
        assertTrue(BotFerryManager.findFerryEdge(222020200, 222020100) == BotFerryManager.HELIOS_DOWN);
        // The Kerning City subway hall boards two lines (NLC subway + Kerning Square train), and Kerning
        // town itself now boards the Singapore airplane.
        assertTrue(BotFerryManager.routesBoardingAt(103000100).size() >= 2);
        assertTrue(BotFerryManager.routesBoardingAt(103000000).contains(BotFerryManager.KC_TO_SINGAPORE));
    }

    @Test
    void soloShouldWalkToItsRandomFerryStandSpotThenSettle() {
        Fixture f = fixture(200090010, new Point(0, 0)); // a ride deck
        Point anchor = new Point(300, 0); // footholds null in the mock -> the picker returns the anchor

        try (Seams seams = new Seams()) {
            BotFerryManager.tickFerryStanding(f.entry(), f.bot(), 0L, true, anchor);
            assertEquals(List.of(new Point(300, 0)), seams.steps); // wandered toward the spot
            assertEquals(new Point(300, 0), f.entry().ferryStandSpot);
            assertTrue(f.entry().ferryStandRepickAtMs > 0L); // jittered re-pick timer armed (desync)

            // Once standing on the spot it stops walking and arms the fidget roll instead.
            seams.steps.clear();
            when(f.bot().getPosition()).thenReturn(new Point(300, 0));
            BotFerryManager.tickFerryStanding(f.entry(), f.bot(), 1L, true, anchor);
            assertTrue(seams.steps.isEmpty());
            assertTrue(f.entry().nextIdleFidgetRollAtMs > 0L);
        }
    }

    @Test
    void crewFollowerShouldHoldFormationBehindTheLeaderAndNotPickItsOwnSpot() {
        Character leaderBot = mock(Character.class);
        when(leaderBot.getId()).thenReturn(1);
        when(leaderBot.getPosition()).thenReturn(new Point(100, 0));
        BotEntry leader = new BotEntry(leaderBot, null, null);

        Fixture f = fixture(200090010, new Point(0, 0));
        when(f.bot().getId()).thenReturn(2); // higher id -> follower

        try (Seams seams = new Seams()) {
            seams.crewMates = List.of(leader);

            BotFerryManager.tickFerryStanding(f.entry(), f.bot(), 0L, true, null);
            // STAGGER slot 0 of 1 follower = +FOLLOW_STAGGER (60) from the leader.
            assertEquals(List.of(new Point(160, 0)), seams.steps);
            assertNull(f.entry().ferryStandSpot); // a follower never loiters on its own
        }
    }

    @Test
    void crewLeaderShouldLoiterRatherThanFollowItsLowerMembers() {
        Character mateBot = mock(Character.class);
        when(mateBot.getId()).thenReturn(9); // higher id -> the follower
        BotEntry mate = new BotEntry(mateBot, null, null);

        Fixture f = fixture(200090010, new Point(0, 0));
        when(f.bot().getId()).thenReturn(1); // lowest id -> the leader
        Point anchor = new Point(250, 0);

        try (Seams seams = new Seams()) {
            seams.crewMates = List.of(mate);

            BotFerryManager.tickFerryStanding(f.entry(), f.bot(), 0L, true, anchor);
            assertEquals(new Point(250, 0), f.entry().ferryStandSpot); // leader picks a spot of its own
            assertEquals(List.of(new Point(250, 0)), seams.steps);
        }
    }
}
