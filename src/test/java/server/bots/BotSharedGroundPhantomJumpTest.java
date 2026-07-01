package server.bots;

import org.junit.jupiter.api.Test;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces the map 600020100 stuck/oscillation (pathlog-SuseRug-2026-06-29T141220): the bot froze
 * against an unexecutable {@code JUMP r97->r73} whose launch and landing were both at y=156. Region 73
 * is a ramp whose flat FOOT overlaps the flat region 97 at the same coordinate — two distinct foothold
 * chains sharing the same ground. The client (CVecCtrl) tracks the standing foothold's prev/next chain
 * and never switches chains on such shared ground, so that jump can NEVER change region: A* committed
 * it, the bot could not perform it, and it oscillated forever.
 *
 * <p>Two guarantees are tested:
 * <ul>
 *   <li><b>Builder</b>: no ballistic (JUMP/FLASH_JUMP) edge is authored whose landing is on ground the
 *       SOURCE region already covers (shared chains). A genuine same-height jump across a real gap is
 *       still authored (no over-pruning).</li>
 *   <li><b>Region resolution</b>: a bot keeps the chain it walked in on across shared ground instead of
 *       flipping to the overlapping region.</li>
 * </ul>
 */
class BotSharedGroundPhantomJumpTest {

    private static final int MAP = 600020100;

    /** {id, x1,y1, x2,y2, prev, next}. Region A (flat, like r97) is one chain at y=156. Region B (ramp,
     *  like r73) is a SEPARATE chain whose flat FOOT (fh10) coincides with A at y=156 then rises up-right.
     *  A covers the shared coordinate but B is not in A's chain. B is inserted first so the foothold tree
     *  resolves the shared ground to B — the same tie-break that, in the live map, made the builder land
     *  a jump from r97 onto r73's coincident foot and author the unexecutable phantom JUMP r97->r73. */
    private static final int[][] OVERLAP_FH = {
            {10, -1340, 156, -1255, 156, 0, 11}, // B foot: coincides with flat A at y=156
            {11, -1255, 156, -1080, 96, 10, 12},
            {12, -1080, 96, -900, -24, 11, 13},
            {13, -900, -24, -720, -24, 12, 0},
            {50, -1475, 156, -1260, 156, 0, 51},
            {51, -1260, 156, 865, 156, 50, 0},
    };

    /** {id, x1,y1, x2,y2, prev, next}. Two flat platforms at the SAME height with a real gap between
     *  them — a legitimate same-height jump the builder must STILL author (over-pruning guard). */
    private static final int[][] GAP_FH = {
            {300, -600, 156, -360, 156, 0, 0},
            {301, -300, 156, 0, 156, 0, 0}, // 60px gap (−360 .. −300), no shared ground
    };

    private static MapleMap buildMap(int[][] footholds) {
        MapleMap map = new MapleMap(MAP, 0, 0, MAP, 1.0f);
        FootholdTree tree = new FootholdTree(new Point(-30000, -30000), new Point(30000, 30000));
        for (int[] f : footholds) {
            Foothold fh = new Foothold(new Point(f[1], f[2]), new Point(f[3], f[4]), f[0]);
            fh.setPrev(f[5]);
            fh.setNext(f[6]);
            tree.insert(fh);
        }
        map.setFootholds(tree);
        return map;
    }

    @Test
    void surfaceCoversPointIsExact() {
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(buildMap(OVERLAP_FH));
        BotNavigationGraph.Region a = graph.getRegion(graph.regionIdByFootholdId.get(50));
        assertTrue(a.surfaceCoversPoint(-1300, 156, 0), "A's flat surface passes through (-1300,156)");
        assertFalse(a.surfaceCoversPoint(-1300, 155, 0), "1px off is a distinct platform on top, NOT shared");
        assertFalse(a.surfaceCoversPoint(2000, 156, 0), "outside A's x-extent is not covered");
    }

    @Test
    void overlapFormsTwoSeparateRegions() {
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(buildMap(OVERLAP_FH));
        Integer a = graph.regionIdByFootholdId.get(50);
        Integer b = graph.regionIdByFootholdId.get(10);
        assertNotNull(a);
        assertNotNull(b);
        assertNotEquals(a, b, "flat A and ramp B are distinct foothold chains -> distinct regions");
    }

    @Test
    void noBallisticEdgeLandsOnSharedGround() {
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(buildMap(OVERLAP_FH));
        for (BotNavigationGraph.Region region : graph.regions) {
            for (BotNavigationGraph.Edge e : graph.getOutgoing(region.id)) {
                if (e.type == BotNavigationGraph.EdgeType.JUMP
                        || e.type == BotNavigationGraph.EdgeType.FLASH_JUMP) {
                    assertFalse(region.surfaceCoversPoint(e.endPoint.x, e.endPoint.y, 0),
                            "phantom " + e.type + " r" + region.id + "->r" + e.toRegionId
                                    + " lands at " + e.endPoint + " on its own source surface");
                }
            }
        }
    }

    @Test
    void genuineSameHeightGapJumpIsStillAuthored() {
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(buildMap(GAP_FH));
        int left = graph.regionIdByFootholdId.get(300);
        int right = graph.regionIdByFootholdId.get(301);
        boolean hasJump = graph.getOutgoing(left).stream()
                .anyMatch(e -> e.type == BotNavigationGraph.EdgeType.JUMP && e.toRegionId == right);
        assertTrue(hasJump, "a same-height jump across a real gap must NOT be pruned by the shared-ground guard");
    }

    @Test
    void chainContinuityKeepsRegionAcrossSharedGround() {
        MapleMap map = buildMap(OVERLAP_FH);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        BotMovementSimulationLab lab = BotMovementSimulationLab.fromMap(map);
        BotEntry bot = lab.spawnBot("x", 1, map, new Point(-1400, 156));
        int aId = graph.regionIdByFootholdId.get(50);

        // Start on pure A (left of the overlap) — establishes the chain we are on.
        assertEquals(aId, BotNavigationManager.resolveCurrentRegionId(graph, bot, map, new Point(-1400, 156)),
                "starts on region A");
        // Now standing on the SHARED foot (x in [-1315,-1260], y=156): must stay on A, not flip to ramp B.
        assertEquals(aId, BotNavigationManager.resolveCurrentRegionId(graph, bot, map, new Point(-1300, 156)),
                "keeps chain A across shared ground instead of flipping to the overlapping ramp B");
    }
}
