package server.maps;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Regression for the "im at map 100000100" status bug: map-name lookups over the shared
 * String.wz Data tree must survive a cold-boot stampede (thousands of bots resolving names
 * concurrently). Requires the real wz/ folder; skipped without it.
 */
class MapFactoryNameLookupTest {

    @Test
    void concurrentColdLookupsAllResolve() throws Exception {
        assumeTrue(Files.isDirectory(Path.of("wz", "String.wz")), "needs real WZ data");

        // Every map id String.wz names — a cold cache plus all of these across a thread pool
        // reproduces the boot stampede that used to corrupt the shared tree.
        List<Integer> ids = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<imgdir name=\"(\\d+)\">")
                .matcher(Files.readString(Path.of("wz", "String.wz", "Map.img.xml")));
        while (m.find()) {
            ids.add(Integer.parseInt(m.group(1)));
        }
        assertTrue(ids.size() > 1000, "expected a full map-name table, got " + ids.size());

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Map<Integer, String> failures = new ConcurrentHashMap<>();
        try {
            List<Callable<Void>> jobs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int offset = t * ids.size() / threads; // staggered starts, full overlap
                jobs.add(() -> {
                    for (int i = 0; i < ids.size(); i++) {
                        int id = ids.get((offset + i) % ids.size());
                        String name = MapFactory.loadPlaceName(id);
                        if (name == null || name.isBlank()) {
                            failures.put(id, "blank");
                        }
                    }
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(jobs)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(Map.of(), failures, "every id must resolve under concurrency");
        assertTrue(MapFactory.loadPlaceName(280090000).contains("Tragedy"));
    }
}
