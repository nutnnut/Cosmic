package server.bots;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Regression for the Henesys department store (100000102) descent park: bots that resupplied
 * ("resupplied and omw back") never left the map, oscillating on the small shelves (r17/r18)
 * above the ground floor while travel re-ran the r17->r24 route every tick.
 *
 * <p>Root cause: the builder authored directional walk-off DROP landings by simulating a fall
 * from the exact lip pixel, but a real dismount (BotPhysicsEngine.simulateWalkOffLanding — the
 * sim the executor gate consults) leaves the ground up to one sub-tick walk step PAST the lip,
 * which can land on a different platform (r18's walk-off really lands the y=120 bookshelf, not
 * the floor). The {@code matchesDirectionalDrop} gate then required the authored landing REGION,
 * mismatched every tick, and steered the bot back to the runway anchor forever. Fixed by (a)
 * authoring the landing with the same walk-off sim (GRAPH_VERSION 68) and (b) gating execution
 * on "dismounts into a real descent", not on the knife-edge exact landing platform.
 */
class BotHenesysDeptStoreDescentTest {

    private static final int MAP = 100000102;
    private static final Point EXIT_PORTAL = new Point(300, 182); // out01 -> Henesys market

    @BeforeEach
    void wzPresent() {
        assumeTrue(Files.isDirectory(Path.of("wz", "Map.wz")), "wz/ not present — skipping");
    }

    /** Every small platform in the store (and both spawn points + the shop counter) must be able
     *  to walk/drop down to the exit portal on the ground floor — the live stuck stances
     *  (-178,98)/(-117,98) on the r17/r18 shelves included. */
    @Test
    void descentFromEverySmallPlatformReachesExitPortal() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);
        BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        Point[] starts = {
                new Point(-178, 98), new Point(-147, 98), new Point(-117, 98),   // r17 shelf
                new Point(-100, 103), new Point(-30, 103), new Point(42, 103),   // r18 shelf
                new Point(-160, 141), new Point(-114, 141), new Point(-99, 141), // r21 shelf
                new Point(-258, 84),                                             // shop counter (r15)
                new Point(-114, 115), new Point(126, 28),                        // spawn portals
        };
        List<String> stuck = new ArrayList<>();
        int id = 100;
        for (Point start : starts) {
            BotMovementSimulationLab lab = BotMovementSimulationLab.fromMap(map);
            String name = "B" + (id++);
            lab.spawnBot(name, id, map, start);
            lab.setMoveTarget(name, EXIT_PORTAL, true);
            boolean reached = false;
            for (int i = 0; i < 600 && !reached; i++) {
                lab.step(1);
                Point p = lab.position(name);
                reached = Math.abs(p.x - EXIT_PORTAL.x) <= 10 && Math.abs(p.y - EXIT_PORTAL.y) <= 6;
            }
            if (!reached) {
                stuck.add(start.x + "," + start.y + " -> parked at "
                        + lab.position(name).x + "," + lab.position(name).y);
            }
        }
        assertTrue(stuck.isEmpty(), "bots never reached the exit portal from: " + stuck);
    }

    /** Builder/executor SSOT: every authored directional walk-off DROP must land in the region the
     *  execution-time walk-off sim actually reaches from the authored runway anchor. */
    @Test
    void directionalDropLandingsMatchExecutionWalkOffSim() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        int checked = 0;
        for (BotNavigationGraph.Region region : graph.regions) {
            for (BotNavigationGraph.Edge edge : graph.getOutgoing(region.id)) {
                if (edge.type != BotNavigationGraph.EdgeType.DROP || edge.launchStepX == 0) {
                    continue;
                }
                BotPhysicsEngine.WalkOffLanding walkOff = BotPhysicsEngine.simulateWalkOffLanding(
                        map, edge.startPoint, Integer.signum(edge.launchStepX), BotMovementProfile.base());
                assertTrue(walkOff != null && walkOff.landing() != null,
                        "authored walk-off DROP r" + edge.fromRegionId + "->r" + edge.toRegionId
                                + " has no executable dismount from " + edge.startPoint);
                int landedRegion = graph.regionIdByFootholdId
                        .getOrDefault(walkOff.landing().foothold().getId(), -1);
                assertEquals(edge.toRegionId, landedRegion,
                        "authored walk-off DROP r" + edge.fromRegionId + "->r" + edge.toRegionId
                                + " from " + edge.startPoint + " actually lands in r" + landedRegion);
                checked++;
            }
        }
        assertTrue(checked > 0, "expected at least one directional walk-off DROP on " + MAP);
    }
}
