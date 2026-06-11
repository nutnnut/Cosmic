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
