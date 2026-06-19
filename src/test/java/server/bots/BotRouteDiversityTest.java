package server.bots;

import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Route-diversification guard (nav-graph backed — run explicitly: mvn test -Dtest=BotRouteDiversityTest).
 * Per-bot seeds must fan 60 bots across many routes for the same start/target on map 10000 so they
 * don't single-file. Calibrated knobs: BotNavigationManager.JITTER_FRAC / EPSILON_SPAN.
 */
class BotRouteDiversityTest {

    // Mirror BotNavigationManager.routeSeed(bot) so seeds match what real consecutive-id bots get.
    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
    private static long routeSeed(int botId) { return mix64(botId) | 1L; }

    @Test
    void seedsFanBotsAcrossManyRoutes() {
        System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        int mapId = 10000;
        Point start = new Point(-95, 428);
        Point target = new Point(1077, 480);
        int bots = 60;

        MapleMap map = BotNavigationMapLoader.loadMapGeometry(mapId);
        BotNavigationGraph graph = BotNavigationGraphProvider.getGraph(map);
        int sReg = graph.findRegionId(map, start);
        int tReg = graph.findRegionId(map, target);
        assertTrue(sReg >= 0 && tReg >= 0 && sReg != tReg, "start/target must resolve to distinct regions");

        BotNavigationManager.SearchOutcome opt = BotNavigationManager.runSearch(
                graph, map, start, sReg, tReg, target, "diversity",
                BotNavigationManager.useAdmissibleHeuristic, false, 0L);
        long optCost = opt.cost();

        Map<String, Integer> distinct = new LinkedHashMap<>();
        List<Long> costs = new ArrayList<>();
        for (int id = 1; id <= bots; id++) {
            BotNavigationManager.SearchOutcome out = BotNavigationManager.runSearch(
                    graph, map, start, sReg, tReg, target, "diversity",
                    BotNavigationManager.useAdmissibleHeuristic, false, routeSeed(id));
            distinct.merge(sig(out.path()), 1, Integer::sum);
            costs.add((long) out.cost());
        }
        long max = costs.stream().mapToLong(Long::longValue).max().orElse(0);
        int modal = distinct.values().stream().mapToInt(Integer::intValue).max().orElse(bots);
        System.out.printf("map %d: bots=%d distinct=%d modal=%d/%d(%.0f%%) overhead=%.1f%% jitter=%.2f epsilon=%.2f%n",
                mapId, bots, distinct.size(), modal, bots, 100.0 * modal / bots,
                optCost > 0 ? 100.0 * (max - optCost) / optCost : 0,
                BotNavigationManager.JITTER_FRAC, BotNavigationManager.EPSILON_SPAN);
        distinct.forEach((s, n) -> System.out.printf("  [%2d] %s%n", n, s));

        assertTrue(distinct.size() >= 6, "too few distinct routes: " + distinct.size());
        assertTrue(modal <= 0.40 * bots, "routes too concentrated on one corridor: " + modal + "/" + bots);
        assertTrue(optCost == 0 || max <= 1.45 * optCost, "suboptimality unbounded: max=" + max + " opt=" + optCost);
    }

    /** Path signature: the typed toRegionId hop sequence (the actual route shape). */
    private static String sig(List<BotNavigationGraph.Edge> path) {
        StringBuilder sb = new StringBuilder();
        for (BotNavigationGraph.Edge e : path) {
            sb.append(e.type.name().charAt(0)).append(e.toRegionId).append(' ');
        }
        return sb.toString().trim();
    }
}
