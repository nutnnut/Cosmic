package server.bots;

import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Manual graphgen benchmark — run explicitly with
 * -Dtest=GraphGenBenchmarkTest when tuning build performance.
 * Prints the per-phase build profile for a big map (Ellinia).
 */
class GraphGenBenchmarkTest {

    @Test
    void benchmarkEllinia() throws Exception {
        benchmark(101000000); // Ellinia — user-reported dozens of seconds
    }

    /** Region/edge counts for the descent-fix benchmark — run before/after the fall-sim cap change.
     *  Edge counts must INCREASE (longer falls now find a landing -> more DROP/JUMP edges), never drop. */
    @Test
    void benchmarkDescentMaps() throws Exception {
        for (int mapId : new int[]{101010103, 106010000, 101000000, 105040300, 100000000}) {
            MapleMap map = BotNavigationMapLoader.loadMapGeometry(mapId);
            BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
            Object r = BotNavigationGraphProvider.getLastBuildReport(mapId, BotMovementProfile.base());
            System.out.println("BENCH " + mapId
                    + " regions=" + field(r, "regionCount")
                    + " edges=" + field(r, "totalEdgeCount")
                    + " drop=" + field(r, "dropEdgeCount")
                    + " jump=" + field(r, "jumpEdgeCount")
                    + " walk=" + field(r, "walkEdgeCount")
                    + " portal=" + field(r, "portalEdgeCount"));
        }
    }

    private static Object field(Object report, String name) throws Exception {
        Field f = report.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(report);
    }

    private static void benchmark(int mapId) throws Exception {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(mapId);
        long startedAt = System.nanoTime();
        BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        long totalMs = (System.nanoTime() - startedAt) / 1_000_000;
        System.out.println("=== graphgen " + mapId + " total " + totalMs + " ms ===");
        Object report = BotNavigationGraphProvider.getLastBuildReport(mapId, BotMovementProfile.base());
        if (report == null) {
            System.out.println("no build report");
            return;
        }
        for (Field f : report.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            Object v = f.get(report);
            if (f.getName().endsWith("Ns") && v instanceof Long ns) {
                System.out.println("  " + f.getName() + " = " + (ns / 1_000_000) + " ms");
            } else if (v instanceof Number || v instanceof String) {
                System.out.println("  " + f.getName() + " = " + v);
            }
        }
    }
}
