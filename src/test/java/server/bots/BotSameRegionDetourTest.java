package server.bots;

import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Same-region-but-disconnected geometry guard (nav-graph backed — run explicitly:
 * mvn test -Dtest=BotSameRegionDetourTest).
 *
 * Map 1020000 region 11 is two platforms split by a gap. A bot at (233,215) and the east00
 * portal at (701,209) both resolve to region 11, yet the only route loops OUT through a portal
 * and back (PORTAL r11->r9 ... JUMP r9->r11). This is the case that made the bot thrash: the
 * reuseCommittedEdge "same region => leave-region edge is stale" guard retired that valid loop
 * every tick. If someone splits r11 into two regions (also valid), this scenario changes — update
 * the test then. The invariant we lock: same start/target region must still yield a non-empty path.
 */
class BotSameRegionDetourTest {

    @Test
    void sameRegionTargetStillNeedsPortalLoop() {
        System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(1020000);
        BotNavigationGraph graph = BotNavigationGraphProvider.getGraph(map);

        Point bot = new Point(233, 215);
        Point pin = new Point(701, 209); // east00 portal -> map 2000000

        int botReg = graph.findRegionId(map, bot);
        int pinReg = BotNavigationManager.resolvePointTargetRegionId(graph, map, pin);
        assertEquals(botReg, pinReg, "scenario assumes bot+target share a region");

        BotNavigationManager.SearchOutcome out = BotNavigationManager.runSearch(
                graph, map, bot, botReg, pinReg, pin, "detour-test",
                BotNavigationManager.useAdmissibleHeuristic, false, 0L);

        assertFalse(out.path().isEmpty(),
                "same-region disconnected platforms must yield a detour path, not empty (direct walk)");
        List<BotNavigationGraph.Edge> p = out.path();
        assertEquals(botReg, p.get(0).fromRegionId, "detour must start in the bot's region");
        assertEquals(botReg, p.get(p.size() - 1).toRegionId, "detour must loop back to the bot's region");
    }
}
