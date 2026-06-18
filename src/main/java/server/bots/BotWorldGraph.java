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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Which maps connect to which? A world-wide portal adjacency graph built once from
 * {@code Map.wz}, used to route multi-hop cross-map travel ("owner is 3 portal hops away —
 * which portal do I take first?"). Edges mirror what {@code BotTravelManager.findAdjacentPortal}
 * can actually execute: unscripted, non-door portals with a real target map. Scripted portals
 * (quest gates, boats) are deliberately absent — those routes fall back to the warp.
 *
 * <p>Two consumable edge kinds are baked in on top of portals, both opt-in per query via
 * {@link RouteOptions} because they cost the bot something:
 * <ul>
 *   <li><b>Return scroll</b> (Nearest Town Scroll, 2030000): map → its {@code info/returnMap}
 *       town. Only present where the portal-only walk to that town needs
 *       {@link #RETURN_SCROLL_MIN_HOPS}+ hops (or doesn't exist) — a scroll on a short walk
 *       is a waste.</li>
 *   <li><b>Taxi</b>: the hardcoded Victoria cab table ({@link #TAXI_EDGES}, verified against
 *       the NPC scripts) — town → town for a meso fare, plus the VIP cab to the Ant Tunnel, plus
 *       cross-continent scripted-warp rides of the same shape (Maple Island exit boat, Herb Town
 *       &harr; Aqua Road dolphin) that connect continents the portal graph can't.</li>
 * </ul>
 *
 * <p>Same pattern as {@link BotSpawnIndex}: ~5.8k map XMLs scanned in seconds on first boot,
 * then cached as a TSV under {@code cache/bot-world/v<N>/}. The {@code info/link} indirection
 * is followed exactly like MapFactory, so linked maps get the portals the runtime map gets.
 * Bump {@link #GRAPH_VERSION} when the row format or scan semantics change.
 */
final class BotWorldGraph {

    private static final Logger log = LoggerFactory.getLogger(BotWorldGraph.class);
    private static final int GRAPH_VERSION = 2;
    private static final Path CACHE_FILE =
            Path.of("cache", "bot-world", "v" + GRAPH_VERSION, "portal-graph.tsv");
    private static final int NO_TARGET_MAPID = 999999999; // tm of spawn points / doors
    // A return scroll is only worth an edge when walking to the town would take this many hops.
    static final int RETURN_SCROLL_MIN_HOPS = 3;

    /** Per-query toggles for the consumable edges; pure portal walking ignores them all. */
    record RouteOptions(boolean withReturnScroll, int meso, boolean withFerry) {
        static final RouteOptions PORTALS_ONLY = new RouteOptions(false, 0, false);
    }

    /** One paid NPC ride: stand near {@code npcId} in {@code fromMapId}, pay, land in {@code toMapId}. */
    record TaxiEdge(int fromMapId, int npcId, int toMapId, int fare) {
    }

    // Victoria cab rides, mirrored from the NPC scripts (scripts/npc/<npcId>.js): each cab
    // warps to portal 0 of the destination for the listed fare. The 10k VIP cabs go to the
    // Ant Tunnel park. Beginner discounts are ignored — bots always have a job.
    private static final List<TaxiEdge> TAXI_EDGES = List.of(
            // Lith Harbor 104000000 — Regular Cab 1002007, VIP Cab 1002004
            new TaxiEdge(104000000, 1002007, 100000000, 1000),
            new TaxiEdge(104000000, 1002007, 102000000, 1000),
            new TaxiEdge(104000000, 1002007, 101000000, 800),
            new TaxiEdge(104000000, 1002007, 103000000, 1000),
            new TaxiEdge(104000000, 1002007, 120000000, 800),
            new TaxiEdge(104000000, 1002004, 105070001, 10000),
            // Henesys 100000000 — Regular Cab 1012000
            new TaxiEdge(100000000, 1012000, 104000000, 1000),
            new TaxiEdge(100000000, 1012000, 102000000, 1000),
            new TaxiEdge(100000000, 1012000, 101000000, 800),
            new TaxiEdge(100000000, 1012000, 103000000, 1000),
            new TaxiEdge(100000000, 1012000, 120000000, 800),
            // Perion 102000000 — Regular Cab 1022001
            new TaxiEdge(102000000, 1022001, 104000000, 1000),
            new TaxiEdge(102000000, 1022001, 100000000, 1000),
            new TaxiEdge(102000000, 1022001, 101000000, 800),
            new TaxiEdge(102000000, 1022001, 103000000, 1000),
            new TaxiEdge(102000000, 1022001, 120000000, 800),
            // Ellinia 101000000 — Regular Cab 1032000, VIP Cab 1032005
            new TaxiEdge(101000000, 1032000, 104000000, 1000),
            new TaxiEdge(101000000, 1032000, 102000000, 1000),
            new TaxiEdge(101000000, 1032000, 100000000, 1000),
            new TaxiEdge(101000000, 1032000, 103000000, 1000),
            new TaxiEdge(101000000, 1032000, 120000000, 800),
            new TaxiEdge(101000000, 1032005, 105070001, 10000),
            // Kerning City 103000000 — Regular Cab 1052016
            new TaxiEdge(103000000, 1052016, 104000000, 1000),
            new TaxiEdge(103000000, 1052016, 102000000, 1000),
            new TaxiEdge(103000000, 1052016, 100000000, 1000),
            new TaxiEdge(103000000, 1052016, 101000000, 800),
            new TaxiEdge(103000000, 1052016, 120000000, 800),
            // Nautilus Harbor 120000000 — Regular Cab 1092014
            new TaxiEdge(120000000, 1092014, 104000000, 1000),
            new TaxiEdge(120000000, 1092014, 102000000, 1000),
            new TaxiEdge(120000000, 1092014, 100000000, 1000),
            new TaxiEdge(120000000, 1092014, 101000000, 800),
            new TaxiEdge(120000000, 1092014, 103000000, 1000),

            // --- Cross-continent scripted-warp rides: same instant "stand near NPC, pay, land at
            // portal 0" shape as a cab (so taxiRide drives them unchanged), connecting continents the
            // portal graph can't. Verified against scripts/npc/<npc>.js. ---
            // Maple Island exit: Southperry dock NPC 22000 sails to Lith Harbor (22000.js: gainMeso(-150), warp(104000000,0)).
            new TaxiEdge(60000, 22000, 104000000, 150),
            // Dolphin NPC 2060009: Herb Town <-> Aqua Road (2060009.js: 10000 meso each way).
            new TaxiEdge(251000100, 2060009, 230000000, 10000),
            new TaxiEdge(230000000, 2060009, 251000100, 10000),
            // Pason 1002002 on Lith Harbor sails to Florina Beach (1002002.js: gainMeso(-1500), warp(110000000)).
            new TaxiEdge(104000000, 1002002, 110000000, 1500));

    private static final Map<Integer, List<TaxiEdge>> TAXI_BY_MAP = buildTaxiByMap();

    private static Map<Integer, List<TaxiEdge>> buildTaxiByMap() {
        Map<Integer, List<TaxiEdge>> byMap = new HashMap<>();
        for (TaxiEdge edge : TAXI_EDGES) {
            byMap.computeIfAbsent(edge.fromMapId(), k -> new ArrayList<>()).add(edge);
        }
        byMap.replaceAll((k, v) -> List.copyOf(v));
        return Collections.unmodifiableMap(byMap);
    }

    /** All outgoing taxi rides from this map (empty when no cab parks there). */
    static List<TaxiEdge> taxiEdgesFrom(int fromMapId) {
        return TAXI_BY_MAP.getOrDefault(fromMapId, List.of());
    }

    /** The taxi ride from one map to another, or null when no cab drives that route. */
    static TaxiEdge findTaxiEdge(int fromMapId, int toMapId) {
        for (TaxiEdge edge : TAXI_BY_MAP.getOrDefault(fromMapId, List.of())) {
            if (edge.toMapId() == toMapId) {
                return edge;
            }
        }
        return null;
    }

    /**
     * Directed adjacency: mapId → distinct portal target mapIds (sorted), plus the per-map
     * return-scroll shortcut (mapId → returnMap town) where it beats walking.
     */
    record Index(Map<Integer, int[]> edges, Map<Integer, Integer> scrollTargets) {
        int[] neighbors(int mapId) {
            return edges.getOrDefault(mapId, new int[0]);
        }

        /** Town a return scroll warps this map to, or -1 when the walk is short enough anyway. */
        int scrollTarget(int mapId) {
            return scrollTargets.getOrDefault(mapId, -1);
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
     * Shortest route from one map to another: the sequence of map ids to enter, ending
     * with {@code toMapId}. Empty when already there; null when unreachable within
     * {@code maxHops} (boat rides, scripted gates, other continents — caller warps instead).
     * Portal hops only — scrolls and taxis need the {@link RouteOptions} overload.
     */
    static List<Integer> route(int fromMapId, int toMapId, int maxHops) {
        return route(get(), fromMapId, toMapId, maxHops, RouteOptions.PORTALS_ONLY);
    }

    /** Like {@link #route(int, int, int)} but may spend a return scroll or taxi fare per options. */
    static List<Integer> route(int fromMapId, int toMapId, int maxHops, RouteOptions options) {
        return route(get(), fromMapId, toMapId, maxHops, options);
    }

    /** Pure BFS over an explicit graph; see {@link #route(int, int, int, RouteOptions)}. */
    static List<Integer> route(Index graph, int fromMapId, int toMapId, int maxHops, RouteOptions options) {
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
                for (int next : expand(graph, current, options)) {
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
        return reachableWithin(get(), fromMapId, maxHops, RouteOptions.PORTALS_ONLY);
    }

    /** Like {@link #reachableWithin(int, int)} but may spend a return scroll or taxi fare per options. */
    static Set<Integer> reachableWithin(int fromMapId, int maxHops, RouteOptions options) {
        return reachableWithin(get(), fromMapId, maxHops, options);
    }

    /** Reachability that refuses to traverse INTO any map {@code blocked} accepts — pruning routes
     *  THROUGH it, not just the map as a destination (so a target only reachable via a blacklisted
     *  map drops out of the set entirely). Used by the death-loop breaker / risk-aware planner. */
    static Set<Integer> reachableWithin(int fromMapId, int maxHops, RouteOptions options,
                                        java.util.function.IntPredicate blocked) {
        return reachableWithin(get(), fromMapId, maxHops, options, blocked);
    }

    /** Pure BFS flood over an explicit graph; see {@link #reachableWithin(int, int, RouteOptions)}. */
    static Set<Integer> reachableWithin(Index graph, int fromMapId, int maxHops, RouteOptions options) {
        return reachableWithin(graph, fromMapId, maxHops, options, m -> false);
    }

    static Set<Integer> reachableWithin(Index graph, int fromMapId, int maxHops, RouteOptions options,
                                        java.util.function.IntPredicate blocked) {
        Set<Integer> seen = new HashSet<>();
        ArrayDeque<Integer> frontier = new ArrayDeque<>();
        seen.add(fromMapId); // origin always allowed: the bot is standing on it
        frontier.add(fromMapId);
        for (int depth = 0; depth < maxHops && !frontier.isEmpty(); depth++) {
            for (int level = frontier.size(); level > 0; level--) {
                int current = frontier.poll();
                for (int next : expand(graph, current, options)) {
                    if (blocked.test(next)) {
                        continue; // never step into a blacklisted map -> any route through it is pruned
                    }
                    if (seen.add(next)) {
                        frontier.add(next);
                    }
                }
            }
        }
        return seen;
    }

    /** A map's outgoing edges under the given options: portals, then scroll/taxi when affordable. */
    private static List<Integer> expand(Index graph, int mapId, RouteOptions options) {
        int[] portals = graph.neighbors(mapId);
        List<Integer> out = new ArrayList<>(portals.length + 6);
        for (int next : portals) {
            out.add(next);
        }
        if (options.withReturnScroll()) {
            int scrollTarget = graph.scrollTarget(mapId);
            if (scrollTarget != -1) {
                out.add(scrollTarget);
            }
        }
        // Fares are gated per edge, not cumulatively along the route — the travel executor
        // re-checks meso at every ride, and a broke bot mid-route just falls back/re-plans.
        for (TaxiEdge taxi : TAXI_BY_MAP.getOrDefault(mapId, List.of())) {
            if (options.meso() >= taxi.fare()) {
                out.add(taxi.toMapId());
            }
        }
        if (options.withFerry()) {
            BotFerryManager.FerryRoute ferry = BotFerryManager.routeBoardingAt(mapId);
            if (ferry != null && options.meso() >= ferry.ticketCost()) {
                out.add(ferry.destinationMapId());
            }
        }
        return out;
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
        Map<Integer, int[]> edges = new ConcurrentHashMap<>(); // scanWz fans out per file
        Map<Integer, Integer> returnMaps = new ConcurrentHashMap<>();
        scanWz(edges, returnMaps);
        Map<Integer, Integer> scrollTargets = computeScrollTargets(edges, returnMaps);
        log.info("Bot world graph: scanned {} maps ({} with outgoing portals, {} scroll shortcuts) in {} ms",
                edges.size(),
                edges.values().stream().filter(targets -> targets.length > 0).count(),
                scrollTargets.size(),
                System.currentTimeMillis() - startedAt);
        Index built = new Index(Collections.unmodifiableMap(edges), Collections.unmodifiableMap(scrollTargets));
        writeCache(built);
        return built;
    }

    private static void scanWz(Map<Integer, int[]> edges, Map<Integer, Integer> returnMaps) {
        // Per-file parsing fans out to a worker pool ({@code edges}/{@code returnMaps} are
        // concurrent). XMLWZFile.getData is synchronized per instance, so one shared provider
        // would serialize the workers — each worker thread builds its own (the per-provider
        // directory walk is cheap next to parsing ~5.8k XMLs).
        ThreadLocal<DataProvider> mapSources =
                ThreadLocal.withInitial(() -> DataProviderFactory.getDataProvider(WZFiles.MAP));
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors())));
        Path mapRoot = Path.of(WZFiles.MAP.getFilePath(), "Map");
        AtomicInteger failures = new AtomicInteger();
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
                    final int fileArea = area;
                    try {
                        int mapId = Integer.parseInt(name.substring(0, name.length() - ".img.xml".length()));
                        pool.execute(() -> {
                            try {
                                readMap(mapSources.get(), fileArea, mapId, edges, returnMaps);
                            } catch (RuntimeException e) {
                                failures.incrementAndGet();
                            }
                        });
                    } catch (NumberFormatException ignored) {
                        // non-map file (e.g. AreaCode.img.xml) — skip
                    }
                }
            } catch (IOException e) {
                log.warn("Bot world graph: can't list {}", areaDir, e);
            }
        }
        pool.shutdown();
        try {
            pool.awaitTermination(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (failures.get() > 0) {
            log.warn("Bot world graph: {} map files failed to parse (skipped)", failures.get());
        }
    }

    /**
     * Outgoing walkable edges and returnMap of one map. Portals follow {@code info/link} like
     * MapFactory so the set matches what the runtime map loads; returnMap is the map's own
     * (MapFactory reads it before the link redirect too).
     */
    private static void readMap(DataProvider mapSource, int area, int mapId,
                                Map<Integer, int[]> edges, Map<Integer, Integer> returnMaps) {
        Data mapData = mapSource.getData(mapImgPath(area, mapId));
        if (mapData == null) {
            return;
        }
        Data info = mapData.getChildByPath("info");
        int returnMapId = info != null ? DataTool.getInt("returnMap", info, NO_TARGET_MAPID) : NO_TARGET_MAPID;
        if (returnMapId != NO_TARGET_MAPID && returnMapId != mapId) {
            returnMaps.put(mapId, returnMapId);
        }
        String link = info != null ? DataTool.getString("link", info, "") : "";
        if (!link.isEmpty()) {
            try {
                int linkId = Integer.parseInt(link);
                mapData = mapSource.getData(mapImgPath(linkId / 100000000, linkId));
                if (mapData == null) {
                    edges.put(mapId, new int[0]);
                    return;
                }
            } catch (NumberFormatException ignored) {
                // malformed link — read the map as-is
            }
        }
        Data portals = mapData.getChildByPath("portal");
        if (portals == null) {
            edges.put(mapId, new int[0]);
            return;
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
        edges.put(mapId, out);
    }

    /**
     * The maps whose return scroll is worth an edge: walking to their returnMap town would take
     * {@link #RETURN_SCROLL_MIN_HOPS}+ portal hops (or isn't possible at all). Computed once
     * over the finished portal graph — a depth-limited BFS per map is cheap.
     */
    static Map<Integer, Integer> computeScrollTargets(Map<Integer, int[]> edges, Map<Integer, Integer> returnMaps) {
        Index portalsOnly = new Index(edges, Map.of());
        Map<Integer, Integer> scrollTargets = new HashMap<>();
        for (Map.Entry<Integer, Integer> e : returnMaps.entrySet()) {
            int mapId = e.getKey();
            int returnMapId = e.getValue();
            if (route(portalsOnly, mapId, returnMapId, RETURN_SCROLL_MIN_HOPS - 1, RouteOptions.PORTALS_ONLY) == null) {
                scrollTargets.put(mapId, returnMapId);
            }
        }
        return scrollTargets;
    }

    private static String mapImgPath(int area, int mapId) {
        return "Map/Map" + area + "/" + String.format("%09d", mapId) + ".img";
    }

    // ---- disk cache: one row per map: mapId \t target,target,... \t scrollTarget ----

    private static Index loadCache() {
        if (!Files.isRegularFile(CACHE_FILE)) {
            return null;
        }
        try {
            Map<Integer, int[]> edges = new HashMap<>();
            Map<Integer, Integer> scrollTargets = new HashMap<>();
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
                if (cols.length > 2 && !cols[2].isEmpty()) {
                    scrollTargets.put(mapId, Integer.parseInt(cols[2]));
                }
            }
            return edges.isEmpty() ? null
                    : new Index(Collections.unmodifiableMap(edges), Collections.unmodifiableMap(scrollTargets));
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
                sb.append('\t');
                int scrollTarget = built.scrollTarget(e.getKey());
                if (scrollTarget != -1) {
                    sb.append(scrollTarget);
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
        return indexOf(edges, Map.of());
    }

    /** Test/debug helper with explicit scroll shortcuts (map → returnMap town). */
    static Index indexOf(Map<Integer, int[]> edges, Map<Integer, Integer> scrollTargets) {
        Map<Integer, int[]> copy = new HashMap<>();
        for (Map.Entry<Integer, int[]> e : edges.entrySet()) {
            copy.put(e.getKey(), Arrays.copyOf(e.getValue(), e.getValue().length));
        }
        return new Index(Collections.unmodifiableMap(copy), Map.copyOf(scrollTargets));
    }
}
