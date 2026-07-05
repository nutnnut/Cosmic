package server.bots;

import client.Character;
import org.junit.jupiter.api.Test;
import server.maps.Foothold;
import server.maps.MapleMap;

import java.awt.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotDirectionalDropNavigationTest {
    @Test
    void shouldKeepDirectionalDropDirectionWhileBotIsStillOnRunway() {
        DropTestFixture fixture = createDirectionalDropFixture(910000040);
        // New O(1) runway semantics: startPoint is placed launchRunwayPx behind the ledge.
        // Once the bot has crossed the runway anchor in the launch direction, nav should
        // feed endPoint until physics performs the walk-off.
        Character bot = mockBot(new Point(fixture.edge.startPoint.x + 2, fixture.edge.startPoint.y), fixture.map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.physX = bot.getPosition().x;
        entry.physY = bot.getPosition().y;

        Point waypoint = BotNavigationManager.selectDropWaypoint(entry, fixture.graph, bot.getPosition(), fixture.edge);

        assertEquals(fixture.edge.endPoint, waypoint,
                "directional drops should keep the held walk direction while the bot is already on the runway");
    }

    @Test
    void shouldKeepDirectionalDropLandingTargetWhenNaturalWalkOffAlreadyMatchesEdge() {
        DropTestFixture fixture = createDirectionalDropFixture(910000041);
        Character bot = mockBot(new Point(fixture.edge.startPoint), fixture.map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.physX = bot.getPosition().x;
        entry.physY = bot.getPosition().y;

        Point waypoint = BotNavigationManager.selectDropWaypoint(entry, fixture.graph, bot.getPosition(), fixture.edge);

        assertEquals(fixture.edge.endPoint, waypoint,
                "once the walk-off already has enough runway, nav should keep feeding the landing-side direction");
    }

    @Test
    void shouldKeepDirectionalDropDirectionAfterCrossingNegativeRunwayAnchor() {
        DropTestFixture fixture = createDirectionalDropFixture(910000042, false);
        Character bot = mockBot(new Point(fixture.edge.startPoint.x - 5, fixture.edge.startPoint.y), fixture.map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.physX = bot.getPosition().x;
        entry.physY = bot.getPosition().y;

        Point waypoint = BotNavigationManager.selectDropWaypoint(entry, fixture.graph, bot.getPosition(), fixture.edge);

        assertEquals(fixture.edge.endPoint, waypoint,
                "once the bot has crossed the negative-direction drop anchor, nav should keep holding the drop direction");
    }

    @Test
    void shouldSteerBackToRunwayWhenBotStandsOnWrongFootholdPastTheAnchor() {
        // Live incident (pathlog-itunes-2026-07-02, NLC 600000000): a same-height ledge sits
        // across a gap from the drop runway. The bot's x is "past" the runway anchor in the
        // launch direction, but walking that way never crosses the runway's lip - the old
        // x-only shortcut fed endPoint and the bot parked at endPoint.x grounded, walking in
        // place against the committed edge forever. The live walk-off sim must reject this
        // stance and steer back to the authored runway start instead.
        MapleMap map = new MapleMap(910000043, 0, 0, 910000043, 1.0f);
        Foothold wrongLedge = new Foothold(new Point(-200, 100), new Point(40, 100), 1);
        Foothold runway = new Foothold(new Point(60, 100), new Point(200, 100), 2);
        Foothold lower = new Foothold(new Point(-300, 160), new Point(300, 160), 3);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(wrongLedge);
        footholds.insert(runway);
        footholds.insert(lower);
        map.setFootholds(footholds);

        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        // The runway foothold's LEFT walk-off: launch direction is negative, landing in the gap.
        BotNavigationGraph.Edge edge = graph.regions.stream()
                .flatMap(region -> graph.getOutgoing(region.id).stream())
                .filter(candidate -> candidate.type == BotNavigationGraph.EdgeType.DROP
                        && candidate.launchStepX < 0
                        && candidate.endPoint.y > candidate.startPoint.y
                        && candidate.startPoint.x > 40)
                .findFirst()
                .orElse(null);
        assertNotNull(edge, "fixture should produce the runway foothold's leftward walk-off drop edge");

        // Bot on the WRONG ledge: x satisfies the old "past the anchor" test (x <= startPoint.x
        // for a negative launch), but walking left from here dismounts off the wrong lip.
        Character bot = mockBot(new Point(0, 100), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.physX = bot.getPosition().x;
        entry.physY = bot.getPosition().y;

        Point waypoint = BotNavigationManager.selectDropWaypoint(entry, graph, bot.getPosition(), edge);

        assertEquals(edge.startPoint, waypoint,
                "a stance the walk-off sim rejects must steer back to the authored runway, not the landing");
    }

    @Test
    void shouldNotAuthorKnifeEdgeDirectionalDropWhoseLandingRegionFlipsWithLaunchPhase() {
        // FM 910000000 regression (pathlog-CabinOpened-2026-07-03): the walk-off landing is
        // knife-edge sensitive to the live launch state (fractional physX phase, carryMs,
        // arrival hspeed) — the authored baseline landed r5 but live phases fell past it to
        // r6, and the planner replanned through the same lying edge forever. A shelf whose
        // near edge sits inside the landing variance band must NOT get a walk-off drop edge.
        MapleMap map = new MapleMap(910000044, 0, 0, 910000044, 1.0f);
        Foothold upper = new Foothold(new Point(0, 100), new Point(100, 100), 1);
        Foothold knifeShelf = new Foothold(new Point(KNIFE_SHELF_EDGE_X, 160), new Point(280, 160), 2);
        Foothold floor = new Foothold(new Point(-100, 400), new Point(600, 400), 3);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(upper);
        footholds.insert(knifeShelf);
        footholds.insert(floor);
        map.setFootholds(footholds);

        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);

        boolean hasRightWalkOffFromUpper = graph.regions.stream()
                .flatMap(region -> graph.getOutgoing(region.id).stream())
                .anyMatch(edge -> edge.type == BotNavigationGraph.EdgeType.DROP
                        && edge.launchStepX > 0
                        && edge.startPoint.y == 100);
        assertFalse(hasRightWalkOffFromUpper,
                "a walk-off drop whose landing region flips with the launch phase must not be authored");
    }

    // Calibrated so the walk-off landing variance band for the base 100/100 profile straddles
    // the shelf's near edge (baseline vs full-speed/phase variants land on different regions).
    private static final int KNIFE_SHELF_EDGE_X = 138;

    private static DropTestFixture createDirectionalDropFixture(int mapId) {
        return createDirectionalDropFixture(mapId, true);
    }

    private static DropTestFixture createDirectionalDropFixture(int mapId, boolean dropRight) {
        MapleMap map = new MapleMap(mapId, 0, 0, mapId, 1.0f);
        Foothold upper = new Foothold(new Point(0, 100), new Point(100, 100), 1);
        Foothold lower = dropRight
                ? new Foothold(new Point(106, 160), new Point(280, 160), 2)
                : new Foothold(new Point(-180, 160), new Point(-6, 160), 2);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(upper);
        footholds.insert(lower);
        map.setFootholds(footholds);

        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        BotNavigationGraph.Edge edge = graph.regions.stream()
                .flatMap(region -> graph.getOutgoing(region.id).stream())
                .filter(candidate -> candidate.type == BotNavigationGraph.EdgeType.DROP
                        && (dropRight ? candidate.launchStepX > 0 : candidate.launchStepX < 0))
                .filter(candidate -> candidate.endPoint.y > candidate.startPoint.y)
                .findFirst()
                .orElse(null);
        assertNotNull(edge, "fixture should produce a directional walk-off drop edge");
        return new DropTestFixture(map, graph, edge);
    }

    private static Character mockBot(Point startPosition, MapleMap map) {
        Character bot = mock(Character.class);
        AtomicReference<Point> position = new AtomicReference<>(new Point(startPosition));
        when(bot.getPosition()).thenAnswer(invocation -> new Point(position.get()));
        doAnswer(invocation -> {
            position.set(new Point(invocation.getArgument(0)));
            return null;
        }).when(bot).setPosition(any(Point.class));
        when(bot.getMap()).thenReturn(map);
        when(bot.getId()).thenReturn(1);
        when(bot.getHp()).thenReturn(100);
        when(bot.getTotalMoveSpeedStat()).thenReturn(100);
        when(bot.getTotalJumpStat()).thenReturn(100);
        return bot;
    }

    private record DropTestFixture(MapleMap map,
                                   BotNavigationGraph graph,
                                   BotNavigationGraph.Edge edge) {
    }
}
