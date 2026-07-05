package server.bots;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Regression for the Free Market entrance (910000000) descent loop
 * (pathlog-CabinOpened-2026-07-03): the graph authored a directional walk-off DROP r4->r5
 * whose landing was knife-edge sensitive to the live launch state — the single baseline sim
 * landed on the r5 portal ledge, but live launch phases crossed r5's height a walk-step to the
 * right and fell through to the r6 floor. The route from r6 led back through r7->r4, so the
 * planner replanned through the same lying edge forever (r4 -> drop -> r6 -> r7 -> portal -> r4).
 * Fixed at build time (GRAPH_VERSION 69): a walk-off DROP is authored only when every
 * live-plausible launch state lands the same region ({@link BotPhysicsEngine#walkOffLandingVariants}).
 */
class BotFreeMarketEntranceDescentTest {

    private static final int MAP = 910000000;
    /** The r5 portal ledge — the hop the looping bot never completed. */
    private static final Point R5_PORTAL_LEDGE = new Point(306, -176);
    /** The r4 gacha-side ledge stance the live bot looped from. */
    private static final Point R4_START = new Point(431, -266);

    @BeforeEach
    void wzPresent() {
        assumeTrue(Files.isDirectory(Path.of("wz", "Map.wz")), "wz/ not present — skipping");
    }

    /** Every authored directional walk-off DROP must land its target region for EVERY
     *  live-plausible launch state, not just the baseline sim — a variant-unstable landing is
     *  a planner lie the executor cannot reproduce (the CabinOpened loop). */
    @Test
    void authoredWalkOffDropsAreLaunchPhaseStable() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        List<String> lies = new ArrayList<>();
        for (BotNavigationGraph.Region region : graph.regions) {
            for (BotNavigationGraph.Edge edge : graph.getOutgoing(region.id)) {
                if (edge.type != BotNavigationGraph.EdgeType.DROP || edge.launchStepX == 0) {
                    continue;
                }
                for (BotPhysicsEngine.WalkOffLanding variant : BotPhysicsEngine.walkOffLandingVariants(
                        map, edge.startPoint, Integer.signum(edge.launchStepX), BotMovementProfile.base())) {
                    int landedRegion = variant == null || variant.landing() == null
                            || variant.landing().foothold() == null
                            ? -1
                            : graph.regionIdByFootholdId.getOrDefault(variant.landing().foothold().getId(), -1);
                    if (landedRegion != edge.toRegionId) {
                        lies.add("DROP r" + edge.fromRegionId + "->r" + edge.toRegionId
                                + " from " + edge.startPoint + " lands r" + landedRegion + " in some launch phase");
                    }
                }
            }
        }
        assertTrue(lies.isEmpty(), "variant-unstable walk-off drops authored: " + lies);
    }

    /** Pruning the lying edge must not orphan the gacha ledge: the r5 portal ledge stays
     *  reachable (via the committed JUMP r4->r5 or the r4->r2 portal route). */
    @Test
    void gachaLedgeStillRoutesToPortalLedge() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        int fromRegion = regionAt(map, graph, R4_START);
        int toRegion = regionAt(map, graph, R5_PORTAL_LEDGE);
        assertTrue(fromRegion >= 0 && toRegion >= 0,
                "fixture stances must resolve to graph regions (got r" + fromRegion + ", r" + toRegion + ")");
        assertTrue(graph.canReach(fromRegion, toRegion, 0),
                "r" + fromRegion + " lost all routes to the portal ledge r" + toRegion);
    }

    private static int regionAt(MapleMap map, BotNavigationGraph graph, Point p) {
        server.maps.Foothold fh = BotPhysicsEngine.findGroundFoothold(map, p);
        return fh == null ? -1 : graph.regionIdByFootholdId.getOrDefault(fh.getId(), -1);
    }
}
