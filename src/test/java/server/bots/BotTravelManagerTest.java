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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BotTravelManagerTest {

    private static final int HENESYS = 100000000;
    private static final int HUNTING_GROUND = 100040000;

    private static Portal portal(int id, int targetMapId, int type, String script, boolean open, Point pos) {
        Portal portal = mock(Portal.class);
        when(portal.getId()).thenReturn(id);
        when(portal.getTargetMapId()).thenReturn(targetMapId);
        when(portal.getType()).thenReturn(type);
        when(portal.getScriptName()).thenReturn(script);
        when(portal.getPortalStatus()).thenReturn(open);
        when(portal.getPosition()).thenReturn(pos);
        return portal;
    }

    private record Fixture(BotEntry entry, Character bot, Character anchor, MapleMap map) {}

    private static Fixture fixture(int botMapId, int anchorMapId, Point botPos, List<Portal> portals) {
        Character bot = mock(Character.class);
        Character anchor = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(botMapId);
        when(bot.getPosition()).thenReturn(botPos);
        when(anchor.getMapId()).thenReturn(anchorMapId);
        when(map.getPortals()).thenReturn(portals);
        for (Portal portal : portals) {
            when(map.getPortal(portal.getId())).thenReturn(portal);
        }
        BotEntry entry = new BotEntry(bot, null, null);
        entry.lastMapId = botMapId;
        return new Fixture(entry, bot, anchor, map);
    }

    /** Swaps the movement seam for a recorder; restore via close(). */
    private static final class MovementRecorder implements AutoCloseable {
        final List<Point> steps = new ArrayList<>();
        private final BotTravelManager.MovementStep previous = BotTravelManager.movementStep;

        MovementRecorder() {
            BotTravelManager.movementStep = (entry, targetPos, runAiTick) -> steps.add(new Point(targetPos));
        }

        @Override
        public void close() {
            BotTravelManager.movementStep = previous;
        }
    }

    /** Swaps the world-graph seam (the default lookup would trigger a WZ scan); restore via close(). */
    private static final class RouteStub implements AutoCloseable {
        private final BotTravelManager.RouteLookup previous = BotTravelManager.routeLookup;

        RouteStub(BotTravelManager.RouteLookup stub) {
            BotTravelManager.routeLookup = stub;
        }

        @Override
        public void close() {
            BotTravelManager.routeLookup = previous;
        }
    }

    /**
     * Swaps the consumable-hop seams (the default scroll-target lookup triggers a WZ scan;
     * scroll use / cab rides touch inventory, meso and the map factory); restore via close().
     * Defaults: no scroll shortcut, no scrolls in the bag, cab NPC missing, ride succeeds.
     */
    private static final class ConsumableSeams implements AutoCloseable {
        private final BotTravelManager.ScrollTargetLookup previousScrollTarget = BotTravelManager.scrollTargetLookup;
        private final java.util.function.ToIntFunction<Character> previousScrollCount = BotTravelManager.returnScrollCount;
        private final BotTravelManager.ReturnScrollUse previousScrollUse = BotTravelManager.returnScrollUse;
        private final BotTravelManager.TaxiNpcLocator previousNpcLocator = BotTravelManager.taxiNpcLocator;
        private final BotTravelManager.TaxiRide previousTaxiRide = BotTravelManager.taxiRide;
        final List<Integer> scrollUses = new ArrayList<>();
        final List<BotWorldGraph.TaxiEdge> rides = new ArrayList<>();

        ConsumableSeams() {
            BotTravelManager.scrollTargetLookup = mapId -> -1;
            BotTravelManager.returnScrollCount = bot -> 0;
            BotTravelManager.returnScrollUse = bot -> {
                scrollUses.add(1);
                return true;
            };
            BotTravelManager.taxiNpcLocator = (map, npcId) -> null;
            BotTravelManager.taxiRide = (bot, edge) -> {
                rides.add(edge);
                return true;
            };
        }

        @Override
        public void close() {
            BotTravelManager.scrollTargetLookup = previousScrollTarget;
            BotTravelManager.returnScrollCount = previousScrollCount;
            BotTravelManager.returnScrollUse = previousScrollUse;
            BotTravelManager.taxiNpcLocator = previousNpcLocator;
            BotTravelManager.taxiRide = previousTaxiRide;
        }
    }

    @Test
    void shouldPickNearestOpenUnscriptedPortalToTargetMap() {
        Portal near = portal(1, HENESYS, Portal.MAP_PORTAL, null, Portal.OPEN, new Point(100, 0));
        Portal far = portal(2, HENESYS, Portal.MAP_PORTAL, null, Portal.OPEN, new Point(900, 0));
        Portal scripted = portal(3, HENESYS, Portal.MAP_PORTAL, "enter_secret", Portal.OPEN, new Point(10, 0));
        Portal closed = portal(4, HENESYS, Portal.MAP_PORTAL, null, Portal.CLOSED, new Point(20, 0));
        Portal otherMap = portal(5, HUNTING_GROUND, Portal.MAP_PORTAL, null, Portal.OPEN, new Point(30, 0));
        Portal door = portal(6, HENESYS, Portal.DOOR_PORTAL, null, Portal.OPEN, new Point(40, 0));

        Portal picked = BotTravelManager.findAdjacentPortal(
                List.of(far, scripted, closed, otherMap, door, near), HENESYS, new Point(0, 0));

        assertSame(near, picked);
        assertNull(BotTravelManager.findAdjacentPortal(List.of(scripted, closed, otherMap, door),
                HENESYS, new Point(0, 0)));
    }

    @Test
    void shouldWalkTowardPortalThenEnterWhenInRange() {
        Portal portal = portal(1, HENESYS, Portal.MAP_PORTAL, null, Portal.OPEN, new Point(300, 0));
        Fixture f = fixture(HUNTING_GROUND, HENESYS, new Point(0, 0), List.of(portal));

        try (MovementRecorder movement = new MovementRecorder()) {
            assertTrue(BotTravelManager.tickFollowTravel(f.entry(), f.bot(), f.anchor(), true));
            assertEquals(HENESYS, f.entry().followTravelTargetMapId);
            assertEquals(List.of(new Point(300, 0)), movement.steps);
            assertNotNull(f.entry().moveTarget);
            assertTrue(f.entry().moveTargetPrecise);

            // Bot arrives within enter tolerance: portal is entered, movement stops.
            when(f.bot().getPosition()).thenReturn(new Point(295, 0));
            assertTrue(BotTravelManager.tickFollowTravel(f.entry(), f.bot(), f.anchor(), true));
            verify(portal).enterPortal(any());
            assertTrue(f.entry().followTravelEnteredAtMs > 0);
            assertNull(f.entry().moveTarget);
            assertEquals(1, movement.steps.size());

            // While the warp is in flight the tick stays consumed with no new movement.
            assertTrue(BotTravelManager.tickFollowTravel(f.entry(), f.bot(), f.anchor(), true));
            assertEquals(1, movement.steps.size());
        }
    }

    @Test
    void shouldFallBackToWarpWhenNoRouteExists() {
        Portal unrelated = portal(1, HUNTING_GROUND, Portal.MAP_PORTAL, null, Portal.OPEN, new Point(50, 0));
        Fixture f = fixture(HUNTING_GROUND, HENESYS, new Point(0, 0), List.of(unrelated));

        try (MovementRecorder movement = new MovementRecorder();
             RouteStub route = new RouteStub((from, to, maxHops, options) -> null)) {
            assertFalse(BotTravelManager.tickFollowTravel(f.entry(), f.bot(), f.anchor(), true));
            assertEquals(-1, f.entry().followTravelTargetMapId);
            assertTrue(movement.steps.isEmpty());
        }
    }

    @Test
    void shouldWalkFirstHopOfMultiHopRoute() {
        int startMap = 999999;
        // No direct portal to Henesys — only one into the hunting ground, which the route says to take.
        Portal toHunting = portal(1, HUNTING_GROUND, Portal.MAP_PORTAL, null, Portal.OPEN, new Point(400, 0));
        Fixture f = fixture(startMap, HENESYS, new Point(0, 0), List.of(toHunting));

        try (MovementRecorder movement = new MovementRecorder();
             RouteStub route = new RouteStub((from, to, maxHops, options) ->
                     from == startMap && to == HENESYS ? List.of(HUNTING_GROUND, HENESYS) : null)) {
            assertTrue(BotTravelManager.tickFollowTravel(f.entry(), f.bot(), f.anchor(), true));
            assertEquals(HENESYS, f.entry().followTravelTargetMapId);
            assertEquals(HUNTING_GROUND, f.entry().followTravelNextHopMapId);
            assertEquals(List.of(new Point(400, 0)), movement.steps);

            // Hop lands: bot is now in the hunting ground (map-change tick already ran) and a
            // direct portal to Henesys exists — travel re-plans and walks the final hop.
            Portal toHenesys = portal(7, HENESYS, Portal.MAP_PORTAL, null, Portal.OPEN, new Point(-200, 0));
            MapleMap huntingGround = mock(MapleMap.class);
            when(huntingGround.getPortals()).thenReturn(List.of(toHenesys));
            when(huntingGround.getPortal(7)).thenReturn(toHenesys);
            when(f.bot().getMap()).thenReturn(huntingGround);
            when(f.bot().getMapId()).thenReturn(HUNTING_GROUND);
            f.entry().lastMapId = HUNTING_GROUND;

            assertTrue(BotTravelManager.tickFollowTravel(f.entry(), f.bot(), f.anchor(), true));
            assertEquals(HENESYS, f.entry().followTravelNextHopMapId);
            assertEquals(new Point(-200, 0), movement.steps.get(movement.steps.size() - 1));
        }
    }

    @Test
    void shouldFallBackWhenRouteEdgeHasNoLiveUsablePortal() {
        // The graph claims a hop into the hunting ground, but the only live portal there is scripted.
        Portal scripted = portal(1, HUNTING_GROUND, Portal.MAP_PORTAL, "enter_gate", Portal.OPEN, new Point(50, 0));
        Fixture f = fixture(999999, HENESYS, new Point(0, 0), List.of(scripted));

        try (MovementRecorder movement = new MovementRecorder();
             ConsumableSeams seams = new ConsumableSeams();
             RouteStub route = new RouteStub((from, to, maxHops, options) -> List.of(HUNTING_GROUND, HENESYS))) {
            assertFalse(BotTravelManager.tickFollowTravel(f.entry(), f.bot(), f.anchor(), true));
            assertEquals(-1, f.entry().followTravelTargetMapId);
            assertTrue(movement.steps.isEmpty());
        }
    }

    @Test
    void shouldUseReturnScrollWhenRouteTakesTheScrollShortcut() {
        int deepMap = 105050100;
        Fixture f = fixture(deepMap, HENESYS, new Point(0, 0), List.of());

        try (MovementRecorder movement = new MovementRecorder();
             ConsumableSeams seams = new ConsumableSeams();
             RouteStub route = new RouteStub((from, to, maxHops, options) ->
                     options.withReturnScroll() ? List.of(HENESYS) : null)) {
            BotTravelManager.scrollTargetLookup = mapId -> mapId == deepMap ? HENESYS : -1;
            BotTravelManager.returnScrollCount = bot -> 1;

            assertTrue(BotTravelManager.tickFollowTravel(f.entry(), f.bot(), f.anchor(), true));
            assertEquals(1, seams.scrollUses.size());
            assertTrue(f.entry().followTravelEnteredAtMs > 0); // warp in flight, land grace applies
            assertTrue(movement.steps.isEmpty()); // no walking — the scroll fires on the spot
        }
    }

    @Test
    void shouldNotPlanScrollHopsWithoutAScrollInTheBag() {
        int deepMap = 105050100;
        Fixture f = fixture(deepMap, HENESYS, new Point(0, 0), List.of());

        try (MovementRecorder movement = new MovementRecorder();
             ConsumableSeams seams = new ConsumableSeams();
             RouteStub route = new RouteStub((from, to, maxHops, options) ->
                     options.withReturnScroll() ? List.of(HENESYS) : null)) {
            BotTravelManager.scrollTargetLookup = mapId -> mapId == deepMap ? HENESYS : -1;

            // returnScrollCount stays 0: planning never sees the scroll edge → warp fallback.
            assertFalse(BotTravelManager.tickFollowTravel(f.entry(), f.bot(), f.anchor(), true));
            assertTrue(seams.scrollUses.isEmpty());
        }
    }

    @Test
    void shouldWalkToCabNpcThenPayAndRide() {
        int lith = 104000000;
        Fixture f = fixture(HENESYS, lith, new Point(0, 0), List.of());
        Character bot = f.bot();
        when(bot.getMeso()).thenReturn(5000);

        try (MovementRecorder movement = new MovementRecorder();
             ConsumableSeams seams = new ConsumableSeams();
             RouteStub route = new RouteStub((from, to, maxHops, options) -> List.of(lith))) {
            BotTravelManager.taxiNpcLocator = (map, npcId) -> npcId == 1012000 ? new Point(800, 0) : null;

            // Too far from the cab: walk toward it.
            assertTrue(BotTravelManager.tickFollowTravel(f.entry(), bot, f.anchor(), true));
            assertEquals(1012000, f.entry().followTravelTaxiNpcId);
            assertEquals(List.of(new Point(800, 0)), movement.steps);
            assertTrue(seams.rides.isEmpty());

            // Within 500px: pay the fare and ride.
            when(bot.getPosition()).thenReturn(new Point(350, 0));
            assertTrue(BotTravelManager.tickFollowTravel(f.entry(), bot, f.anchor(), true));
            assertEquals(1, seams.rides.size());
            assertEquals(lith, seams.rides.get(0).toMapId());
            assertEquals(1000, seams.rides.get(0).fare());
            assertTrue(f.entry().followTravelEnteredAtMs > 0);
        }
    }

    @Test
    void shouldStartFerryBoardingOnlyWhenFerriesAreAllowed() {
        int elliniaStation = 101000300;
        int orbisStation = 200000100;
        Fixture f = fixture(elliniaStation, orbisStation, new Point(0, 0), List.of());
        when(f.bot().getMeso()).thenReturn(5000);

        try (MovementRecorder movement = new MovementRecorder();
             ConsumableSeams seams = new ConsumableSeams();
             RouteStub route = new RouteStub((from, to, maxHops, options) ->
                     options.withFerry() ? List.of(orbisStation) : null)) {
            BotTravelManager.taxiNpcLocator = (map, npcId) -> npcId == 1032007 ? new Point(800, 0) : null;

            // Follow travel never plans a 15-minute boat ride — warp fallback instead.
            assertFalse(BotTravelManager.tickFollowTravel(f.entry(), f.bot(), f.anchor(), true));

            // Autopilot travel boards: first leg walks toward the ticket seller.
            assertTrue(BotTravelManager.tickTravel(f.entry(), f.bot(), orbisStation, 8, true, true));
            assertTrue(f.entry().followTravelFerry);
            assertEquals(List.of(new Point(800, 0)), movement.steps);
        }
    }

    @Test
    void shouldKeepRidingTheFerryEvenInsideGiveUpWindow() {
        int deck = 200090010; // boat to Orbis
        Fixture f = fixture(deck, HENESYS, new Point(0, 0), List.of());
        f.entry().followTravelGiveUpUntilMs = System.currentTimeMillis() + 60_000;
        BotFerryManager.ThreatCheck previousThreat = BotFerryManager.threatCheck;
        BotFerryManager.threatCheck = bot -> false;

        try (MovementRecorder movement = new MovementRecorder()) {
            // Mid-ocean there is nothing to give up to: the tick stays consumed, no warp.
            assertTrue(BotTravelManager.tickFollowTravel(f.entry(), f.bot(), f.anchor(), true));
            assertTrue(movement.steps.isEmpty());
        } finally {
            BotFerryManager.threatCheck = previousThreat;
        }
    }

    @Test
    void shouldFallBackWhenMesoCannotCoverTheCabFare() {
        int lith = 104000000;
        Fixture f = fixture(HENESYS, lith, new Point(0, 0), List.of());
        when(f.bot().getMeso()).thenReturn(500); // Henesys→Lith cab costs 1000

        try (MovementRecorder movement = new MovementRecorder();
             ConsumableSeams seams = new ConsumableSeams();
             RouteStub route = new RouteStub((from, to, maxHops, options) -> List.of(lith))) {
            BotTravelManager.taxiNpcLocator = (map, npcId) -> new Point(800, 0);

            assertFalse(BotTravelManager.tickFollowTravel(f.entry(), f.bot(), f.anchor(), true));
            assertTrue(seams.rides.isEmpty());
            assertTrue(movement.steps.isEmpty());
        }
    }

    @Test
    void shouldReplanWhenAnchorMovesToAnotherMap() {
        Portal toHenesys = portal(1, HENESYS, Portal.MAP_PORTAL, null, Portal.OPEN, new Point(300, 0));
        Portal toHunting = portal(2, HUNTING_GROUND, Portal.MAP_PORTAL, null, Portal.OPEN, new Point(-300, 0));
        Fixture f = fixture(999999, HENESYS, new Point(0, 0), List.of(toHenesys, toHunting));

        try (MovementRecorder movement = new MovementRecorder()) {
            assertTrue(BotTravelManager.tickFollowTravel(f.entry(), f.bot(), f.anchor(), true));
            assertEquals(HENESYS, f.entry().followTravelTargetMapId);

            when(f.anchor().getMapId()).thenReturn(HUNTING_GROUND);
            assertTrue(BotTravelManager.tickFollowTravel(f.entry(), f.bot(), f.anchor(), true));
            assertEquals(HUNTING_GROUND, f.entry().followTravelTargetMapId);
            assertEquals(new Point(-300, 0), movement.steps.get(movement.steps.size() - 1));
        }
    }

    @Test
    void shouldGiveUpAfterDeadlineAndWarpDirectlyForAWhile() {
        Portal portal = portal(1, HENESYS, Portal.MAP_PORTAL, null, Portal.OPEN, new Point(300, 0));
        Fixture f = fixture(HUNTING_GROUND, HENESYS, new Point(0, 0), List.of(portal));

        try (MovementRecorder movement = new MovementRecorder()) {
            assertTrue(BotTravelManager.tickFollowTravel(f.entry(), f.bot(), f.anchor(), true));
            f.entry().followTravelDeadlineMs = System.currentTimeMillis() - 1;

            assertFalse(BotTravelManager.tickFollowTravel(f.entry(), f.bot(), f.anchor(), true));
            assertEquals(-1, f.entry().followTravelTargetMapId);
            assertNull(f.entry().moveTarget);
            assertTrue(f.entry().followTravelGiveUpUntilMs > System.currentTimeMillis());

            // Inside the give-up window every attempt falls straight back to the warp.
            assertFalse(BotTravelManager.tickFollowTravel(f.entry(), f.bot(), f.anchor(), true));
            verify(portal, never()).enterPortal(any());
            assertEquals(1, movement.steps.size());
        }
    }

    @Test
    void shouldNotClobberPlayerIssuedMoveTargetOnClear() {
        BotEntry entry = new BotEntry(mock(Character.class), null, null);
        Point playerTarget = new Point(123, 45);
        entry.moveTarget = playerTarget;
        entry.moveTargetPrecise = true;
        entry.followTravelTargetMapId = HENESYS; // stale travel state

        BotTravelManager.clear(entry);

        assertSame(playerTarget, entry.moveTarget);
        assertTrue(entry.moveTargetPrecise);
        assertEquals(-1, entry.followTravelTargetMapId);
    }
}
