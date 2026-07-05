package server.bots;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Regression for the 600020100 (Deity Room) shared-ground stucks — KB oscillation ledger #15
 * (pathlog-VonLaugh-2026-07-04T041519, pathlog-MAGEFUNNY-2026-07-04T043204).
 *
 * <p>The r73 structure's 420px cliff wall chain sits at x=-1315 with its foot ON the r97 floor;
 * floor foothold 396 passes under it unbroken. Client truth (v83 CVecCtrl::CalcWalk +
 * CollisionDetectFloat, field names from the v95 PDB, live-verified in the real client both
 * directions, walking AND airborne): ground walking scans NO walls at all (chain traversal only),
 * and airborne wall collision is scoped by WZ zMass group — this wall belongs to the r73
 * structure's group, so floor movers pass it entirely. The server used to block ALL ground steps
 * crossing any "collidable" wall, freezing floor-walkers at x=-1316 forever (blocked
 * jump-pos/tele-pos tug-of-war, watchdog blind).
 */
class BotDeityRoomSharedGroundTest {

    private static final int MAP = 600020100;
    private static final Point ST00 = new Point(-1392, 155); // portal to 600020000 (left edge)
    private static final Point ST01 = new Point(541, 155);   // portal to 600020200 (far right)

    @BeforeEach
    void wzPresent() {
        assumeTrue(Files.isDirectory(Path.of("wz", "Map.wz")), "wz/ not present — skipping");
    }

    /** Ground walking never collides with walls (client truth, live-verified BOTH directions at
     *  x=-1315): the r73 tower wall is invisible to floor walkers, and even a walker standing on
     *  the r73 foot crosses left onto the overlapping floor instead of stopping. */
    @Test
    void groundWalkIsNeverBlockedByWallsAtTheOverlap() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);
        BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());

        // Standing on floor fh396 left of the wall: walking right passes under the r73 cliff.
        assertFalse(BotPhysicsEngine.isGroundStepBlockedByWall(map, new Point(-1320, 156), 6),
                "foreign-chain wall at x=-1315 must not block a floor walker heading right");
        assertTrue(BotPhysicsEngine.canWalkGroundStep(map, new Point(-1320, 156), 6),
                "floor walk across the overlap start must be a plain ground step");

        // Standing on the r73 foot (fh31, findBelow tie at the overlap): walking left crosses
        // onto the r97 floor at the same y — no wall block in either direction.
        assertFalse(BotPhysicsEngine.isGroundStepBlockedByWall(map, new Point(-1313, 156), -6),
                "walls never block ground walking — leftward crossing must not report a wall stop");
    }

    /** VonLaugh class: a floor bot travelling right across the overlap froze at x=-1316 for its
     *  whole travel budget. It must keep making rightward progress past the wall now. */
    @Test
    void walkRightAcrossTheOverlapKeepsProgressing() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);
        BotMovementSimulationLab lab = BotMovementSimulationLab.fromMap(map);
        lab.spawnBot("R", 11, map, new Point(-1360, 156));
        lab.setMoveTarget("R", ST01, true);
        int bestX = Integer.MIN_VALUE;
        for (int i = 0; i < 300 && bestX < -1240; i++) {
            lab.step(1);
            bestX = Math.max(bestX, lab.position("R").x);
        }
        assertTrue(bestX >= -1240, "bot should cross the x=-1315 wall and the overlap heading right, "
                + "but only reached x=" + bestX + " (froze at the foreign-chain wall?)");
    }

    /** Stale committed route: the portal tour committed toward far st01 must be DROPPED when the
     *  same-region goal point flips to st00 (travel give-up wander-escape), instead of steering the
     *  bot right against the pin forever (the [-1331,-1319] tug-of-war). */
    @Test
    void staleRouteDropsWhenSameRegionGoalPointMoves() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);
        BotMovementSimulationLab lab = BotMovementSimulationLab.fromMap(map);
        lab.spawnBot("S", 12, map, new Point(-1420, 156));
        lab.setMoveTarget("S", ST01, true);
        lab.step(8); // commit + start walking the tour (still left of the jump windows)
        lab.setMoveTarget("S", ST00, true); // travel gave up; wander-escape pins the opposite portal
        boolean reachedPin = false;
        for (int i = 0; i < 120 && !reachedPin; i++) {
            lab.step(1);
            reachedPin = Math.abs(lab.position("S").x - ST00.x) <= 8;
        }
        assertTrue(reachedPin, "bot should walk the 79px to the new same-region goal after the "
                + "route target moved, but sits at " + lab.position("S"));
    }

    /** v70 teleport guard: no TELEPORT edge may land on ground its SOURCE region already covers
     *  (phantom chain switch), while the legitimate escape teleport off the r73 foot onto the
     *  r97-only floor must survive. */
    @Test
    void teleportEdgesNeverLandOnSourceSurface() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        int teleports = 0;
        for (BotNavigationGraph.Region region : graph.regions) {
            for (BotNavigationGraph.Edge edge : graph.getOutgoing(region.id)) {
                if (edge.type != BotNavigationGraph.EdgeType.TELEPORT) {
                    continue;
                }
                teleports++;
                assertFalse(region.surfaceCoversPoint(edge.endPoint.x, edge.endPoint.y,
                                BotNavigationGraph.SHARED_GROUND_Y_PX),
                        "phantom TELEPORT r" + edge.fromRegionId + "->r" + edge.toRegionId
                                + " lands on the source region's own ground at " + edge.endPoint);
            }
        }
        assertTrue(teleports > 0, "expected teleport edges on " + MAP);

        // The real escape: from the r73 foot, a teleport landing on r97-only floor (~-1390,156).
        int footRegion = graph.findRegionId(map, new Point(-1250, 156));
        int floorRegion = graph.findRegionId(map, ST00);
        assertTrue(footRegion >= 0 && floorRegion >= 0 && footRegion != floorRegion,
                "fixture drifted: overlap foot and st00 floor should be distinct regions");
        boolean escapeExists = false;
        for (BotNavigationGraph.Edge edge : graph.getOutgoing(footRegion)) {
            if (edge.type == BotNavigationGraph.EdgeType.TELEPORT && edge.toRegionId == floorRegion) {
                escapeExists = true;
                break;
            }
        }
        assertTrue(escapeExists, "the legitimate TELEPORT r" + footRegion + "->r" + floorRegion
                + " (foot -> r97-only floor) must not be over-pruned");
    }
}
