package server.bots;

import client.Character;
import client.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BotTravelManagerTest {

    private static final int HENESYS = 100000000;
    private static final int HUNTING_GROUND = 100040000;

    // Fire NPC/portal actions on the in-range tick instead of waiting out the humanlike dwell pause.
    @BeforeEach void instantDwell() { BotManager.dwellInstant = true; }
    @AfterEach void resetDwell() { BotManager.dwellInstant = false; }

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
        when(bot.getJob()).thenReturn(Job.BEGINNER);
        // Real bots always carry a client; the partition-route loader resolves the channel MapFactory
        // through it. The stub router never invokes the loader, but the guard needs a non-null chain.
        client.Client botClient = mock(client.Client.class);
        net.server.channel.Channel botChannel = mock(net.server.channel.Channel.class);
        when(bot.getClient()).thenReturn(botClient);
        when(botClient.getChannelServer()).thenReturn(botChannel);
        when(botChannel.getMapFactory()).thenReturn(mock(server.maps.MapManager.class));
        when(anchor.getMapId()).thenReturn(anchorMapId);
        when(map.getId()).thenReturn(botMapId);
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
    void shouldPickNearestOpenTravelPortalToTargetMap() {
        Portal near = portal(1, HENESYS, Portal.MAP_PORTAL, null, Portal.OPEN, new Point(100, 0));
        Portal far = portal(2, HENESYS, Portal.MAP_PORTAL, null, Portal.OPEN, new Point(900, 0));
        Portal scripted = portal(3, HENESYS, Portal.MAP_PORTAL, "enter_secret", Portal.OPEN, new Point(10, 0));
        Portal closed = portal(4, HENESYS, Portal.MAP_PORTAL, null, Portal.CLOSED, new Point(20, 0));
        Portal otherMap = portal(5, HUNTING_GROUND, Portal.MAP_PORTAL, null, Portal.OPEN, new Point(30, 0));
        Portal door = portal(6, HENESYS, Portal.DOOR_PORTAL, null, Portal.OPEN, new Point(40, 0));
        Portal scriptOnly = portal(7, 999999999, Portal.MAP_PORTAL, "enter_secret", Portal.OPEN, new Point(5, 0));

        Portal picked = BotTravelManager.findAdjacentPortal(
                List.of(far, scripted, closed, otherMap, door, near), HENESYS, new Point(0, 0));

        assertSame(scripted, picked);
        assertNull(BotTravelManager.findAdjacentPortal(List.of(scriptOnly, closed, otherMap, door),
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
    void collisionPortalRebasesAirbornePhysicsOnIntraMapWarp() {
        Point trap = new Point(306, -216);
        Point spawn = new Point(-649, 34);
        Portal portal = portal(38, HUNTING_GROUND, 3, null, Portal.OPEN, trap);
        Fixture f = fixture(HUNTING_GROUND, HENESYS, trap, List.of(portal));
        AtomicReference<Point> position = new AtomicReference<>(trap);
        when(f.bot().getPosition()).thenAnswer(ignored -> position.get());
        doAnswer(invocation -> {
            position.set(invocation.getArgument(0));
            return null;
        }).when(f.bot()).setPosition(any(Point.class));
        doAnswer(ignored -> {
            position.set(spawn);
            return null;
        }).when(portal).enterPortal(any());

        f.entry().inAir = true;
        f.entry().physX = 337;
        f.entry().physY = -240;
        f.entry().velY = 33.5f;
        f.entry().airVelX = -7;
        f.entry().fixedAirArc = true;

        assertTrue(BotTravelManager.tickCollisionPortal(f.entry(), f.bot()));
        assertEquals(spawn, position.get(), "collision portal should warp to its target spawn");

        if (f.entry().inAir) {
            BotPhysicsEngine.stepAirborne(f.entry(), f.bot());
        }

        assertEquals(spawn, position.get(),
                "the next physics tick must continue from the warp, not the pre-warp airborne trajectory");
    }

    @Test
    void scriptedCollisionPortalFiresWhileHoveringOverWarpStrip() {
        // Leafre<->Temple flight map 200090510: pt=9 "undodraco" portals tile the floor at y=145 and
        // their script does the warp to Leafre, so tm is the no-destination sentinel (999999999) —
        // the sentinel must not filter pt=9 the way it filters pt=3. Bot position is the live stuck
        // hover's cycle bottom (-846, 101), 30px/44px off the portal — inside the client's default
        // ±50 trigger box.
        Portal strip = portal(12, 999999999, 9, "undodraco", Portal.OPEN, new Point(-876, 145));
        Fixture f = fixture(200090510, HENESYS, new Point(-846, 101), List.of(strip));

        assertTrue(BotTravelManager.tickCollisionPortal(f.entry(), f.bot()));
        verify(strip).enterPortal(any());
    }

    @Test
    void collisionTriggerBoxMatchesClientDefaultHalfRanges() {
        // Client FindPortal_Collision box = pos ± hRange/2 x ± vRange/2, WZ defaults 100 each → ±50.
        Portal strip = portal(12, 999999999, 9, "undodraco", Portal.OPEN, new Point(0, 145));
        Fixture f = fixture(200090510, HENESYS, new Point(0, 94), List.of(strip));

        assertFalse(BotTravelManager.tickCollisionPortal(f.entry(), f.bot()),
                "51px above the portal is outside the client trigger box");
        when(f.bot().getPosition()).thenReturn(new Point(0, 95));
        assertTrue(BotTravelManager.tickCollisionPortal(f.entry(), f.bot()),
                "50px above the portal is inside the client trigger box");
        verify(strip).enterPortal(any());
    }

    @Test
    void scriptlessTypeNineAndScriptedPitPortalsStayInert() {
        // A pt=9 without a script has nothing to fire; a scripted pt=3 may gate/dialog and keeps
        // its pre-existing exclusion.
        Portal bareNine = portal(1, HENESYS, 9, null, Portal.OPEN, new Point(0, 0));
        Portal scriptedPit = portal(2, HENESYS, 3, "gate", Portal.OPEN, new Point(0, 0));
        Fixture f = fixture(HUNTING_GROUND, HENESYS, new Point(0, 0), List.of(bareNine, scriptedPit));

        assertFalse(BotTravelManager.tickCollisionPortal(f.entry(), f.bot()));
        verify(bareNine, never()).enterPortal(any());
        verify(scriptedPit, never()).enterPortal(any());
    }

    @Test
    void lod1TimedPortalHopOutlivesPhysicalWalkDeadline() {
        Portal portal = portal(1, HENESYS, Portal.MAP_PORTAL, null, Portal.OPEN, new Point(300, 0));
        Fixture f = fixture(HUNTING_GROUND, HENESYS, new Point(0, 0), List.of(portal));
        f.entry().lod = BotEntry.Lod.LOD1;

        assertTrue(BotTravelManager.tickFollowTravel(f.entry(), f.bot(), f.anchor(), true));
        assertTrue(f.entry().lod1TravelDwellUntilMs > 0L);

        // The modeled portal dwell is longer than some physical approach budgets. It must finish the
        // abstract hop instead of giving up and wandering through a different portal.
        f.entry().followTravelDeadlineMs = System.currentTimeMillis() - 1L;
        assertTrue(BotTravelManager.tickFollowTravel(f.entry(), f.bot(), f.anchor(), true));
        assertEquals(HENESYS, f.entry().followTravelTargetMapId);
        assertNull(f.entry().followTravelGiveUpReason);
        verify(portal, never()).enterPortal(any());
    }

    private static final class PartitionRouteStub implements AutoCloseable {
        private final BotTravelManager.PartitionRouteLookup previous = BotTravelManager.partitionRouteLookup;

        PartitionRouteStub(BotTravelManager.PartitionRouteLookup stub) {
            BotTravelManager.partitionRouteLookup = stub;
        }

        @Override
        public void close() {
            BotTravelManager.partitionRouteLookup = previous;
        }
    }

    @Test
    void shouldTryPositiveTargetScriptedPortalAndFailFastWhenScriptDoesNotMove() {
        Portal portal = portal(1, HENESYS, Portal.MAP_PORTAL, "kpq0", Portal.OPEN, new Point(0, 0));
        Fixture f = fixture(HUNTING_GROUND, HENESYS, new Point(0, 0), List.of(portal));

        assertFalse(BotTravelManager.tickFollowTravel(f.entry(), f.bot(), f.anchor(), true));

        verify(portal).enterPortal(any());
        assertEquals(0L, f.entry().followTravelEnteredAtMs);
        assertEquals("script-no-land", f.entry().followTravelGiveUpReason);
    }

    @Test
    void shouldFallBackToWarpWhenNoRouteExists() {
        Portal unrelated = portal(1, HUNTING_GROUND, Portal.MAP_PORTAL, null, Portal.OPEN, new Point(50, 0));
        Fixture f = fixture(HUNTING_GROUND, HENESYS, new Point(0, 0), List.of(unrelated));

        try (MovementRecorder movement = new MovementRecorder();
             RouteStub route = new RouteStub((from, to, maxHops, options, blocked) -> null)) {
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
             RouteStub route = new RouteStub((from, to, maxHops, options, blocked) ->
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
    void shouldUsePartitionRouteEvenWhenCurrentMapCanReachAllExits() {
        int startMap = 1;
        int badSplitArrivalMap = 2;
        int goodDetourMap = 3;
        int targetMap = 4;
        Portal badMapLevelHop = portal(1, badSplitArrivalMap, Portal.MAP_PORTAL, null, Portal.OPEN, new Point(100, 0));
        Portal partitionHop = portal(2, goodDetourMap, Portal.MAP_PORTAL, null, Portal.OPEN, new Point(300, 0));
        Fixture f = fixture(startMap, targetMap, new Point(0, 0), List.of(badMapLevelHop, partitionHop));

        BotNavigationGraph graph = mock(BotNavigationGraph.class);
        when(graph.findRegionId(f.map(), new Point(0, 0))).thenReturn(10);
        when(graph.findRegionId(f.map(), new Point(100, 0))).thenReturn(11);
        when(graph.findRegionId(f.map(), new Point(300, 0))).thenReturn(12);
        when(graph.canReach(10, 11, 0)).thenReturn(true);
        when(graph.canReach(10, 12, 0)).thenReturn(true);

        try (var graphs = mockStatic(BotNavigationGraphProvider.class);
             MovementRecorder movement = new MovementRecorder();
             RouteStub route = new RouteStub((from, to, maxHops, options, blocked) ->
                     List.of(badSplitArrivalMap, targetMap));
             PartitionRouteStub partition = new PartitionRouteStub((provider, from, exits, to, maxHops, blocked) ->
                     List.of(new BotWorldPartitionRouter.Node(goodDetourMap, "arrive_good"),
                             new BotWorldPartitionRouter.Node(targetMap, "target")))) {
            graphs.when(() -> BotNavigationGraphProvider.peekGraph(any(MapleMap.class), any(BotMovementProfile.class)))
                    .thenReturn(graph);
            graphs.when(() -> BotNavigationGraphProvider.peekBestGraph(any(MapleMap.class), any(BotMovementProfile.class)))
                    .thenReturn(graph);

            assertTrue(BotTravelManager.tickTravel(f.entry(), f.bot(), targetMap, 8, true, false));

            assertEquals(targetMap, f.entry().followTravelTargetMapId);
            assertEquals(goodDetourMap, f.entry().followTravelNextHopMapId);
            assertEquals(List.of(new Point(300, 0)), movement.steps);
        }
    }

    @Test
    void shouldUseActiveProfileGraphForPartitionTravel() {
        int deadMansGorge = 610010004;
        int unreachableTopLeft = 610010005;
        int reachableDetour = 610010103;
        int targetMap = 610020002;
        Point bottom = new Point(-681, 208);
        Portal badMapLevelHop = portal(5, unreachableTopLeft, Portal.MAP_PORTAL, null, Portal.OPEN,
                new Point(-989, -97));
        Portal goodPartitionHop = portal(3, reachableDetour, Portal.MAP_PORTAL, null, Portal.OPEN,
                new Point(189, 196));
        when(badMapLevelHop.getName()).thenReturn("U5_1");
        when(goodPartitionHop.getName()).thenReturn("U5_6");
        Fixture f = fixture(deadMansGorge, targetMap, bottom, List.of(badMapLevelHop, goodPartitionHop));
        f.entry().movementProfile = new BotMovementProfile(180, 120, false);

        BotNavigationGraph graph = mock(BotNavigationGraph.class);
        when(graph.findRegionId(f.map(), bottom)).thenReturn(6);
        when(graph.findRegionId(f.map(), badMapLevelHop.getPosition())).thenReturn(4);
        when(graph.findRegionId(f.map(), goodPartitionHop.getPosition())).thenReturn(6);
        when(graph.canReach(6, 4, 0)).thenReturn(false);
        when(graph.canReach(6, 6, 0)).thenReturn(true);

        try (var graphs = mockStatic(BotNavigationGraphProvider.class);
             MovementRecorder movement = new MovementRecorder();
             RouteStub route = new RouteStub((from, to, maxHops, options, blocked) ->
                     List.of(unreachableTopLeft, 610020000, 610020001, targetMap));
             PartitionRouteStub partition = new PartitionRouteStub((provider, from, exits, to, maxHops, blocked) -> {
                 assertEquals(List.of("U5_6"), exits.stream().map(BotMapPartition.PortalRef::name).toList());
                 return List.of(new BotWorldPartitionRouter.Node(reachableDetour, "A4_3"),
                         new BotWorldPartitionRouter.Node(610010104, "A5_8"),
                         new BotWorldPartitionRouter.Node(targetMap, "CM3_1"));
             })) {
            graphs.when(() -> BotNavigationGraphProvider.peekBestGraph(any(MapleMap.class), any(BotMovementProfile.class)))
                    .thenReturn(graph);
            graphs.when(() -> BotNavigationGraphProvider.peekGraph(any(MapleMap.class), any(BotMovementProfile.class)))
                    .thenReturn(graph);

            assertTrue(BotTravelManager.tickTravel(f.entry(), f.bot(), targetMap, 30, true, false));

            assertEquals(targetMap, f.entry().followTravelTargetMapId);
            assertEquals(reachableDetour, f.entry().followTravelNextHopMapId);
            assertEquals(goodPartitionHop.getId(), f.entry().followTravelPortalId);
            assertEquals(List.of(new Point(189, 196)), movement.steps);
        }
    }

    @Test
    void shouldNotUseGroundReachabilityToRejectSwimMapPortal() {
        int forkedRoadWestSea = 230010400;
        int westSea = 230010300;
        int instructorMap = 106010000;
        Point bottomArrival = new Point(229, 590);
        Portal westPortal = portal(7, westSea, Portal.MAP_PORTAL, null, Portal.OPEN, new Point(-2085, 39));
        Fixture f = fixture(forkedRoadWestSea, instructorMap, bottomArrival, List.of(westPortal));
        when(f.map().isSwim()).thenReturn(true);

        BotNavigationGraph groundGraph = mock(BotNavigationGraph.class);
        when(groundGraph.findRegionId(f.map(), bottomArrival)).thenReturn(2);
        when(groundGraph.findRegionId(f.map(), westPortal.getPosition())).thenReturn(1);
        when(groundGraph.canReach(2, 1, 0)).thenReturn(false);

        try (var graphs = mockStatic(BotNavigationGraphProvider.class);
             MovementRecorder movement = new MovementRecorder();
             RouteStub route = new RouteStub((from, to, maxHops, options, blocked) ->
                     List.of(westSea, instructorMap))) {
            graphs.when(() -> BotNavigationGraphProvider.peekBestGraph(any(MapleMap.class), any(BotMovementProfile.class)))
                    .thenReturn(groundGraph);
            graphs.when(() -> BotNavigationGraphProvider.peekGraph(any(MapleMap.class), any(BotMovementProfile.class)))
                    .thenReturn(groundGraph);

            assertTrue(BotTravelManager.tickTravel(f.entry(), f.bot(), instructorMap, 30, true, true));

            assertEquals(instructorMap, f.entry().followTravelTargetMapId);
            assertEquals(westSea, f.entry().followTravelNextHopMapId);
            assertEquals(westPortal.getId(), f.entry().followTravelPortalId);
            assertEquals(List.of(westPortal.getPosition()), movement.steps);
        }
    }

    @Test
    void shouldFallBackWhenRouteEdgeHasNoLiveUsablePortal() {
        // The graph claims a hop into the hunting ground, but the only live portal there is closed.
        Portal closed = portal(1, HUNTING_GROUND, Portal.MAP_PORTAL, null, Portal.CLOSED, new Point(50, 0));
        Fixture f = fixture(999999, HENESYS, new Point(0, 0), List.of(closed));

        try (MovementRecorder movement = new MovementRecorder();
             ConsumableSeams seams = new ConsumableSeams();
             RouteStub route = new RouteStub((from, to, maxHops, options, blocked) -> List.of(HUNTING_GROUND, HENESYS))) {
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
             RouteStub route = new RouteStub((from, to, maxHops, options, blocked) ->
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
             RouteStub route = new RouteStub((from, to, maxHops, options, blocked) ->
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
             RouteStub route = new RouteStub((from, to, maxHops, options, blocked) -> List.of(lith))) {
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
    void shouldHailCabFromHereWhenApproachBudgetLapses() {
        int lith = 104000000;
        Fixture f = fixture(HENESYS, lith, new Point(0, 0), List.of());
        Character bot = f.bot();
        when(bot.getMeso()).thenReturn(5000);

        try (MovementRecorder movement = new MovementRecorder();
             ConsumableSeams seams = new ConsumableSeams();
             RouteStub route = new RouteStub((from, to, maxHops, options, blocked) -> List.of(lith))) {
            BotTravelManager.taxiNpcLocator = (map, npcId) -> npcId == 1012000 ? new Point(800, 0) : null;

            // First tick seeds the taxi hop; the bot is 800px from the cab, so it just walks (no ride).
            assertTrue(BotTravelManager.tickFollowTravel(f.entry(), bot, f.anchor(), true));
            assertEquals(1012000, f.entry().followTravelTaxiNpcId);
            assertTrue(seams.rides.isEmpty());

            // Approach budget lapses while still far from the cab (a foothold the nav can't stand on):
            // hail it from here instead of failing the errand. A town cab is clickable map-wide.
            f.entry().followTravelDeadlineMs = System.currentTimeMillis() - 1;
            assertTrue(BotTravelManager.tickFollowTravel(f.entry(), bot, f.anchor(), true));
            assertEquals(1, seams.rides.size());
            assertEquals(lith, seams.rides.get(0).toMapId());
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
             RouteStub route = new RouteStub((from, to, maxHops, options, blocked) ->
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
        BotFerryManager.threatCheck = (bot, eventName) -> false;

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
             RouteStub route = new RouteStub((from, to, maxHops, options, blocked) -> List.of(lith))) {
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
    void pickRandomCrossMapPortalFiltersIneligibleAndReturnsRealTarget() {
        Portal good = portal(4, HENESYS, 0, "", true, new Point(40, 0));
        List<Portal> portals = List.of(
                portal(0, HENESYS, 0, "", false, new Point(0, 0)),                 // closed
                portal(1, HENESYS, Portal.DOOR_PORTAL, "", true, new Point(10, 0)),// door
                portal(2, 999999999, 0, "MD00", true, new Point(20, 0)),           // script-only sentinel
                portal(3, HUNTING_GROUND, 0, "", true, new Point(30, 0)),          // self-loop (current map)
                good);                                                              // the only eligible portal

        Portal picked = BotTravelManager.pickRandomCrossMapPortal(portals, HUNTING_GROUND, new java.util.Random(1));
        assertSame(good, picked);
    }

    @Test
    void pickRandomCrossMapPortalReturnsNullWhenNoneEligible() {
        List<Portal> portals = List.of(
                portal(0, HUNTING_GROUND, 0, "", true, new Point(0, 0)),    // self-loop (current map)
                portal(1, 999999999, 0, "MD00", true, new Point(10, 0)));   // script-only sentinel
        assertNull(BotTravelManager.pickRandomCrossMapPortal(portals, HUNTING_GROUND, new java.util.Random(7)));
    }

    @Test
    void pickRandomCrossMapPortalIsNotBiasedToFirst() {
        // Three eligible portals, distinct nearest distances; verify selection isn't pinned to
        // the first/nearest portal across many draws (the "no fixed first-portal bias" rule).
        List<Portal> portals = List.of(
                portal(0, HENESYS, 0, "", true, new Point(0, 0)),     // nearest to (0,0)
                portal(1, HENESYS, 0, "", true, new Point(500, 0)),
                portal(2, HENESYS, 0, "", true, new Point(900, 0)));
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        java.util.Random rng = new java.util.Random(42);
        for (int i = 0; i < 60; i++) {
            seen.add(BotTravelManager.pickRandomCrossMapPortal(portals, HUNTING_GROUND, rng).getId());
        }
        assertTrue(seen.size() >= 2, "expected the random pick to span multiple portals, saw " + seen);
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

    @Test
    void nextHopPortalPositionReturnsDirectPortalPos() {
        Portal toHenesys = portal(1, HENESYS, Portal.MAP_PORTAL, null, Portal.OPEN, new Point(300, 0));
        Fixture f = fixture(HUNTING_GROUND, HENESYS, new Point(0, 0), List.of(toHenesys));

        try (ConsumableSeams seams = new ConsumableSeams()) {
            Point pos = BotTravelManager.nextHopPortalPosition(f.entry(), f.bot(), HENESYS, 4);
            assertEquals(new Point(300, 0), pos);
        }
    }

    @Test
    void nextHopPortalPositionReturnsFirstHopPortalOfMultiHopRoute() {
        int startMap = 999999;
        Portal toHunting = portal(1, HUNTING_GROUND, Portal.MAP_PORTAL, null, Portal.OPEN, new Point(400, 0));
        Fixture f = fixture(startMap, HENESYS, new Point(0, 0), List.of(toHunting));

        try (ConsumableSeams seams = new ConsumableSeams();
             RouteStub route = new RouteStub((from, to, maxHops, options, blocked) ->
                     from == startMap && to == HENESYS ? List.of(HUNTING_GROUND, HENESYS) : null)) {
            Point pos = BotTravelManager.nextHopPortalPosition(f.entry(), f.bot(), HENESYS, 4);
            assertEquals(new Point(400, 0), pos); // stand at the hop portal, not the final destination
        }
    }

    @Test
    void nextHopPortalPositionReturnsNullWhenNextHopIsNotAWalkablePortal() {
        // Route's next hop has no live walkable portal (only a closed one) — nothing to stand at.
        Portal closed = portal(1, HUNTING_GROUND, Portal.MAP_PORTAL, null, Portal.CLOSED, new Point(50, 0));
        Fixture f = fixture(999999, HENESYS, new Point(0, 0), List.of(closed));

        try (ConsumableSeams seams = new ConsumableSeams();
             RouteStub route = new RouteStub((from, to, maxHops, options, blocked) -> List.of(HUNTING_GROUND, HENESYS))) {
            assertNull(BotTravelManager.nextHopPortalPosition(f.entry(), f.bot(), HENESYS, 4));
        }
    }

    @Test
    void nextHopPortalPositionReturnsNullWhenNoRoute() {
        Fixture f = fixture(999999, HENESYS, new Point(0, 0), List.of());

        try (ConsumableSeams seams = new ConsumableSeams();
             RouteStub route = new RouteStub((from, to, maxHops, options, blocked) -> null)) {
            assertNull(BotTravelManager.nextHopPortalPosition(f.entry(), f.bot(), HENESYS, 4));
        }
    }

    @Test
    void pickRandomCrossMapPortalExcludesNoDestinationSpawnPortals() {
        // Spawn points / doors carry tm=999999999 (a positive sentinel, not a real map). Treating
        // them as exits is what made a one-exit town offer ~16 "portals" to wander between.
        Portal spawn = portal(0, 999999999, 0, "", true, new Point(0, 0));
        Portal real = portal(1, HENESYS, 0, "", true, new Point(50, 0));

        assertSame(real, BotTravelManager.pickRandomCrossMapPortal(
                List.of(spawn, real), HUNTING_GROUND, new java.util.Random(3)));
        assertNull(BotTravelManager.pickRandomCrossMapPortal(
                List.of(spawn), HUNTING_GROUND, new java.util.Random(3)));
    }

    @Test
    void resetForModeChangeDropsGiveUpCooldownSoCommandsUnstick() {
        BotEntry entry = new BotEntry(mock(Character.class), null, null);
        entry.followTravelGiveUpUntilMs = System.currentTimeMillis() + 60_000;
        entry.followTravelGiveUpReason = "deadline";
        entry.followTravelTargetMapId = HENESYS;

        BotTravelManager.resetForModeChange(entry);

        assertEquals(0L, entry.followTravelGiveUpUntilMs);
        assertNull(entry.followTravelGiveUpReason);
        assertEquals(-1, entry.followTravelTargetMapId);
    }

    @Test
    void plainClearKeepsGiveUpCooldownForTheInternalRetryLoop() {
        // clear() runs every tick during the give-up window and must NOT drop the cooldown — only
        // the deliberate resetForModeChange() does. Guards the stuck-state fix from regressing into
        // "the give-up window never holds".
        BotEntry entry = new BotEntry(mock(Character.class), null, null);
        long until = System.currentTimeMillis() + 60_000;
        entry.followTravelGiveUpUntilMs = until;

        BotTravelManager.clear(entry);

        assertEquals(until, entry.followTravelGiveUpUntilMs);
    }

    @Test
    void progressTowardPortalTracksClosestDistanceAndRefreshesDeadline() {
        // Fix for the give-up macro-loop: a long multi-jump climb must not be aborted while the bot
        // is still closing on the portal. Each new closest distance refreshes the deadline; only a
        // bot that stops making progress times out.
        Portal portal = portal(1, HENESYS, Portal.MAP_PORTAL, null, Portal.OPEN, new Point(2000, 0));
        Fixture f = fixture(HUNTING_GROUND, HENESYS, new Point(0, 0), List.of(portal));

        try (MovementRecorder movement = new MovementRecorder()) {
            assertTrue(BotTravelManager.tickFollowTravel(f.entry(), f.bot(), f.anchor(), true));
            assertEquals(2000, f.entry().followTravelBestDist);

            when(f.bot().getPosition()).thenReturn(new Point(1500, 0));
            long beforeMs = System.currentTimeMillis();
            assertTrue(BotTravelManager.tickFollowTravel(f.entry(), f.bot(), f.anchor(), true));
            assertEquals(500, f.entry().followTravelBestDist);          // new closest distance recorded
            assertTrue(f.entry().followTravelDeadlineMs >= beforeMs);   // deadline refreshed from 'now'
        }
    }

    @Test
    void movementAlongDetourRefreshesPortalDeadlineEvenBeforeRawDistanceImproves() {
        // Some valid graph routes initially move away from the portal in raw Manhattan distance
        // before climbing back toward it. The hop watchdog must not time out those legal detours.
        Portal portal = portal(1, HENESYS, Portal.MAP_PORTAL, null, Portal.OPEN, new Point(1000, 0));
        Fixture f = fixture(HUNTING_GROUND, HENESYS, new Point(0, 0), List.of(portal));

        try (MovementRecorder movement = new MovementRecorder()) {
            assertTrue(BotTravelManager.tickFollowTravel(f.entry(), f.bot(), f.anchor(), true));
            assertEquals(1000, f.entry().followTravelBestDist);

            when(f.bot().getPosition()).thenReturn(new Point(0, 200)); // farther from portal, but real movement
            f.entry().followTravelDeadlineMs = System.currentTimeMillis() - 1;
            long beforeMs = System.currentTimeMillis();

            assertTrue(BotTravelManager.tickFollowTravel(f.entry(), f.bot(), f.anchor(), true));
            assertEquals(1000, f.entry().followTravelBestDist);
            assertTrue(f.entry().followTravelDeadlineMs >= beforeMs);
            assertEquals(2, movement.steps.size());
        }
    }

    @Test
    void stuckNearActsOnlyWhenStationaryAndWithinRange() {
        // SSOT "got close, act from where you stand" fallback shared by the taxi/ferry/instructor/shop
        // approaches: a cab atop Ellinia's tree the walk can't stand exactly on must still be hailed.
        BotTravelManager.ApproachStuck st = new BotTravelManager.ApproachStuck();
        Point npc = new Point(0, 0);
        // First sighting only arms the clock — never fires immediately.
        assertFalse(BotTravelManager.stuckNear(st, new Point(100, 0), npc, 0L, 500));
        // Held within the 2px move tolerance, but <1s elapsed.
        assertFalse(BotTravelManager.stuckNear(st, new Point(101, 0), npc, 500L, 500));
        // Stationary >=1s AND within fallback distance -> arrived-from-here.
        assertTrue(BotTravelManager.stuckNear(st, new Point(101, 0), npc, 1500L, 500));
        // A real move resets the clock.
        assertFalse(BotTravelManager.stuckNear(st, new Point(300, 0), npc, 1600L, 500));
        assertFalse(BotTravelManager.stuckNear(st, new Point(300, 0), npc, 2000L, 500)); // <1s since reset

        // Stationary but beyond the fallback radius never fires (it isn't "near" the NPC).
        BotTravelManager.ApproachStuck far = new BotTravelManager.ApproachStuck();
        assertFalse(BotTravelManager.stuckNear(far, new Point(900, 0), npc, 0L, 500));
        assertFalse(BotTravelManager.stuckNear(far, new Point(900, 0), npc, 9000L, 500));
    }
}
