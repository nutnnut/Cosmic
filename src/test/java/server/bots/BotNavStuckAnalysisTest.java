package server.bots;

import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for the within-region '-<' branch detour on 101020000 (the magician 2nd-job shaft).
 *
 * <p>Region 29 merges the branch at vertex (-471,-1808): the bot lands on the UPPER arm (~-288,-1717)
 * but the edge that continues the climb (CLIMB onto the rope ~ -160,-1912) launches from the LOWER
 * arm. The only legal route is to walk LEFT to the stem (away from the launch x) then back onto the
 * lower arm. Monotone "steer toward launch x" can never do that — the bot oscillated forever and
 * never job-advanced. {@link BotNavigationManager#footholdDetourWaypoint} must return an away-from-launch
 * waypoint here, and must stay inert when no detour is needed.
 */
class BotNavStuckAnalysisTest {

    private static final int MAP = 101020000;
    private static final Point UPPER_ARM = new Point(-288, -1717); // where JUMP r31->r29 lands
    private static final Point ROPE_LAUNCH = new Point(-160, -1912); // CLIMB r29->rope launch (lower arm)

    @Test
    void branchDetourSteersAwayFromLaunchTowardStem() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        BotMovementSimulationLab lab = BotMovementSimulationLab.fromMap(map);

        int region = regionAt(graph, UPPER_ARM);
        BotNavigationGraph.Edge climb = null;
        for (BotNavigationGraph.Edge e : graph.outgoingByRegionId.getOrDefault(region, List.of())) {
            if (e.type == BotNavigationGraph.EdgeType.CLIMB
                    && Math.abs(e.startPoint.x - ROPE_LAUNCH.x) <= 60
                    && Math.abs(e.startPoint.y - ROPE_LAUNCH.y) <= 60) {
                climb = e;
                break;
            }
        }
        assertNotNull(climb, "expected a CLIMB-to-rope edge launching from the lower arm of r" + region);

        // Bot on the upper arm: the launch is to the RIGHT, but the legal walk chain goes LEFT first.
        BotEntry upper = lab.spawnBot("upper", 1, map, UPPER_ARM);
        Point wp = BotNavigationManager.footholdDetourWaypoint(upper, graph, UPPER_ARM, climb);
        assertNotNull(wp, "branch detour should engage when launch foothold is across the vertex");
        assertTrue(climb.startPoint.x > UPPER_ARM.x, "sanity: rope launch is to the right of the bot");
        assertTrue(wp.x < UPPER_ARM.x,
                "detour must steer AWAY from the launch x (left toward the stem); got " + wp);

        // Inertness: a bot already on the launch foothold gets no detour (normal steering takes over).
        BotEntry atLaunch = lab.spawnBot("atLaunch", 2, map, ROPE_LAUNCH);
        assertNull(BotNavigationManager.footholdDetourWaypoint(atLaunch, graph, ROPE_LAUNCH, climb),
                "no detour when already on the launch foothold");
    }

    private static int regionAt(BotNavigationGraph g, Point p) {
        int best = -1;
        long bestD = Long.MAX_VALUE;
        for (BotNavigationGraph.Region r : g.regions) {
            for (BotNavigationGraph.Segment s : r.segments) {
                if (!s.containsX(p.x)) {
                    continue;
                }
                long d = Math.abs((long) s.pointAt(p.x).y - p.y);
                if (d < bestD) {
                    bestD = d;
                    best = r.id;
                }
            }
        }
        return best;
    }
}
