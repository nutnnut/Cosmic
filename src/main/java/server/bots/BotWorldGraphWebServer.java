package server.bots;

import client.BotClient;
import client.Character;
import client.Job;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;

import java.awt.Point;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Executors;

/**
 * Localhost-only read-only web view of the {@link BotWorldGraph} (maps as nodes, portal/taxi/ferry
 * adjacency as edges) overlaid with live per-map occupancy of every online character — bots and
 * real players alike. Open {@code http://127.0.0.1:8089/} in a browser.
 *
 * <p>Only the maps a bot can legally reach from spawn ({@link #START_MAP}, the tutorial island) are
 * shown, and node positions are computed once on the server by a deterministic directional walk (a
 * neighbour is placed in the direction of the portal that leads to it), so the graph never reshuffles
 * when live data changes — the browser draws it with a fixed {@code preset} layout and only restyles
 * on poll. Maps reachable from {@link #RETURN_ANCHOR} (Lith Harbor) but unable to get back to it are
 * flagged as one-way-in traps — the bug class this view exists to surface.
 *
 * <p>Dependency-free: the JDK's built-in {@link HttpServer} and hand-rolled JSON.
 */
public final class BotWorldGraphWebServer {

    private static final Logger log = LoggerFactory.getLogger(BotWorldGraphWebServer.class);
    // ponytail: fixed localhost port; promote to a cfg knob only if it ever clashes.
    private static final int PORT = 8089;
    private static final int START_MAP = 10000;        // spawn/tutorial area — what the graph shows (reachable-from)
    private static final int RETURN_ANCHOR = 104000000; // Lith Harbor — the hub maps must be able to return to
    private static final double EDGE_LEN = 72.0;   // uniform intra-region edge length
    private static final double TOWN_STEP = 520.0; // distance non-hardcoded region anchors spread from a hub
    private static final double CONE = 2.0;        // child angular spread (radians) of the per-region radial tree
    private static final double ROOT_CONE = Math.PI * 1.5; // root fans its children over ~270deg, facing outward
    private static final double WARP_R = 320.0;    // custom steering-anchor influence radius (matches the web view)

    // Hardcoded region anchors (hub map id -> screen pos), captured by dragging hubs in the web view and
    // exporting. Every other region's town spreads out from these over the town-to-town graph.
    private static final Map<Integer, double[]> ANCHORS = buildAnchors();

    // Custom steering anchors (non-hub map id -> target pos): nudge nearby maps toward a position without
    // being a region hub. Captured like ANCHORS (mark in the web view, drag, export). Empty until baked.
    private static final Map<Integer, double[]> CUSTOM_ANCHORS = new HashMap<>();

    private static Map<Integer, double[]> buildAnchors() {
        Map<Integer, double[]> a = new HashMap<>();
        a.put(10000, new double[]{-1773, 379});       // Mushroom Town
        a.put(1000000, new double[]{-1283, 416});     // Amherst
        a.put(100000000, new double[]{-32, 593});     // Henesys
        a.put(100000001, new double[]{-103, 690});    // Henesys Townstreet
        a.put(101000000, new double[]{560, 0});       // Ellinia
        a.put(101000001, new double[]{542, 24});      // Ellinia Weapon Store
        a.put(101000002, new double[]{560, 24});      // Ellinia Department Store
        a.put(102000000, new double[]{0, -560});      // Perion
        a.put(103000000, new double[]{-560, -360});   // Kerning City
        a.put(103000002, new double[]{-560, -336});   // Kerning City Pharmacy
        a.put(104000000, new double[]{-620, 380});    // Lith Harbor
        a.put(105040300, new double[]{0, 0});         // Sleepywood
        a.put(110000000, new double[]{-1003, 731});   // Florina Beach
        a.put(120000000, new double[]{518, 443});     // Nautilus Harbor
        a.put(140000000, new double[]{-1129, 207});   // Rien
        a.put(140000010, new double[]{-1128, 123});   // Rien Library 1st Floor
        a.put(140000011, new double[]{-1130, 44});    // Rien Library 2nd Floor
        a.put(140000012, new double[]{-1131, -34});   // Rien Library 3rd Floor
        a.put(20000, new double[]{-1693, 380});       // Snail Garden
        a.put(2000000, new double[]{-1019, 407});     // Southperry
        a.put(200000000, new double[]{1532, -998});   // Orbis
        a.put(211000000, new double[]{1502, -413});   // El Nath
        a.put(220000000, new double[]{3324, -1306});  // Ludibrium
        a.put(221000000, new double[]{3128, 666});    // Omega Sector
        a.put(221000200, new double[]{2573, -387});   // Silo
        a.put(222000000, new double[]{3807, 741});    // Korean Folk Town
        a.put(230000000, new double[]{1435, 697});    // Aquarium
        a.put(240000000, new double[]{1167, 119});    // Leafre
        a.put(250000000, new double[]{2078, 510});    // Mu Lung
        a.put(250000001, new double[]{2286, 649});    // Tae Sang's House
        a.put(250000002, new double[]{2304, 607});    // Mu Lung Department Store
        a.put(250000003, new double[]{2306, 561});    // Mu Lung Hair Salon
        a.put(251000000, new double[]{2105, 980});    // Herb Town
        a.put(260000000, new double[]{1728, 115});    // Ariant
        a.put(261000000, new double[]{2218, 115});    // Magatia
        a.put(30000, new double[]{-1598, 383});       // Snail Field of Flowers
        a.put(30001, new double[]{-1606, 470});       // Mushroom Town Townstreet
        a.put(40000, new double[]{-1499, 382});       // In a Small Forest
        a.put(680000004, new double[]{-69, 881});     // Meet the Parents
        return a;
    }

