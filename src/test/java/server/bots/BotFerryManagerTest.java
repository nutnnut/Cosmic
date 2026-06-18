package server.bots;

import client.Character;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BotFerryManagerTest {

    private static final BotFerryManager.FerryRoute ELLINIA = BotFerryManager.ELLINIA_TO_ORBIS;
    private static final BotFerryManager.FerryRoute ORBIS = BotFerryManager.ORBIS_TO_ELLINIA;

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
        boolean hasTicket = false;
        boolean gateOpen = false;
        boolean invaded = false;
        Point npcPos = null;

        private final BotFerryManager.TicketCheck prevTicketCheck = BotFerryManager.ticketCheck;
        private final BotFerryManager.TicketShop prevTicketShop = BotFerryManager.ticketShop;
        private final BotFerryManager.BoardAction prevBoard = BotFerryManager.boardAction;
        private final BotFerryManager.GuideAction prevGuide = BotFerryManager.guideAction;
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
}
