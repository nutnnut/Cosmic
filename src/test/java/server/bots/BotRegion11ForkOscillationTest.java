package server.bots;

import org.junit.jupiter.api.Test;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;
import server.maps.Rope;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces the region-11 fork oscillation on map 100040000
 * (pathlog-MinusSent-2026-06-29T032740: CLIMB r11->r48, blocked: climb-pos, the waypoint flips
 * between the launch approach (~1014,233) and the detour (~883,294), bot bounces x=880..898 forever).
 *
 * <p>Geometry (exact WZ foothold layer, ids+prev+next preserved): region 11 is a ridge that climbs
 * left->right. At vertex (883,294) it forks:
 * <ul>
 *   <li><b>Ridge A</b> (the main next-chain, climbs to the rope): fh173 -> 178 -> 179 ... -> 188
 *       (1037,233 = the CLIMB launch) -> 189 -> 190.</li>
 *   <li><b>Spur B</b> (a dead-end hanging off the fork): fh177 (883,294)->(891,295) -> 174 -> 175 ->
 *       176, ending at (964,300) with next=0.</li>
 * </ul>
 * A and B overlap in x[883,964] with A rising 8px+ above B. A bot on spur B sits at y~295; ridge A is
 * above it, so {@code findGroundFoothold} snaps the bot back down to B every walk step -> it can never
 * ascend A. The graph nonetheless merges both arms into ONE region, and the footholdDetour bandaid
 * sends the bot left/right across the fork forever.
 *
 * <p>This test recreates ONLY region 11 + the rope (not the whole map). The "spur" case currently
 * FAILS (reproduces the live bug); the "main ridge" control passes (proves the ridge itself walks).
 */
class BotRegion11ForkOscillationTest {

    private static final int MAP = 100040000;
    private static final Point ROPE_LAUNCH = new Point(1037, 233); // fh188 start = CLIMB r11->r48 launch
    private static final Point ROPE_TOP = new Point(1072, 70);      // up the rope -> forces the CLIMB

    /** Exact region-11 footholds from wz/Map.wz/.../100040000.img.xml: {id, x1,y1, x2,y2, prev, next}. */
    private static final int[][] FH = {
            {160, 594, 404, 630, 391, 0, 161},
            {161, 630, 391, 674, 390, 160, 162},
            {162, 674, 390, 679, 391, 161, 163},
            {163, 679, 391, 702, 387, 162, 164},
            {164, 702, 387, 725, 381, 163, 165},
            {165, 725, 381, 756, 371, 164, 166},
            {166, 756, 371, 776, 363, 165, 167},
            {167, 776, 363, 796, 353, 166, 168},
            {168, 796, 353, 822, 335, 167, 169},
            {169, 822, 335, 844, 316, 168, 170},
            {171, 826, 304, 862, 291, 0, 172},
            {170, 844, 316, 866, 292, 169, 173},
            {172, 862, 291, 866, 292, 171, 173},
            {173, 866, 292, 883, 294, 172, 178}, // stem; next continues UP ridge A (178)
            {177, 883, 294, 891, 295, 173, 174}, // spur B start
            {178, 883, 294, 911, 287, 173, 179}, // ridge A start
            {174, 891, 295, 921, 295, 177, 175},
            {179, 911, 287, 934, 279, 178, 180},
            {175, 921, 295, 932, 293, 174, 176},
            {176, 932, 293, 964, 300, 175, 0},   // spur B dead-end (next=0)
            {180, 934, 279, 954, 268, 179, 181},
            {181, 954, 268, 982, 241, 180, 184},
            {182, 969, 248, 981, 240, 0, 183},
            {183, 981, 240, 982, 241, 182, 184},
            {184, 982, 241, 998, 240, 183, 185},
            {185, 998, 240, 1005, 235, 184, 186},
            {186, 1005, 235, 1028, 236, 185, 187},
            {187, 1028, 236, 1037, 233, 186, 188},
            {188, 1037, 233, 1079, 235, 187, 189}, // CLIMB launch foothold
            {189, 1079, 235, 1087, 232, 188, 190},
            {190, 1087, 232, 1113, 237, 189, 0},
    };

    private static MapleMap buildRegion11() {
        MapleMap map = new MapleMap(MAP, 0, 0, MAP, 1.0f);
        FootholdTree tree = new FootholdTree(new Point(-30000, -30000), new Point(30000, 30000));
        for (int[] f : FH) {
            Foothold fh = new Foothold(new Point(f[1], f[2]), new Point(f[3], f[4]), f[0]);
            fh.setPrev(f[5]);
            fh.setNext(f[6]);
            tree.insert(fh);
        }
        map.setFootholds(tree);
        map.addRope(new Rope(1072, 63, 199, false));
        return map;
    }

    /** The design question: are the dead-end spur (B) and the climb ridge (A) merged into one region? */
    @Test
    void spurAndRidgeAreMergedIntoOneRegion() {
        MapleMap map = buildRegion11();
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        Integer ridge = graph.regionIdByFootholdId.get(178); // ridge A
        Integer spur = graph.regionIdByFootholdId.get(174);  // dead-end spur B
        assertEquals(ridge, spur,
                "spur B (fh174) and ridge A (fh178) are merged into one region (id " + ridge + "); "
                        + "the bot is not walk-able between them, so this merge is the root of the oscillation");
    }

    /** A bot that lands on the dead-end spur must still reach the climb launch. Currently it oscillates. */
    @Test
    void botOnDeadEndSpurReachesClimbLaunch() {
        assertReachesLaunch(new Point(905, 295), "dead-end spur B (fh174)");
    }

    /**
     * A bot on the MAIN ridge (fh164), walking up toward the rope, must reach the launch. It currently
     * fails too: at the fork the ground-walk snaps it onto the lower spur B and it oscillates — so the
     * fork is a universal walk-trap, not just a problem for bots that happen to land on the spur.
     */
    @Test
    void botOnMainRidgeReachesClimbLaunch() {
        assertReachesLaunch(new Point(700, 386), "main ridge (fh164)");
    }

    private void assertReachesLaunch(Point start, String where) {
        MapleMap map = buildRegion11();
        BotNavigationGraphProvider.rebuildGraph(map);
        BotMovementSimulationLab lab = BotMovementSimulationLab.fromMap(map);

        BotEntry bot = lab.spawnBot("fork", 1, map, start);
        bot.grinding = false;
        lab.setMoveTarget("fork", ROPE_TOP, true);

        int maxX = start.x;
        int minY = start.y;
        for (int i = 0; i < 160; i++) {
            lab.step(1);
            Point p = lab.position("fork");
            maxX = Math.max(maxX, p.x);
            minY = Math.min(minY, p.y);
            boolean climbing = bot.climbing;
            // success: walked onto the upper ridge (x past the spur AND y risen onto A) or grabbed the rope.
            if (climbing || (p.x >= 1000 && p.y <= 260) || p.x >= ROPE_LAUNCH.x) {
                return;
            }
        }

        List<String> tail = lab.formatRecentTrace("fork", 12);
        throw new AssertionError("bot from " + where + " never reached the CLIMB launch (1037,233): "
                + "maxX=" + maxX + " minY=" + minY + " (stuck oscillating at the fork)\n"
                + String.join("\n", tail));
    }
}
