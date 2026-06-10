package server.bots;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.maps.Portal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which maps connect to which? A world-wide portal adjacency graph built once from
 * {@code Map.wz}, used to route multi-hop cross-map travel ("owner is 3 portal hops away —
 * which portal do I take first?"). Edges mirror what {@code BotTravelManager.findAdjacentPortal}
 * can actually execute: unscripted, non-door portals with a real target map. Scripted portals
 * (quest gates, taxis, boats) are deliberately absent — those routes fall back to the warp.
 *
 * <p>Same pattern as {@link BotSpawnIndex}: ~5.8k map XMLs scanned in seconds on first boot,
 * then cached as a TSV under {@code cache/bot-world/v<N>/}. The {@code info/link} indirection
 * is followed exactly like MapFactory, so linked maps get the portals the runtime map gets.
 * Bump {@link #GRAPH_VERSION} when the row format or scan semantics change.
 */
final class BotWorldGraph {

    private static final Logger log = LoggerFactory.getLogger(BotWorldGraph.class);
    private static final int GRAPH_VERSION = 1;
    private static final Path CACHE_FILE =
            Path.of("cache", "bot-world", "v" + GRAPH_VERSION, "portal-graph.tsv");
    private static final int NO_TARGET_MAPID = 999999999; // tm of spawn points / doors

    /** Directed adjacency: mapId → distinct target mapIds (sorted). */
    record Index(Map<Integer, int[]> edges) {
        int[] neighbors(int mapId) {
            return edges.getOrDefault(mapId, new int[0]);
        }
    }

    private static volatile Index index;

    private BotWorldGraph() {}

    /** The world portal graph, built (or loaded from cache) on first use. Never null; empty on failure. */
    static Index get() {
        Index cached = index;
        if (cached != null) {
            return cached;
        }
        synchronized (BotWorldGraph.class) {
            if (index == null) {
                index = loadOrBuild();
            }
            return index;
        }
    }

    /**
     * Shortest portal route from one map to another: the sequence of map ids to enter, ending
     * with {@code toMapId}. Empty when already there; null when unreachable within
     * {@code maxHops} (boat rides, scripted gates, other continents — caller warps instead).
     */
    static List<Integer> route(int fromMapId, int toMapId, int maxHops) {
        return route(get(), fromMapId, toMapId, maxHops);
    }

    /** Pure BFS over an explicit graph; see {@link #route(int, int, int)}. */
    static List<Integer> route(Index graph, int fromMapId, int toMapId, int maxHops) {
        if (fromMapId == toMapId) {
            return List.of();
        }
        if (maxHops <= 0) {
            return null;
        }
        Map<Integer, Integer> cameFrom = new HashMap<>();
        ArrayDeque<Integer> frontier = new ArrayDeque<>();
        cameFrom.put(fromMapId, fromMapId);
        frontier.add(fromMapId);
        int depth = 0;
        while (!frontier.isEmpty() && depth < maxHops) {
            depth++;
            for (int level = frontier.size(); level > 0; level--) {
                int current = frontier.poll();
                for (int next : graph.neighbors(current)) {
                    if (cameFrom.putIfAbsent(next, current) != null) {
                        continue;
                    }
                    if (next == toMapId) {
                        return reconstruct(cameFrom, fromMapId, toMapId);
                    }
                    frontier.add(next);
                }
            }
        }
        return null;
    }

    /** All maps reachable within maxHops portal hops of fromMapId, including fromMapId itself. */
    static Set<Integer> reachableWithin(int fromMapId, int maxHops) {
        return reachableWithin(get(), fromMapId, maxHops);
    }

    /** Pure BFS flood over an explicit graph; see {@link #reachableWithin(int, int)}. */
    static Set<Integer> reachableWithin(Index graph, int fromMapId, int maxHops) {
        Set<Integer> seen = new HashSet<>();
        ArrayDeque<Integer> frontier = new ArrayDeque<>();
        seen.add(fromMapId);
        frontier.add(fromMapId);
        for (int depth = 0; depth < maxHops && !frontier.isEmpty(); depth++) {
            for (int level = frontier.size(); level > 0; level--) {
                int current = frontier.poll();
                for (int next : graph.neighbors(current)) {
                    if (seen.add(next)) {
                        frontier.add(next);
                    }
                }
            }
        }
        return seen;
    }

    private static List<Integer> reconstruct(Map<Integer, Integer> cameFrom, int fromMapId, int toMapId) {
        List<Integer> hops = new ArrayList<>();
        for (int at = toMapId; at != fromMapId; at = cameFrom.get(at)) {
            hops.add(at);
        }
        Collections.reverse(hops);
        return List.copyOf(hops);
    }

    private static Index loadOrBuild() {
        Index loaded = loadCache();
        if (loaded != null) {
            log.info("Bot world graph: loaded {} maps from cache", loaded.edges().size());
            return loaded;
        }
        long startedAt = System.currentTimeMillis();
        Map<Integer, int[]> edges = scanWz();
        log.info("Bot world graph: scanned {} maps ({} with outgoing portals) in {} ms",
                edges.size(),
                edges.values().stream().filter(targets -> targets.length > 0).count(),
                System.currentTimeMillis() - startedAt);
        Index built = new Index(Collections.unmodifiableMap(edges));
        writeCache(built);
        return built;
    }

    private static Map<Integer, int[]> scanWz() {
        Map<Integer, int[]> edges = new HashMap<>();
        DataProvider mapSource = DataProviderFactory.getDataProvider(WZFiles.MAP);
        Path mapRoot = Path.of(WZFiles.MAP.getFilePath(), "Map");
        int failures = 0;
        for (int area = 0; area <= 9; area++) {
            Path areaDir = mapRoot.resolve("Map" + area);
            if (!Files.isDirectory(areaDir)) {
                continue;
            }
            try (var files = Files.list(areaDir)) {
                for (Path file : (Iterable<Path>) files::iterator) {
                    String name = file.getFileName().toString();
                    if (!name.endsWith(".img.xml")) {
                        continue;
                    }
                    try {
                        int mapId = Integer.parseInt(name.substring(0, name.length() - ".img.xml".length()));
                        int[] targets = readMapEdges(mapSource, area, mapId);
                        if (targets != null) {
                            edges.put(mapId, targets);
                        }
                    } catch (NumberFormatException ignored) {
                        // non-map file (e.g. AreaCode.img.xml) — skip
                    } catch (RuntimeException e) {
                        failures++;
                    }
                }
            } catch (IOException e) {
                log.warn("Bot world graph: can't list {}", areaDir, e);
            }
        }
        if (failures > 0) {
            log.warn("Bot world graph: {} map files failed to parse (skipped)", failures);
        }
        return edges;
    }

    /**
     * Outgoing walkable edges of one map, following {@code info/link} like MapFactory so the
     * portal set matches what the runtime map actually loads.
     */
    private static int[] readMapEdges(DataProvider mapSource, int area, int mapId) {
        Data mapData = mapSource.getData(mapImgPath(area, mapId));
        if (mapData == null) {
            return null;
        }
        Data info = mapData.getChildByPath("info");
        String link = info != null ? DataTool.getString("link", info, "") : "";
        if (!link.isEmpty()) {
            try {
                int linkId = Integer.parseInt(link);
                mapData = mapSource.getData(mapImgPath(linkId / 100000000, linkId));
                if (mapData == null) {
                    return new int[0];
                }
            } catch (NumberFormatException ignored) {
                // malformed link — read the map as-is
            }
        }
        Data portals = mapData.getChildByPath("portal");
        if (portals == null) {
            return new int[0];
        }
        Set<Integer> targets = new TreeSet<>();
        for (Data portal : portals) {
            int targetMapId = DataTool.getInt("tm", portal, NO_TARGET_MAPID);
            if (targetMapId == NO_TARGET_MAPID || targetMapId == mapId) {
                continue;
            }
            if (DataTool.getInt("pt", portal, 0) == Portal.DOOR_PORTAL) {
                continue;
            }
            String script = DataTool.getString("script", portal, "");
            if (!script.isEmpty()) {
                continue;
            }
            targets.add(targetMapId);
        }
        int[] out = new int[targets.size()];
        int i = 0;
        for (int target : targets) {
            out[i++] = target;
        }
        return out;
    }

    private static String mapImgPath(int area, int mapId) {
        return "Map/Map" + area + "/" + String.format("%09d", mapId) + ".img";
    }

    // ---- disk cache: one row per map: mapId \t target,target,... ----

    private static Index loadCache() {
        if (!Files.isRegularFile(CACHE_FILE)) {
            return null;
        }
        try {
            Map<Integer, int[]> edges = new HashMap<>();
            for (String line : Files.readAllLines(CACHE_FILE, StandardCharsets.US_ASCII)) {
                if (line.isBlank()) {
                    continue;
                }
                String[] cols = line.split("\t", -1);
                int mapId = Integer.parseInt(cols[0]);
                int[] targets;
                if (cols.length > 1 && !cols[1].isEmpty()) {
                    String[] parts = cols[1].split(",");
                    targets = new int[parts.length];
                    for (int i = 0; i < parts.length; i++) {
                        targets[i] = Integer.parseInt(parts[i]);
                    }
                } else {
                    targets = new int[0];
                }
                edges.put(mapId, targets);
            }
            return edges.isEmpty() ? null : new Index(Collections.unmodifiableMap(edges));
        } catch (IOException | RuntimeException e) {
            log.warn("Bot world graph: cache unreadable, rescanning WZ", e);
            return null;
        }
    }

    private static void writeCache(Index built) {
        try {
            Files.createDirectories(CACHE_FILE.getParent());
            StringBuilder sb = new StringBuilder(1 << 20);
            for (Map.Entry<Integer, int[]> e : built.edges().entrySet()) {
                sb.append(e.getKey()).append('\t');
                int[] targets = e.getValue();
                for (int i = 0; i < targets.length; i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(targets[i]);
                }
                sb.append('\n');
            }
            Files.writeString(CACHE_FILE, sb.toString(), StandardCharsets.US_ASCII);
        } catch (IOException e) {
            log.warn("Bot world graph: couldn't write cache (will rescan next boot)", e);
        }
    }

    /** Test/debug helper: build an index straight from explicit adjacency, bypassing WZ/cache. */
    static Index indexOf(Map<Integer, int[]> edges) {
        Map<Integer, int[]> copy = new HashMap<>();
        for (Map.Entry<Integer, int[]> e : edges.entrySet()) {
            copy.put(e.getKey(), Arrays.copyOf(e.getValue(), e.getValue().length));
        }
        return new Index(Collections.unmodifiableMap(copy));
    }
}
