package server.bots;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapManager;
import server.maps.MapleMap;
import server.maps.Portal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Source of {@link BotMapPartition}s for live travel, with an incremental write-through disk cache.
 *
 * <p>A map's partition never changes for a given nav-graph version, so once derived it is appended to a
 * TSV under {@code cache/bot-partition/v<navVersion>/} and loaded back on boot — routing can then read a
 * map's components (the 99%-common "single component" verdict included) WITHOUT loading the map or its
 * nav graph. The cache fills in incrementally as bots visit/warm maps; there is no all-maps precompute.
 *
 * <p>A map whose reference (s100/j100, no-skill) nav graph isn't warm yet falls back to a single
 * fully-connected component (today's map-level behavior) and is NOT persisted, so it self-heals into a
 * real partition once the graph warms.
 */
final class BotMapPartitionProvider {

    private static final Logger log = LoggerFactory.getLogger(BotMapPartitionProvider.class);
    private static final Map<Integer, BotMapPartition> CACHE = new ConcurrentHashMap<>();
    private static final Object FILE_LOCK = new Object();
    private static volatile boolean loaded;

    static {
        // A same-version graph rebuild produces a different graph instance; drop any partition derived from
        // the old one so it's re-derived on next use. (Registered on class load — before any partition can
        // be cached, since caching goes through this class.)
        BotNavigationGraphProvider.setGraphRebuildListener(BotMapPartitionProvider::invalidate);
    }

    private BotMapPartitionProvider() {}

    private static Path cacheFile() {
        return Path.of("cache", "bot-partition", "v" + BotNavigationGraphProvider.graphVersion(), "partitions.tsv");
    }

    /** Load the persisted partitions into memory once, on first use. */
    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (FILE_LOCK) {
            if (loaded) {
                return;
            }
            Path file = cacheFile();
            if (Files.isRegularFile(file)) {
                try {
                    int n = 0;
                    for (String line : Files.readAllLines(file, StandardCharsets.US_ASCII)) {
                        if (line.isBlank()) {
                            continue;
                        }
                        BotMapPartition part = BotMapPartition.deserialize(line);
                        CACHE.put(part.mapId, part); // last row wins on dupes
                        n++;
                    }
                    log.info("Bot map partitions: loaded {} maps from cache", n);
                } catch (IOException | RuntimeException e) {
                    log.warn("Bot map partitions: cache unreadable, will rederive lazily", e);
                    CACHE.clear();
                }
            }
            loaded = true;
        }
    }

    /** Append one freshly derived partition to the on-disk cache. */
    private static void persist(BotMapPartition part) {
        synchronized (FILE_LOCK) {
            try {
                Path file = cacheFile();
                Files.createDirectories(file.getParent());
                Files.writeString(file, part.serialize() + "\n", StandardCharsets.US_ASCII,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.warn("Bot map partitions: couldn't append map {} (will rederive next boot)", part.mapId, e);
            }
        }
    }

    /** Every named portal of the map (any can be an arrival point), as the partition model wants them. */
    static List<BotMapPartition.PortalRef> portalsOf(MapleMap map) {
        List<BotMapPartition.PortalRef> out = new ArrayList<>();
        for (Portal p : map.getPortals()) {
            String name = p.getName();
            if (name == null || name.isEmpty()) {
                continue;
            }
            out.add(new BotMapPartition.PortalRef(name, p.getPosition(), p.getTargetMapId(), p.getTarget()));
        }
        return out;
    }

    /** The names of the plain cross-map exit portals (shared eligibility predicate — SSOT). */
    private static java.util.Set<String> exitNamesOf(MapleMap map) {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Portal p : map.getPortals()) {
            if (p.getName() != null && !p.getName().isEmpty()
                    && BotMapPartition.isTravelCrossMapPortal(p, map.getId())) {
                names.add(p.getName());
            }
        }
        return names;
    }

    /**
     * The partition for {@code map}: the cached one (memory or disk), a freshly built+persisted one (when
     * the reference nav graph is warm), or a fully-connected fallback (kicking an async warm) when it
     * isn't. Never null for a live map.
     */
    static BotMapPartition forMap(MapleMap map) {
        if (map == null) {
            return null;
        }
        ensureLoaded();
        List<BotMapPartition.PortalRef> portals = portalsOf(map);
        java.util.Set<String> exitNames = exitNamesOf(map);
        if (map.isSwim()) {
            List<BotMapPartition.PortalRef> exits = new ArrayList<>();
            for (BotMapPartition.PortalRef p : portals) {
                if (exitNames.contains(p.name())) {
                    exits.add(p);
                }
            }
            return BotMapPartition.fullyConnected(map.getId(), exits);
        }
        BotMapPartition cached = CACHE.get(map.getId());
        if (cached != null) {
            return cached;
        }
        BotNavigationGraph graph = BotNavigationGraphProvider.peekGraph(map, BotMovementProfile.base());
        if (graph == null) {
            BotNavigationGraphProvider.warmGraphAsync(map, BotMovementProfile.base());
            List<BotMapPartition.PortalRef> exits = new ArrayList<>();
            for (BotMapPartition.PortalRef p : portals) {
                if (exitNames.contains(p.name())) {
                    exits.add(p);
                }
            }
            return BotMapPartition.fullyConnected(map.getId(), exits); // provisional: not cached
        }
        BotMapPartition built = BotMapPartition.fromNavGraph(map.getId(), graph, portals, exitNames,
                p -> graph.findRegionId(map, p.position()));
        BotMapPartition prev = CACHE.putIfAbsent(map.getId(), built);
        if (prev != null) {
            return prev; // lost a build race — keep the first, don't double-persist
        }
        persist(built);
        return built;
    }

    /** Partition for a map id, served from cache when persisted (no map load), else loading the map via
     *  {@code mf} to derive it. Null when the map can't be loaded. Used by the cross-map router. */
    static BotMapPartition forMapId(MapManager mf, int mapId) {
        ensureLoaded();
        BotMapPartition cached = CACHE.get(mapId);
        if (cached != null) {
            return cached;
        }
        if (mf == null) {
            return null;
        }
        return forMap(mf.getMap(mapId));
    }

    /** Drop a map's cached partition (e.g. after a nav graph rebuild for that map). Memory only — the
     *  disk row is superseded on next derivation (last-row-wins on load). */
    static void invalidate(int mapId) {
        CACHE.remove(mapId);
    }
}