    private static volatile HttpServer server;
    private static volatile String graphJsonCache; // graph is static for the server's lifetime

    private BotWorldGraphWebServer() {
    }

    /** Starts the view. No-op if already running; a bind failure is logged, never fatal to boot. */
    public static synchronized void start() {
        if (server != null) {
            return;
        }
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
            s.createContext("/", BotWorldGraphWebServer::servePage);
            s.createContext("/map", BotWorldGraphWebServer::serveWorldMapPage);
            s.createContext("/api/graph", BotWorldGraphWebServer::serveGraph);
            s.createContext("/api/relayout", BotWorldGraphWebServer::serveRelayout);
            s.createContext("/api/worldmaps", BotWorldGraphWebServer::serveWorldMaps);
            s.createContext("/wm/", BotWorldGraphWebServer::serveWorldMapImg);
            s.createContext("/api/live", BotWorldGraphWebServer::serveLive);
            s.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "bot-worldmap-web");
                t.setDaemon(true);
                return t;
            }));
            s.start();
            server = s;
            log.info("Bot world-graph web view: http://127.0.0.1:{}/", PORT);
        } catch (IOException e) {
            log.warn("Bot world-graph web view failed to start on port {}: {}", PORT, e.toString());
        }
    }

    // --- HTTP handlers ---

    private static void servePage(HttpExchange ex) throws IOException {
        if (!"/".equals(ex.getRequestURI().getPath())) {
            send(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] body;
        try (InputStream in = BotWorldGraphWebServer.class.getResourceAsStream("/web/botworld.html")) {
            if (in == null) {
                send(ex, 500, "text/plain", "botworld.html resource missing".getBytes(StandardCharsets.UTF_8));
                return;
            }
            body = in.readAllBytes();
        }
        send(ex, 200, "text/html; charset=utf-8", body);
    }

    private static void serveWorldMapPage(HttpExchange ex) throws IOException {
        byte[] body;
        try (InputStream in = BotWorldGraphWebServer.class.getResourceAsStream("/web/worldmap.html")) {
            if (in == null) {
                send(ex, 500, "text/plain", "worldmap.html resource missing".getBytes(StandardCharsets.UTF_8));
                return;
            }
            body = in.readAllBytes();
        }
        send(ex, 200, "text/html; charset=utf-8", body);
    }

    private static void serveGraph(HttpExchange ex) throws IOException {
        String json = graphJsonCache;
        if (json == null) {
            json = buildGraphJson();
            graphJsonCache = json;
        }
        send(ex, 200, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    // --- WorldMap.wz overlay: each numbered WorldMap img has a BaseImg + MapList of spots (in-image
    // pixel = BaseImg origin + spot) tagging real map ids. First worldmap to claim a map id wins. ---

    private static volatile String worldMapsJsonCache;

    private static void serveWorldMaps(HttpExchange ex) throws IOException {
        String json = worldMapsJsonCache;
        if (json == null) {
            json = worldMapsJson();
            worldMapsJsonCache = json;
        }
        send(ex, 200, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    /** {@code {"worldmaps":[{"id":"000","spots":[{"map":,"x":,"y":}]}]}} — x/y are pixels on that
     *  worldmap's BaseImg (top-left = 0,0). */
    private static String worldMapsJson() {
        DataProvider dp = DataProviderFactory.getDataProvider(WZFiles.MAP);
        Set<Integer> claimed = new HashSet<>(); // first worldmap to list a map id keeps it
        List<String> wms = new ArrayList<>();
        for (String id : worldMapIds()) {
            Data wm = dp.getData("WorldMap/WorldMap" + id + ".img");
            if (wm == null) {
                continue;
            }
            Point origin = DataTool.getPoint("BaseImg/origin", wm, new Point(0, 0));
            Data mapList = wm.getChildByPath("MapList");
            if (mapList == null) {
                continue;
            }
            StringBuilder spots = new StringBuilder();
            for (Data entry : mapList.getChildren()) {
                Point spot = DataTool.getPoint("spot", entry, null);
                Data mapNo = entry.getChildByPath("mapNo");
                if (spot == null || mapNo == null) {
                    continue;
                }
                for (Data mn : mapNo.getChildren()) {
                    int mapId = DataTool.getInt(mn, -1);
                    if (mapId < 0 || !claimed.add(mapId)) {
                        continue;
                    }
                    if (spots.length() > 0) {
                        spots.append(',');
                    }
                    spots.append("{\"map\":").append(mapId)
                            .append(",\"x\":").append(origin.x + spot.x)
                            .append(",\"y\":").append(origin.y + spot.y).append('}');
                }
            }
            wms.add("{\"id\":\"" + id + "\",\"spots\":[" + spots + "]}");
        }
        return "{\"worldmaps\":[" + String.join(",", wms) + "]}";
    }

    /** Numbered WorldMap img ids ("000","010",...), sorted, scanned from the WZ folder. */
    private static List<String> worldMapIds() {
        List<String> ids = new ArrayList<>();
        java.util.regex.Pattern pat = java.util.regex.Pattern.compile("^WorldMap(\\d+)\\.img\\.xml$");
        try (var files = Files.list(Path.of(WZFiles.MAP.getFilePath(), "WorldMap"))) {
            for (Path p : (Iterable<Path>) files::iterator) {
                java.util.regex.Matcher m = pat.matcher(p.getFileName().toString());
                if (m.matches()) {
                    ids.add(m.group(1));
                }
            }
        } catch (IOException e) {
            log.warn("Bot world-graph web view: can't list WorldMap dir: {}", e.toString());
        }
        Collections.sort(ids);
        return ids;
    }

    /** Serve a worldmap's BaseImg PNG from {@code wz-WorldMap/<id>.png} (committed, sibling to wz/). */
    private static void serveWorldMapImg(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String id = path.substring(path.lastIndexOf('/') + 1).replace(".png", "");
        if (!id.matches("\\d+")) {
            send(ex, 404, "text/plain", "bad id".getBytes(StandardCharsets.UTF_8));
            return;
        }
        Path file = Path.of("wz-WorldMap", id + ".png");
        if (!Files.isRegularFile(file)) {
            send(ex, 404, "text/plain", "no image".getBytes(StandardCharsets.UTF_8));
            return;
        }
        send(ex, 200, "image/png", Files.readAllBytes(file));
    }

    private static void serveLive(HttpExchange ex) throws IOException {
        send(ex, 200, "application/json", liveJson(onlineCharacters()).getBytes(StandardCharsets.UTF_8));
    }

    /** Recompute positions with caller-supplied anchor overrides ({@code a=} hubs, {@code c=} steering pins,
     *  each {@code id:x:y,...}) so the web view can preview moved anchors. Reuses the cached graph data. */
    private static void serveRelayout(HttpExchange ex) throws IOException {
        Map<String, String> q = queryParams(ex.getRequestURI().getRawQuery());
        Map<Integer, double[]> hubs = parseAnchors(q.get("a"));
        Map<Integer, double[]> customs = parseAnchors(q.get("c"));
        if (hubs.isEmpty()) {
            hubs = ANCHORS; // nothing supplied -> fall back to the baked defaults
        }
        send(ex, 200, "application/json", relayoutJson(hubs, customs).getBytes(StandardCharsets.UTF_8));
    }

    /** Parse {@code id:x:y,id:x:y,...} into a map; tolerant of blanks/garbage. */
    private static Map<Integer, double[]> parseAnchors(String s) {
        Map<Integer, double[]> out = new HashMap<>();
        if (s == null || s.isBlank()) {
            return out;
        }
        for (String tok : s.split(",")) {
            String[] f = tok.split(":");
            if (f.length != 3) {
                continue;
            }
            try {
                out.put(Integer.parseInt(f[0].trim()),
                        new double[]{Double.parseDouble(f[1].trim()), Double.parseDouble(f[2].trim())});
            } catch (NumberFormatException ignore) {
                // skip malformed token
            }
        }
        return out;
    }

    private static Map<String, String> queryParams(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null) {
            return out;
        }
        for (String p : raw.split("&")) {
            int i = p.indexOf('=');
            if (i > 0) {
                out.put(p.substring(0, i), java.net.URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    // --- graph (reachable subgraph + deterministic directional layout, built once) ---

    record GNode(int id, String name, double x, double y, boolean danger, boolean leaf, boolean anchor) {
    }

    /** Position-independent graph data, computed once: reachability, typed edges, dead ends, traps, names,
     *  region grouping. Kept separate from layout so anchors can be moved (relayout) without redoing it. */
    record GraphData(Set<Integer> reachable, List<int[]> edges, Map<Integer, List<Integer>> adj,
                     Set<Integer> leaves, Set<Integer> danger, Map<Integer, String> names,
                     Map<Integer, Integer> regionOf, Map<Integer, List<Integer>> regionMembers) {
    }

    private static volatile GraphData dataCache;

    private static GraphData graphData() {
        GraphData cached = dataCache;
        if (cached != null) {
            return cached;
        }
        long t0 = System.currentTimeMillis();
        BotWorldGraph.Index idx = BotWorldGraph.get();
        // Maps a bot can legally reach from spawn: portals + boarding taxis + ferries, fares assumed
        // affordable so we get the whole traversable world (SSOT: BotWorldGraph's own flood).
        Set<Integer> reachable = BotWorldGraph.reachableWithin(START_MAP, 1000,
                new BotWorldGraph.RouteOptions(false, Integer.MAX_VALUE, true));
        Set<Long> seen = new HashSet<>();
        List<int[]> edges = new ArrayList<>();
        for (int a : reachable) {                  // portals first (type 0 = walkable)
            for (int b : idx.neighbors(a)) {
                tryEdge(a, b, 0, reachable, seen, edges);
            }
        }
        for (int a : reachable) {                  // taxi/ferry NPC rides (type 1) where not already a portal
            for (BotWorldGraph.TaxiEdge t : BotWorldGraph.taxiEdgesFrom(a)) {
                tryEdge(a, t.toMapId(), 1, reachable, seen, edges);
            }
            for (BotFerryManager.FerryRoute f : BotFerryManager.routesBoardingAt(a)) {
                tryEdge(a, f.destinationMapId(), 1, reachable, seen, edges);
            }
        }
        Map<Integer, Integer> degree = new HashMap<>();
        for (int[] e : edges) {
            degree.merge(e[0], 1, Integer::sum);
            degree.merge(e[1], 1, Integer::sum);
        }
        Set<Integer> leaves = new HashSet<>(); // degree-1 dead ends (root excluded)
        for (int m : reachable) {
            if (m != START_MAP && degree.getOrDefault(m, 0) == 1) {
                leaves.add(m);
            }
        }
        Map<Integer, List<Integer>> adj = new HashMap<>();
        for (int[] e : edges) {
            adj.computeIfAbsent(e[0], k -> new ArrayList<>()).add(e[1]);
            adj.computeIfAbsent(e[1], k -> new ArrayList<>()).add(e[0]);
        }
        for (List<Integer> nbrs : adj.values()) {
            Collections.sort(nbrs);
        }
        Map<Integer, Integer> regionOf = new HashMap<>();
        Map<Integer, List<Integer>> regionMembers = new HashMap<>();
        for (int m : reachable) {
            int r = BotWorldGraph.returnMapOf(m);
            regionOf.put(m, r);
            regionMembers.computeIfAbsent(r, k -> new ArrayList<>()).add(m);
        }
        Set<Integer> danger = unreturnable(reachable); // reachable from Lith but can't get back to it
        GraphData g = new GraphData(reachable, edges, adj, leaves, danger, mapNames(), regionOf, regionMembers);
        dataCache = g;
        log.info("Bot world-graph web view: {} reachable maps ({} dead-ends, {} unreturnable), {} edges in {} ms",
                reachable.size(), leaves.size(), danger.size(), edges.size(), System.currentTimeMillis() - t0);
        return g;
    }

    private static String buildGraphJson() {
        GraphData g = graphData();
        Map<Integer, double[]> pos = computePositions(g, ANCHORS, CUSTOM_ANCHORS);
        List<GNode> nodes = new ArrayList<>(g.reachable().size());
        for (int id : g.reachable()) {
            double[] p = pos.getOrDefault(id, new double[]{0, 0});
            // anchor = a region's hub: a map that returns to itself, the town others cluster on
            nodes.add(new GNode(id, g.names().getOrDefault(id, String.valueOf(id)), p[0], p[1],
                    g.danger().contains(id), g.leaves().contains(id), BotWorldGraph.returnMapOf(id) == id));
        }
        return graphJson(nodes, g.edges());
    }

    /** Positions-only JSON for a relayout with caller-supplied anchor overrides (moved hubs + steering
     *  pins), so the web view previews anchor changes live without a server restart. */
    private static String relayoutJson(Map<Integer, double[]> hubs, Map<Integer, double[]> customs) {
        GraphData g = graphData();
        Map<Integer, double[]> pos = computePositions(g, hubs, customs);
        StringBuilder sb = new StringBuilder("{\"pos\":{");
        boolean first = true;
        for (int id : g.reachable()) {
            double[] p = pos.getOrDefault(id, new double[]{0, 0});
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(id).append("\":[").append(Math.round(p[0])).append(',')
                    .append(Math.round(p[1])).append(']');
        }
        return sb.append("}}").toString();
    }

    /**
     * Maps reachable FROM {@link #RETURN_ANCHOR} (Lith Harbor) that cannot get BACK to it by any legal
     * bot means (portals, taxis, ferries, or a return scroll) — one-way-in traps. The gate on
     * "reachable from Lith" keeps the deliberately one-way tutorial island (spawn → boat → Lith) out of
     * the danger set. The bug class this view exists to surface; see the Sleepywood trap rescue work.
     *
     * <p>ponytail: one route() flood per shown map. Fine one-time + cached; if it ever drags, swap for a
     * single reverse-reachability BFS from the anchor.
     */
    private static Set<Integer> unreturnable(Set<Integer> shown) {
        Set<Integer> fromAnchor = BotWorldGraph.reachableWithin(RETURN_ANCHOR, 1000,
                new BotWorldGraph.RouteOptions(false, Integer.MAX_VALUE, true));
        BotWorldGraph.RouteOptions back = new BotWorldGraph.RouteOptions(true, Integer.MAX_VALUE, true);
        Set<Integer> danger = new HashSet<>();
        for (int m : shown) {
            if (m != RETURN_ANCHOR && fromAnchor.contains(m)
                    && BotWorldGraph.route(m, RETURN_ANCHOR, 1000, back) == null) {
                danger.add(m);
            }
        }
        return danger;
    }

    private static void tryEdge(int a, int b, int type, Set<Integer> reach, Set<Long> seen, List<int[]> out) {
        if (a == b || !reach.contains(a) || !reach.contains(b)) {
            return;
        }
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        if (seen.add(((long) lo << 32) | (hi & 0xFFFFFFFFL))) {
            out.add(new int[]{lo, hi, type}); // type 0 = portal/walk, 1 = taxi/ferry NPC ride
        }
    }

    /**
     * Free-form, region-clustered positions. Each map belongs to the region of the town it returns to
     * ({@link BotWorldGraph#returnMapOf}); every region is laid out as a uniform-edge-length tree rooted
     * at its town's anchor, so one hop is the same length everywhere inside a region (it only changes
     * when an edge crosses into another region). Dead ends ({@code leaves}) stay tiny, tucked under their
     * single neighbour wherever it landed. No grid — positions are continuous.
     */
    private static Map<Integer, double[]> computePositions(GraphData g, Map<Integer, double[]> hubs,
                                                           Map<Integer, double[]> customs) {
        Map<Integer, double[]> anchor = placeRegions(g.regionMembers().keySet(), g.adj(), g.regionOf(), hubs);
        Map<Integer, double[]> outward = outwardDirs(anchor); // which way is "away from other hubs"

        Map<Integer, double[]> pos = new HashMap<>();
        for (Map.Entry<Integer, List<Integer>> e : g.regionMembers().entrySet()) {
            radialPlace(e.getKey(), e.getValue(), anchor.get(e.getKey()), outward.get(e.getKey()),
                    g.adj(), g.regionOf(), g.leaves(), g.reachable(), pos);
        }

        // Dead ends last: small, clustered under their one neighbour (even if that neighbour is in another
        // region) — so a one-edge spur never stretches the trunk.
        Map<Integer, Integer> childCount = new HashMap<>();
        List<Integer> sortedLeaves = new ArrayList<>(g.leaves());
        Collections.sort(sortedLeaves);
        for (int leaf : sortedLeaves) {
            List<Integer> nbrs = g.adj().getOrDefault(leaf, List.of());
            int parentKey = nbrs.isEmpty() ? leaf : nbrs.get(0);
            double[] pp = nbrs.isEmpty() ? null : pos.get(parentKey);
            if (pp == null) {
                pp = anchor.getOrDefault(g.regionOf().get(leaf), new double[]{0, 0});
            }
            pos.put(leaf, childSlot(pp, childCount.merge(parentKey, 1, Integer::sum) - 1));
        }
        applyCustomSteering(pos, customs);
        return pos;
    }

    /** Pull nearby maps toward each baked custom steering anchor (linear falloff within {@link #WARP_R}),
     *  mirroring the web view's drag-warp. Region hubs and other steering anchors stay put. */
    private static void applyCustomSteering(Map<Integer, double[]> pos, Map<Integer, double[]> customs) {
        for (Map.Entry<Integer, double[]> a : customs.entrySet()) {
            double[] cur = pos.get(a.getKey());
            if (cur == null) {
                continue;
            }
            double cx = cur[0]; // snapshot the anchor's pre-warp position
            double cy = cur[1];
            double dx = a.getValue()[0] - cx;
            double dy = a.getValue()[1] - cy;
            for (Map.Entry<Integer, double[]> pe : pos.entrySet()) {
                int id = pe.getKey();
                if (ANCHORS.containsKey(id) || (id != a.getKey() && customs.containsKey(id))) {
                    continue; // hubs and other steering pins are fixed
                }
                double[] p = pe.getValue();
                double dist = Math.hypot(p[0] - cx, p[1] - cy);
                if (dist >= WARP_R) {
                    continue;
                }
                double w = 1 - dist / WARP_R;
                p[0] += dx * w;
                p[1] += dy * w;
            }
        }
    }

    /** Screen position of every region's town: the hardcoded hubs are fixed; the rest spread out from them
     *  over the town-to-town adjacency at a fixed step (free-form fan, deterministic). */
    private static Map<Integer, double[]> placeRegions(Set<Integer> regions, Map<Integer, List<Integer>> adj,
                                                       Map<Integer, Integer> regionOf, Map<Integer, double[]> hubs) {
        Map<Integer, Set<Integer>> townAdj = new HashMap<>();
        for (Map.Entry<Integer, List<Integer>> e : adj.entrySet()) {
            int ra = regionOf.getOrDefault(e.getKey(), e.getKey());
            for (int b : e.getValue()) {
                int rb = regionOf.getOrDefault(b, b);
                if (ra != rb) {
                    townAdj.computeIfAbsent(ra, k -> new TreeSet<>()).add(rb);
                    townAdj.computeIfAbsent(rb, k -> new TreeSet<>()).add(ra);
                }
            }
        }
        Map<Integer, double[]> anchor = new HashMap<>();
        for (Map.Entry<Integer, double[]> e : hubs.entrySet()) {
            anchor.put(e.getKey(), e.getValue().clone());
        }
        ArrayDeque<Integer> queue = new ArrayDeque<>(new TreeSet<>(hubs.keySet())); // deterministic seeds
        Map<Integer, Integer> fan = new HashMap<>();
        while (!queue.isEmpty()) {
            int t = queue.poll();
            double[] pt = anchor.get(t);
            for (int n : townAdj.getOrDefault(t, Set.of())) {
                if (anchor.containsKey(n)) {
                    continue;
                }
                double ang = (fan.merge(t, 1, Integer::sum) - 1) * 2.399963; // golden-angle fan
                anchor.put(n, new double[]{pt[0] + TOWN_STEP * Math.cos(ang), pt[1] + TOWN_STEP * Math.sin(ang)});
                queue.add(n);
            }
        }
        // regions reachable from no hub at all: drop them in a row well below everything
        int orphan = 0;
        for (int r : new TreeSet<>(regions)) {
            if (!anchor.containsKey(r)) {
                anchor.put(r, new double[]{(orphan++ * 180) - 600, 1500});
            }
        }
        return anchor;
    }

    /** Lay a region's non-leaf maps out as a uniform-edge-length ({@link #EDGE_LEN}) tree rooted at its
     *  anchor. The root fans its children over a cone pointed in {@code outwardDir} (away from other hubs)
     *  so dead-end map sequences grow into open space instead of toward a neighbour; single-child chains
     *  then run straight. Members the tree can't reach fan around the anchor as a fallback. */
    private static void radialPlace(int region, List<Integer> members, double[] anchorIn, double[] outwardDir,
                                    Map<Integer, List<Integer>> adj, Map<Integer, Integer> regionOf,
                                    Set<Integer> leaves, Set<Integer> reachable, Map<Integer, double[]> pos) {
        double[] anchor = anchorIn != null ? anchorIn : new double[]{0, 0};
        double mag = outwardDir == null ? 0 : Math.hypot(outwardDir[0], outwardDir[1]);
        double rootAngle = mag > 1e-9 ? Math.atan2(outwardDir[1], outwardDir[0]) : -Math.PI / 2;
        double rootCone = mag > 1e-9 ? ROOT_CONE : Math.PI * 2; // surrounded hub -> spread all the way round
        ArrayDeque<double[]> queue = new ArrayDeque<>(); // {node, inAngle, sectorWidth}
        if (reachable.contains(region)) {
            pos.put(region, anchor.clone());
            queue.add(new double[]{region, rootAngle, rootCone});
        }
        while (!queue.isEmpty()) {
            double[] cur = queue.poll();
            int n = (int) cur[0];
            double inAngle = cur[1];
            double sector = cur[2];
            double[] pn = pos.get(n);
            List<Integer> kids = new ArrayList<>();
            for (int b : adj.getOrDefault(n, List.of())) {
                if (regionOf.get(b) == region && !leaves.contains(b) && !pos.containsKey(b)) {
                    kids.add(b);
                }
            }
            Collections.sort(kids);
            if (kids.isEmpty()) {
                continue;
            }
            double w = Math.min(sector, n == region ? Math.PI * 2 : CONE);
            double step = w / kids.size();
            double base = inAngle - w / 2;
            for (int i = 0; i < kids.size(); i++) {
                int c = kids.get(i);
                double ang = base + (i + 0.5) * step;
                pos.put(c, new double[]{pn[0] + EDGE_LEN * Math.cos(ang), pn[1] + EDGE_LEN * Math.sin(ang)});
                queue.add(new double[]{c, ang, step});
            }
        }
        // members the tree couldn't reach (disconnected inside the region, or town not reachable): ring them
        int ring = 0;
        List<Integer> sorted = new ArrayList<>(members);
        Collections.sort(sorted);
        for (int m : sorted) {
            if (leaves.contains(m) || pos.containsKey(m)) {
                continue;
            }
            double ang = rootAngle + ring++ * 2.399963;
            pos.put(m, new double[]{anchor[0] + EDGE_LEN * Math.cos(ang), anchor[1] + EDGE_LEN * Math.sin(ang)});
        }
    }

    /** For each anchor, a direction pointing away from the other anchors (inverse-square repulsion) so a
     *  region's dead-end chains grow into open space; near-zero when a hub sits symmetrically in the middle. */
    private static Map<Integer, double[]> outwardDirs(Map<Integer, double[]> anchor) {
        Map<Integer, double[]> out = new HashMap<>();
        for (Map.Entry<Integer, double[]> e : anchor.entrySet()) {
            double[] p = e.getValue();
            double ox = 0;
            double oy = 0;
            for (double[] q : anchor.values()) {
                double dx = p[0] - q[0];
                double dy = p[1] - q[1];
                double d2 = dx * dx + dy * dy;
                if (d2 < 1) {
                    continue;
                }
                ox += dx / d2;
                oy += dy / d2;
            }
            out.put(e.getKey(), new double[]{ox, oy});
        }
        return out;
    }

    /** Position for the i-th dead-end child of a parent: a tight 3-wide cluster just below it. */
    private static double[] childSlot(double[] parent, int i) {
        int col = i % 3;
        int row = i / 3;
        return new double[]{parent[0] + (col - 1) * 18.0, parent[1] + 24.0 + row * 16.0};
    }

    /** {@code {"nodes":[{"id":..,"name":"..","x":..,"y":..}],"edges":[[lo,hi],..]}} */
    static String graphJson(List<GNode> nodes, List<int[]> edges) {
        StringBuilder n = new StringBuilder();
        for (GNode g : nodes) {
            if (n.length() > 0) {
                n.append(',');
            }
            n.append("{\"id\":").append(g.id()).append(",\"name\":").append(jsonStr(g.name()))
                    .append(",\"x\":").append(Math.round(g.x())).append(",\"y\":").append(Math.round(g.y()))
                    .append(",\"danger\":").append(g.danger())
                    .append(",\"leaf\":").append(g.leaf())
                    .append(",\"anchor\":").append(g.anchor())
                    .append('}');
        }
        StringBuilder e = new StringBuilder();
        for (int[] ed : edges) {
            if (e.length() > 0) {
                e.append(',');
            }
            e.append('[').append(ed[0]).append(',').append(ed[1])
                    .append(",\"").append(ed[2] == 1 ? 't' : 'p').append("\"]");
        }
        return "{\"nodes\":[" + n + "],\"edges\":[" + e + "]}";
    }

    // --- live occupancy ---

    /** Every character currently online across all worlds/channels (bots included). */
    private static List<Character> onlineCharacters() {
        List<Character> out = new ArrayList<>();
        for (World w : Server.getInstance().getWorlds()) {
            for (Channel ch : Server.getInstance().getChannelsFromWorld(w.getId())) {
                out.addAll(ch.getPlayerStorage().getAllCharacters());
            }
        }
        return out;
    }

    /** {@code {"maps":{"<id>":{"players":[{n,l,j}..],"bots":[..]}}}} for maps that have anyone on them;
     *  each character carries name (n), level (l) and class/job (j). */
    static String liveJson(List<Character> online) {
        Map<Integer, List<String>> players = new TreeMap<>();
        Map<Integer, List<String>> bots = new TreeMap<>();
        for (Character chr : online) {
            Map<Integer, List<String>> bucket = chr.getClient() instanceof BotClient ? bots : players;
            bucket.computeIfAbsent(chr.getMapId(), k -> new ArrayList<>()).add(charJson(chr));
        }

        Set<Integer> maps = new TreeSet<>();
        maps.addAll(players.keySet());
        maps.addAll(bots.keySet());

        StringBuilder sb = new StringBuilder("{\"maps\":{");
        boolean first = true;
        for (int id : maps) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(id).append("\":{\"players\":").append(rawArr(players.get(id)))
                    .append(",\"bots\":").append(rawArr(bots.get(id))).append('}');
        }
        return sb.append("}}").toString();
    }

    private static String charJson(Character chr) {
        Job j = chr.getJob();
        return "{\"n\":" + jsonStr(chr.getName()) + ",\"l\":" + chr.getLevel()
                + ",\"j\":" + jsonStr(j == null ? "" : j.toString()) + "}";
    }

    // --- JSON helpers ---

    // ponytail: hand-rolled JSON, flat payloads only; reach for a lib only if the shape grows.
    /** Join already-rendered JSON objects/values into an array (vs quoting raw strings). */
    private static String rawArr(List<String> xs) {
        return xs == null || xs.isEmpty() ? "[]" : "[" + String.join(",", xs) + "]";
    }

    private static String jsonStr(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    private static Map<Integer, String> mapNames() {
        Map<Integer, String> out = new HashMap<>();
        try {
            DataProvider dp = DataProviderFactory.getDataProvider(WZFiles.STRING);
            Data mapData = dp.getData("Map.img");
            for (Data dir : mapData.getChildren()) {
                for (Data m : dir.getChildren()) {
                    String name = DataTool.getString(m.getChildByPath("mapName"), "");
                    if (name.isEmpty()) {
                        continue;
                    }
                    try {
                        out.put(Integer.parseInt(m.getName()), name);
                    } catch (NumberFormatException ignore) {
                        // non-numeric leaf, skip
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Bot world-graph: map name load failed, falling back to ids: {}", e.toString());
        }
        return out;
    }

    private static void send(HttpExchange ex, int code, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}
