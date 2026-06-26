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
import server.bots.llm.BotLlmConfig;
import server.life.LifeFactory;
import server.life.MonsterInformationProvider;
import server.life.NPC;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import server.maps.Portal;

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
import java.util.Comparator;
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
    private static final double EDGE_LEN = 72.0;   // uniform edge length for the worldmap non-anchor layout
    private static final double CONE = 2.0;        // child angular spread (radians) of the radial fan

    // Worldmap panel transforms for the /map view (worldmap id -> {x, y, scale}), captured by dragging the
    // worldmaps into a real-world arrangement and exporting. Worldmaps not listed fall back to a grid.
    private static final Map<String, double[]> WORLDMAP_LAYOUT = buildWorldMapLayout();
    private static final Object PERF_SAMPLE_LOCK = new Object();

    private static Map<String, double[]> buildWorldMapLayout() {
        Map<String, double[]> m = new HashMap<>();
        m.put("000", new double[]{-406, -517, 0.347});
        m.put("010", new double[]{323, -231, 0.997});
        m.put("011", new double[]{729, 238, 0.270});
        m.put("012", new double[]{497, -454, 0.450});
        m.put("013", new double[]{86, -468, 0.450});
        m.put("014", new double[]{182, 243, 0.469});
        m.put("020", new double[]{1945, -728, 1.206});
        m.put("021", new double[]{2768, -326, 1.161});
        m.put("030", new double[]{1964, 355, 1.003});
        m.put("031", new double[]{2144, 819, 0.450});
        m.put("040", new double[]{1962, -135, 1.004});
        m.put("050", new double[]{793, 540, 1.054});
        m.put("051", new double[]{2977, 2102, 0.450});
        m.put("060", new double[]{2695, 279, 1.075});
        m.put("070", new double[]{1623, 1045, 1.394});
        m.put("080", new double[]{317, 677, 0.731});
        m.put("090", new double[]{897, -787, 0.450});
        m.put("100", new double[]{-462, -204, 0.450});
        m.put("140", new double[]{-457, 80, 0.450});
        m.put("141", new double[]{-14, -19, 0.450});
        m.put("142", new double[]{-13, -250, 0.450});
        m.put("143", new double[]{1549, 2323, 0.450});
        m.put("144", new double[]{314, 1033, 0.721});
        m.put("145", new double[]{2959, -877, 1.092});
        m.put("146", new double[]{1890, 2346, 0.450});
        m.put("147", new double[]{2251, 2325, 0.450});
        m.put("148", new double[]{2638, 2375, 0.450});
        m.put("149", new double[]{3030, 2485, 0.450});
        m.put("150", new double[]{938, 3026, 0.450});
        m.put("151", new double[]{1275, 3012, 0.450});
        m.put("152", new double[]{1599, 3000, 0.450});
        m.put("153", new double[]{1945, 2991, 0.450});
        m.put("154", new double[]{2326, 2991, 0.450});
        m.put("155", new double[]{2698, 2984, 0.450});
        m.put("156", new double[]{3056, 2946, 0.450});
        m.put("157", new double[]{963, 3326, 0.450});
        m.put("158", new double[]{1296, 3323, 0.450});
        m.put("159", new double[]{1625, 3291, 0.450});
        m.put("160", new double[]{1961, 3257, 0.450});
        m.put("161", new double[]{2312, 3272, 0.450});
        m.put("162", new double[]{2682, 3304, 0.450});
        m.put("163", new double[]{3031, 3354, 0.450});
        return m;
    }

    private static volatile HttpServer server;

    private BotWorldGraphWebServer() {
    }

    /** Starts the view. No-op if already running; a bind failure is logged, never fatal to boot. */
    public static synchronized void start() {
        if (server != null) {
            return;
        }
        try {
            // ponytail: bound to all interfaces for LAN access (http://<server-lan-ip>:8089/). No auth —
            // exposes online player/bot names+locations to anyone on the LAN; fine on a private server LAN.
            HttpServer s = HttpServer.create(new InetSocketAddress(PORT), 0);
            // This createContext list is the SSOT for routes. When you add/change/remove a route or its
            // JSON shape, update docs/bot/web-endpoints.md (project rule).
            s.createContext("/", BotWorldGraphWebServer::servePage);
            s.createContext("/map", BotWorldGraphWebServer::serveWorldMapPage);
            s.createContext("/api/worldmaps", BotWorldGraphWebServer::serveWorldMaps);
            s.createContext("/wm/", BotWorldGraphWebServer::serveWorldMapImg);
            s.createContext("/api/live", BotWorldGraphWebServer::serveLive);
            s.createContext("/api/mapinfo", BotWorldGraphWebServer::serveMapInfo);
            s.createContext("/api/command", BotWorldGraphWebServer::serveCommand);
            s.createContext("/api/botdebug", BotWorldGraphWebServer::serveBotDebug);
            s.createContext("/api/bot/pathlog", BotWorldGraphWebServer::servePathLog);
            s.createContext("/api/perf", BotWorldGraphWebServer::servePerf);
            s.createContext("/api/spawnbot", BotWorldGraphWebServer::serveSpawnBot);
            s.createContext("/api/navprobe", BotWorldGraphWebServer::serveNavProbe);
            s.createContext("/mapgraph", BotWorldGraphWebServer::serveMapGraphPage);
            s.createContext("/api/mapgraph", BotWorldGraphWebServer::serveMapGraph);
            s.createContext("/api/pathfind", BotWorldGraphWebServer::servePathfind);
            s.createContext("/admin", BotWorldGraphWebServer::serveAdminPage);
            s.createContext("/api/settings", BotWorldGraphWebServer::serveSettings);
            s.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "bot-worldmap-web");
                t.setDaemon(true);
                return t;
            }));
            s.start();
            server = s;
            log.info("Bot world-graph web view: http://127.0.0.1:{}/ (LAN: http://<this-host-ip>:{}/)", PORT, PORT);
        } catch (IOException e) {
            log.warn("Bot world-graph web view failed to start on port {}: {}", PORT, e.toString());
        }
    }

    // --- HTTP handlers ---

    private static final String LANDING_PAGE =
            "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Bot World</title><style>"
            + "html,body{margin:0;height:100%;display:flex;flex-direction:column;align-items:center;"
            + "justify-content:center;gap:14px;background:#10141c;color:#cdd6e4;font:16px system-ui,sans-serif}"
            + "h1{font-weight:600;margin:0;color:#9fb0c8}"
            + "a{color:#6cc6ff;font-size:20px;text-decoration:none;padding:14px 24px;border:1px solid #3a4761;"
            + "border-radius:8px}a:hover{background:#171c26}</style></head>"
            + "<body><h1>Bot World</h1><a href=\"/map\">Open the World Map &rarr;</a>"
            + "<a href=\"/admin\">Admin / Settings &rarr;</a></body></html>";

    private static void servePage(HttpExchange ex) throws IOException {
        if (!"/".equals(ex.getRequestURI().getPath())) {
            send(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        send(ex, 200, "text/html; charset=utf-8", LANDING_PAGE.getBytes(StandardCharsets.UTF_8));
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

    /** Standalone per-map graph preview page ({@code mapgraph.html}); reads {@code ?id=<mapId>} client-side. */
    private static void serveMapGraphPage(HttpExchange ex) throws IOException {
        byte[] body;
        try (InputStream in = BotWorldGraphWebServer.class.getResourceAsStream("/web/mapgraph.html")) {
            if (in == null) {
                send(ex, 500, "text/plain", "mapgraph.html resource missing".getBytes(StandardCharsets.UTF_8));
                return;
            }
            body = in.readAllBytes();
        }
        send(ex, 200, "text/html; charset=utf-8", body);
    }

    // --- WorldMap.wz overlay: each numbered WorldMap img has a BaseImg + MapList of spots (in-image
    // pixel = BaseImg origin + spot) tagging real map ids. A map may legitimately appear on several
    // continent worldmaps (kept as separate dots, flagged dup). Spots are the anchors; every other
    // reachable map (non-anchor) is grown off its nearest spot, in that worldmap's local space, so it
    // rides along when the worldmap is dragged. ---

    private static volatile String worldGraphJsonCache;

    private static void serveWorldMaps(HttpExchange ex) throws IOException {
        String json = worldGraphJsonCache;
        if (json == null) {
            json = worldGraphJson();
            worldGraphJsonCache = json;
        }
        send(ex, 200, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    /** One MapList entry: a single dot that may stand for several map ids (multiple {@code mapNo}). */
    private record WorldSpot(List<Integer> maps, double x, double y) {
    }

    /** WorldMap spots in image-local pixels. A dot may stand for several maps (merged mapNo); merging is
     *  per-worldmap-entry (NOT global), so a map can be merged on an overview yet separate on its detail.
     *  {@code spotMaps} = every map that belongs to some entry; {@code occur} counts worldmaps per map. */
    private record WorldSpots(List<String> wmIds, Map<String, List<WorldSpot>> byWm,
                              Map<Integer, Integer> occur, Set<Integer> spotMaps) {
    }

    /** Follow parentMap links to the top-level (overview) worldmap; detail worldmaps share their parent's root. */
    private static String rootWorldMap(Map<String, String> parent, String wm) {
        String cur = wm;
        for (int i = 0; i < 32; i++) {
            String p = parent.get(cur);
            if (p == null || p.equals(cur)) {
                break;
            }
            cur = p;
        }
        return cur;
    }

    private static WorldSpots scanWorldMapSpots() {
        DataProvider dp = DataProviderFactory.getDataProvider(WZFiles.MAP);
        List<String> wmIds = new ArrayList<>();
        Map<String, List<WorldSpot>> byWm = new TreeMap<>();
        Map<Integer, Integer> occur = new HashMap<>(); // mapId -> # of distinct ROOT worldmaps showing it
        Map<String, String> wmParent = new HashMap<>(); // child worldmap id -> parent (overview) worldmap id
        Map<String, Set<Integer>> wmMaps = new HashMap<>(); // worldmap id -> the maps it shows
        for (String id : worldMapIds()) {
            Data wm = dp.getData("WorldMap/WorldMap" + id + ".img");
            if (wm == null) {
                continue;
            }
            Point origin = DataTool.getPoint("BaseImg/0/origin", wm, new Point(0, 0)); // canvas is named "0"
            Data mapList = wm.getChildByPath("MapList");
            if (mapList == null) {
                continue;
            }
            String pm = DataTool.getString("parentMap", wm, null); // detail worldmaps point at their overview
            if (pm != null && pm.startsWith("WorldMap")) {
                wmParent.put(id, pm.substring("WorldMap".length()));
            }
            List<WorldSpot> spots = new ArrayList<>();
            Set<Integer> primaries = new HashSet<>();  // dedup repeated entries within this worldmap
            Set<Integer> onThisWm = new HashSet<>();    // distinct maps on this worldmap
            for (Data entry : mapList.getChildren()) {
                Point spot = DataTool.getPoint("spot", entry, null);
                Data mapNo = entry.getChildByPath("mapNo");
                if (spot == null || mapNo == null) {
                    continue;
                }
                List<Integer> maps = new ArrayList<>();
                for (Data mn : mapNo.getChildren()) {
                    int mapId = DataTool.getInt(mn, -1);
                    if (mapId >= 0 && !maps.contains(mapId)) {
                        maps.add(mapId);
                    }
                }
                if (maps.isEmpty() || !primaries.add(maps.get(0))) {
                    continue;
                }
                spots.add(new WorldSpot(List.copyOf(maps), origin.x + spot.x, origin.y + spot.y));
                onThisWm.addAll(maps);
            }
            wmIds.add(id);
            byWm.put(id, spots);
            wmMaps.put(id, onThisWm);
        }
        // dup = a map shown on more than one INDEPENDENT worldmap; an overview and its zoomed detail
        // (linked by parentMap) collapse to a single root, so they don't count as duplicates.
        Map<Integer, Set<String>> rootsByMap = new HashMap<>();
        for (Map.Entry<String, Set<Integer>> e : wmMaps.entrySet()) {
            String root = rootWorldMap(wmParent, e.getKey());
            for (int m : e.getValue()) {
                rootsByMap.computeIfAbsent(m, k -> new HashSet<>()).add(root);
            }
        }
        for (Map.Entry<Integer, Set<String>> e : rootsByMap.entrySet()) {
            occur.put(e.getKey(), e.getValue().size());
        }
        Set<Integer> spotMaps = new HashSet<>();
        for (Set<Integer> ms : wmMaps.values()) {
            spotMaps.addAll(ms);
        }
        return new WorldSpots(wmIds, byWm, occur, spotMaps);
    }

    /**
     * {@code {"worldmaps":[{"id":"000","x":,"y":,"scale":,"nodes":[{id,maps,names,x,y,anchor,hub,dup,danger,
     * leaf,unreachable}]}],"edges":[[mapA,mapB,"p"|"t"]]}} — node x/y are image-local pixels; a node may
     * stand for several maps (merged {@code mapNo}). Edges are raw map ids; the client connects, per
     * worldmap, the dot holding each endpoint (so a detail keeps edges its overview merges into one dot).
     */
    private static String worldGraphJson() {
        GraphData g = graphData();
        WorldSpots ws = scanWorldMapSpots();
        Map<Integer, double[]> naLocal = new HashMap<>(); // non-anchor map -> image-local pos
        Map<Integer, String> naWm = new HashMap<>();       // non-anchor map -> owning worldmap
        worldMapLayout(g, ws, naLocal, naWm);

        Map<String, List<Integer>> naByWm = new HashMap<>();
        for (Map.Entry<Integer, String> e : naWm.entrySet()) {
            naByWm.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        for (List<Integer> l : naByWm.values()) {
            Collections.sort(l);
        }

        int cols = (int) Math.ceil(Math.sqrt(Math.max(1, ws.wmIds().size())));
        List<String> wmsOut = new ArrayList<>();
        int gi = 0; // grid index for worldmaps not in the baked layout
        for (String wm : ws.wmIds()) {
            List<WorldSpot> spots = ws.byWm().get(wm);
            if (spots == null) {
                continue;
            }
            double[] t = WORLDMAP_LAYOUT.get(wm);
            double tx = t != null ? t[0] : (gi % cols) * 480.0;
            double ty = t != null ? t[1] : (gi / cols) * 480.0;
            double ts = t != null ? t[2] : 0.45;
            gi++;
            StringBuilder nodes = new StringBuilder();
            for (WorldSpot s : spots) {
                boolean dup = ws.occur().getOrDefault(s.maps().get(0), 1) > 1;
                appendWorldNode(nodes, g, s.maps(), s.x(), s.y(), true, dup);
            }
            for (int m : naByWm.getOrDefault(wm, List.of())) {
                double[] p = naLocal.get(m);
                appendWorldNode(nodes, g, List.of(m), p[0], p[1], false, false);
            }
            wmsOut.add("{\"id\":\"" + wm + "\",\"x\":" + Math.round(tx) + ",\"y\":" + Math.round(ty)
                    + ",\"scale\":" + ts + ",\"nodes\":[" + nodes + "]}");
        }
        // Raw map-id edges over every source (entry maps + non-anchors) from the full portal graph —
        // including unreachable worldmap spots. NOT collapsed by merge: the client connects, per worldmap,
        // the dot holding each endpoint, so a detail worldmap keeps edges its overview merges into one dot.
        BotWorldGraph.Index idx = BotWorldGraph.get();
        Set<Integer> rendered = new HashSet<>(ws.spotMaps());
        rendered.addAll(naLocal.keySet());
        Set<Long> seen = new HashSet<>();
        StringBuilder es = new StringBuilder();
        for (int a : rendered) {                           // portals first (type p = walkable)
            for (int b : idx.neighbors(a)) {
                appendWorldEdge(es, a, b, 'p', rendered, seen);
            }
        }
        for (int a : rendered) {                           // taxi/ferry NPC rides (type t)
            for (BotWorldGraph.TaxiEdge t : BotWorldGraph.taxiEdgesFrom(a)) {
                appendWorldEdge(es, a, t.toMapId(), 't', rendered, seen);
            }
            for (BotFerryManager.FerryRoute f : BotFerryManager.routesBoardingAt(a)) {
                appendWorldEdge(es, a, f.destinationMapId(), 't', rendered, seen);
            }
        }
        return "{\"worldmaps\":[" + String.join(",", wmsOut) + "],\"edges\":[" + es + "]}";
    }

    private static void appendWorldEdge(StringBuilder es, int a, int b, char type,
                                        Set<Integer> rendered, Set<Long> seen) {
        if (a == b || !rendered.contains(a) || !rendered.contains(b)) {
            return;
        }
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        if (!seen.add(((long) lo << 32) | (hi & 0xFFFFFFFFL))) {
            return;
        }
        if (es.length() > 0) {
            es.append(',');
        }
        es.append('[').append(lo).append(',').append(hi).append(",\"").append(type).append("\"]");
    }

    /** Emit one node for a (possibly merged) set of maps; flags aggregate over the constituents
     *  (hub/danger/leaf if ANY is; unreachable only if NONE is reachable). */
    private static void appendWorldNode(StringBuilder sb, GraphData g, List<Integer> maps,
                                        double x, double y, boolean anchor, boolean dup) {
        boolean hub = false;
        boolean danger = false;
        boolean leaf = false;
        boolean reachable = false;
        StringBuilder mapsArr = new StringBuilder();
        StringBuilder names = new StringBuilder();
        for (int m : maps) {
            hub |= isHub(g, m);
            danger |= g.danger().contains(m);
            leaf |= g.leaves().contains(m);
            reachable |= g.reachable().contains(m);
            if (mapsArr.length() > 0) {
                mapsArr.append(',');
                names.append(',');
            }
            mapsArr.append(m);
            names.append(jsonStr(g.names().getOrDefault(m, String.valueOf(m))));
        }
        if (sb.length() > 0) {
            sb.append(',');
        }
        sb.append("{\"id\":").append(maps.get(0))
                .append(",\"maps\":[").append(mapsArr).append(']')
                .append(",\"names\":[").append(names).append(']')
                .append(",\"x\":").append(Math.round(x))
                .append(",\"y\":").append(Math.round(y))
                .append(",\"anchor\":").append(anchor)
                .append(",\"hub\":").append(hub)
                .append(",\"dup\":").append(dup)
                .append(",\"danger\":").append(danger)
                .append(",\"leaf\":").append(leaf)
                .append(",\"unreachable\":").append(!reachable)
                .append('}');
    }

    /** A hub = a return-map root that some OTHER reachable map returns to (a town maps cluster on), as
     *  opposed to a map that merely returns to itself. Only hubs get the purple ring. */
    private static boolean isHub(GraphData g, int map) {
        List<Integer> members = g.regionMembers().get(map);
        if (members == null) {
            return false;
        }
        for (int m : members) {
            if (m != map) {
                return true;
            }
        }
        return false;
    }

    /**
     * Multi-source BFS in each worldmap's local pixel space: every non-anchor (reachable map that is not
     * a worldmap spot) is grown {@link #EDGE_LEN} out from its nearest spot — so dungeon chains trail off
     * their town — and assigned to that spot's worldmap so it rides along on drag. Leaves tuck under their
     * parent via {@link #childSlot}. Maps no spot can reach stack below the first worldmap.
     */
    private static void worldMapLayout(GraphData g, WorldSpots ws,
                                       Map<Integer, double[]> naLocal, Map<Integer, String> naWm) {
        List<String> wmIds = ws.wmIds();
        Map<String, double[]> centroid = new HashMap<>(); // worldmap spot centroid -> outward direction
        for (String wm : wmIds) {
            List<WorldSpot> sp = ws.byWm().get(wm);
            if (sp == null || sp.isEmpty()) {
                continue;
            }
            double sx = 0;
            double sy = 0;
            for (WorldSpot s : sp) {
                sx += s.x();
                sy += s.y();
            }
            centroid.put(wm, new double[]{sx / sp.size(), sy / sp.size()});
        }
        Set<Integer> spots = ws.spotMaps(); // every map id that belongs to a worldmap entry
        ArrayDeque<double[]> q = new ArrayDeque<>(); // {map, wmIdx, lx, ly, inAngle, sector}
        for (int wi = 0; wi < wmIds.size(); wi++) {
            List<WorldSpot> sp = ws.byWm().get(wmIds.get(wi));
            if (sp == null) {
                continue;
            }
            double[] c = centroid.getOrDefault(wmIds.get(wi), new double[]{0, 0});
            for (WorldSpot s : sp) {
                double ang = Math.atan2(s.y() - c[1], s.x() - c[0]);
                if (!Double.isFinite(ang)) {
                    ang = -Math.PI / 2;
                }
                for (int m : s.maps()) {
                    q.add(new double[]{m, wi, s.x(), s.y(), ang, Math.PI}); // root fans a half-circle outward
                }
            }
        }
        Map<Integer, Integer> leafCount = new HashMap<>(); // per-parent dead-end slot index
        while (!q.isEmpty()) {
            double[] cur = q.poll();
            int n = (int) cur[0];
            int wi = (int) cur[1];
            double lx = cur[2];
            double ly = cur[3];
            double inAngle = cur[4];
            double sector = cur[5];
            List<Integer> kids = new ArrayList<>();
            for (int b : g.adj().getOrDefault(n, List.of())) {
                if (!spots.contains(b) && !naLocal.containsKey(b)) {
                    kids.add(b);
                }
            }
            if (kids.isEmpty()) {
                continue;
            }
            Collections.sort(kids);
            String wm = wmIds.get(wi);
            double w = Math.min(sector, CONE);
            double step = w / kids.size();
            double base = inAngle - w / 2;
            for (int i = 0; i < kids.size(); i++) {
                int c = kids.get(i);
                if (naLocal.containsKey(c)) {
                    continue; // claimed by a sibling already placed this pop
                }
                if (g.leaves().contains(c)) {
                    double[] cp = childSlot(new double[]{lx, ly}, leafCount.merge(n, 1, Integer::sum) - 1);
                    naLocal.put(c, cp);
                    naWm.put(c, wm);
                } else {
                    double ang = base + (i + 0.5) * step;
                    double[] cp = new double[]{lx + EDGE_LEN * Math.cos(ang), ly + EDGE_LEN * Math.sin(ang)};
                    naLocal.put(c, cp);
                    naWm.put(c, wm);
                    q.add(new double[]{c, wi, cp[0], cp[1], ang, step});
                }
            }
        }
        if (!wmIds.isEmpty()) { // orphans: reachable but no spot reached them — stack below worldmap 0
            String wm0 = wmIds.get(0);
            List<Integer> orphans = new ArrayList<>();
            for (int m : g.reachable()) {
                if (!spots.contains(m) && !naLocal.containsKey(m)) {
                    orphans.add(m);
                }
            }
            Collections.sort(orphans);
            int oi = 0;
            for (int m : orphans) {
                naLocal.put(m, new double[]{(oi % 20) * 40, 1200 + (oi / 20) * 40});
                naWm.put(m, wm0);
                oi++;
            }
            if (!orphans.isEmpty()) {
                log.info("Bot world-graph web view: {} non-anchor maps unreached by any worldmap spot", orphans.size());
            }
        }
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

    private static volatile String liveJsonCache;
    private static volatile long liveJsonAt;

    private static void serveLive(HttpExchange ex) throws IOException {
        // ponytail: 750ms cache coalesces concurrent viewers (the only per-2s-per-client endpoint); still
        // fresher than the 2s client poll. One enumeration of all online chars per window, not per request.
        long now = System.currentTimeMillis();
        String json = liveJsonCache;
        if (json == null || now - liveJsonAt > 750) {
            json = liveJson(onlineCharacters());
            liveJsonCache = json;
            liveJsonAt = now;
        }
        send(ex, 200, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    /** Read-only per-bot autopilot internals for live debugging — party/crew cohesion, follow state, and
     *  travel target for every online bot. Lets an operator (or an agent, via plain HTTP) see WHY bots
     *  scatter: owner (null/self/human), apParty, dst (travel target), errand, grinding, follow/transit,
     *  straggler-wait, operator override. No cache — low-frequency introspection, not a client poll. */
    /** Bring offline bot character(s) back online as managed self-owned autopilot bots —
     *  {@code ?id=<charId>[,<charId>...]}. Reuses {@link BotManager#spawnManagedBot(int)} (the same path
     *  {@code BotScheduler} uses), so it respawns at the character's saved map/position with its real
     *  level/job/skills/stats. Pair with {@code /api/command moveto x y} to drop it on an exact spot for a
     *  controlled repro (e.g. a lv10 mage next to a snail). For a specific test condition, respawn a
     *  character that already has the build you want — this does not synthesize stats. LAN debug only. */
    private static void serveSpawnBot(HttpExchange ex) throws IOException {
        String raw = queryParams(ex.getRequestURI().getRawQuery()).getOrDefault("id", "").trim();
        if (raw.isEmpty()) {
            send(ex, 400, "application/json", "{\"error\":\"need ?id=<charId>[,<charId>...]\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        BotManager mgr = BotManager.getInstance();
        StringBuilder sb = new StringBuilder("{\"results\":[");
        boolean first = true;
        for (String tok : raw.split(",")) {
            int id;
            try {
                id = Integer.parseInt(tok.trim());
            } catch (NumberFormatException e) {
                continue;
            }
            boolean spawned = mgr.spawnManagedBot(id);
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"id\":").append(id).append(",\"spawned\":").append(spawned)
                    .append(spawned ? "" : ",\"note\":\"already online or load failed\"").append('}');
        }
        send(ex, 200, "application/json", sb.append("]}").toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void serveAdminPage(HttpExchange ex) throws IOException {
        byte[] body;
        try (InputStream in = BotWorldGraphWebServer.class.getResourceAsStream("/web/admin.html")) {
            if (in == null) {
                send(ex, 500, "text/plain", "admin.html resource missing".getBytes(StandardCharsets.UTF_8));
                return;
            }
            body = in.readAllBytes();
        }
        send(ex, 200, "text/html; charset=utf-8", body);
    }

    /**
     * Admin settings menu API. GET = snapshot of every tunable group; POST = mutate one knob.
     * GET shape: {@code {"manager":[{name,value,type}],"combat":[...],"pop":{enabled,multiplier,status:[...]},
     * "llm":{enabled,debug}}}. POST dispatches on {@code cmd}: {@code set}{group,field,value} |
     * {@code pop}{mult?,enabled?,sweep?} | {@code llm}{enabled?,debug?} | {@code disconnectAll}{confirm:"DISCONNECT"}
     * | {@code wipe}{confirm:"WIPE"}. Reuses the same reflection ({@link BotConfigReflect}) as {@code !botcfg}
     * and the {@link BotScheduler}/{@link BotAdminOps} the {@code @botpop} command drives — SSOT, no second copy.
     */
    private static void serveSettings(HttpExchange ex) throws IOException {
        if ("GET".equals(ex.getRequestMethod())) {
            send(ex, 200, "application/json", settingsJson().getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (!"POST".equals(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"GET or POST\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String cmd = jsonField(body, "cmd");
        String result;
        switch (cmd == null ? "" : cmd) {
            case "set" -> {
                Object cfg = configGroup(jsonField(body, "group"));
                if (cfg == null) {
                    result = "{\"error\":\"unknown group (manager|combat)\"}";
                } else {
                    String msg = BotConfigReflect.setField(cfg, jsonField(body, "field"), jsonField(body, "value"));
                    boolean ok = msg.startsWith("OK");
                    result = "{\"ok\":" + ok + ",\"msg\":" + jsonStr(msg) + "}";
                }
            }
            case "pop" -> {
                BotScheduler sched = BotScheduler.getInstance();
                String mult = jsonField(body, "mult");
                if (mult != null) {
                    try {
                        sched.setMultiplier(Double.parseDouble(mult));
                    } catch (NumberFormatException e) {
                        result = "{\"error\":\"bad multiplier\"}";
                        break;
                    }
                }
                String enabled = jsonField(body, "enabled");
                if (enabled != null) {
                    sched.setEnabled(enabled.equalsIgnoreCase("true") || enabled.equals("1"));
                }
                if ("true".equalsIgnoreCase(jsonField(body, "sweep"))) {
                    sched.sweepNow();
                }
                result = "{\"ok\":true,\"status\":" + jsonStrArr(sched.statusLines()) + "}";
            }
            case "llm" -> {
                String enabled = jsonField(body, "enabled");
                String debug = jsonField(body, "debug");
                if (enabled != null) {
                    BotLlmConfig.enabled = enabled.equalsIgnoreCase("true") || enabled.equals("1");
                }
                if (debug != null) {
                    BotLlmConfig.debugLog = debug.equalsIgnoreCase("true") || debug.equals("1");
                    if (BotLlmConfig.debugLog) {
                        BotLlmConfig.enabled = true; // debug implies on, like !botllm debug
                    }
                }
                result = "{\"ok\":true,\"enabled\":" + BotLlmConfig.enabled
                        + ",\"debug\":" + BotLlmConfig.debugLog + "}";
            }
            case "disconnectAll" -> {
                if (!"DISCONNECT".equals(jsonField(body, "confirm"))) {
                    result = "{\"error\":\"confirm token mismatch\"}";
                } else {
                    int n = BotManager.getInstance().disconnectAllBots();
                    result = "{\"ok\":true,\"disconnected\":" + n + "}";
                }
            }
            case "wipe" -> {
                if (!"WIPE".equals(jsonField(body, "confirm"))) {
                    result = "{\"error\":\"confirm token mismatch\"}";
                } else {
                    BotAdminOps.WipeResult r = BotAdminOps.wipeManagedBots();
                    List<String> lines = new ArrayList<>();
                    for (String l : r.lines()) {
                        lines.add(jsonStr(l));
                    }
                    result = "{\"ok\":true,\"wiped\":" + r.wiped() + ",\"skipped\":" + r.skipped()
                            + ",\"lines\":" + rawArr(lines) + "}";
                }
            }
            default -> result = "{\"error\":\"unknown cmd\"}";
        }
        send(ex, 200, "application/json", result.getBytes(StandardCharsets.UTF_8));
    }

    /** The live config instance for a settings group name, or null if unknown. */
    private static Object configGroup(String group) {
        if ("manager".equalsIgnoreCase(group)) {
            return BotManager.cfg;
        }
        if ("combat".equalsIgnoreCase(group)) {
            return BotCombatManager.config();
        }
        return null;
    }

    private static String settingsJson() {
        BotScheduler sched = BotScheduler.getInstance();
        return "{\"manager\":" + fieldsJson(BotManager.cfg)
                + ",\"combat\":" + fieldsJson(BotCombatManager.config())
                + ",\"pop\":{\"enabled\":" + BotManager.cfg.POPULATION_SCHED_ENABLED
                + ",\"multiplier\":" + sched.getMultiplier()
                + ",\"status\":" + jsonStrArr(sched.statusLines()) + "}"
                + ",\"llm\":{\"enabled\":" + BotLlmConfig.enabled
                + ",\"debug\":" + BotLlmConfig.debugLog + "}}";
    }

    private static String fieldsJson(Object cfg) {
        List<String> rows = new ArrayList<>();
        for (BotConfigReflect.FieldView f : BotConfigReflect.fields(cfg)) {
            rows.add("{\"name\":" + jsonStr(f.name()) + ",\"value\":" + jsonStr(f.value())
                    + ",\"type\":" + jsonStr(f.type()) + "}");
        }
        return rawArr(rows);
    }

    private static String jsonStrArr(List<String> xs) {
        List<String> q = new ArrayList<>();
        for (String x : xs) {
            q.add(jsonStr(x));
        }
        return rawArr(q);
    }

    /** Pathfinding probe: run the bot's own nav planner from its current position to an arbitrary point
     *  on its current map and report the result — {@code ?id=<botCharId>&x=<>&y=<>}. Shows the start/target
     *  regions, whether the target sits on ground / a rope / midair, whether it's reachable, and the full
     *  edge path (WALK/CLIMB/JUMP/DROP with endpoints). The "why can't the bot get there" companion to
     *  {@code /api/bot/pathlog} (which shows what it's doing live) — answers it for a hypothetical target
     *  (e.g. a portal's approach point) without having to drive the bot there. LAN debug only. */
    private static void serveNavProbe(HttpExchange ex) throws IOException {
        var q = queryParams(ex.getRequestURI().getRawQuery());
        int id;
        int x;
        int y;
        try {
            id = Integer.parseInt(q.getOrDefault("id", "").trim());
            x = Integer.parseInt(q.getOrDefault("x", "").trim());
            y = Integer.parseInt(q.getOrDefault("y", "").trim());
        } catch (NumberFormatException e) {
            send(ex, 400, "application/json", "{\"error\":\"need ?id=<botCharId>&x=<>&y=<>\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        BotEntry e = lookupBotEntry(id);
        if (e == null || e.bot == null || e.bot.getMap() == null) {
            send(ex, 404, "application/json", "{\"error\":\"no such online bot\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        var bot = e.bot;
        var map = bot.getMap();
        var botPos = bot.getPosition();
        var target = new java.awt.Point(x, y);
        StringBuilder sb = new StringBuilder("{\"bot\":").append(jsonStr(bot.getName()))
                .append(",\"map\":").append(map.getId())
                .append(",\"from\":[").append(botPos.x).append(',').append(botPos.y).append("]")
                .append(",\"to\":[").append(x).append(',').append(y).append("]");
        var graph = BotNavigationGraphProvider.getGraph(map, e.movementProfile);
        if (graph == null) {
            send(ex, 200, "application/json", sb.append(",\"error\":\"graph warming — retry shortly\"}")
                    .toString().getBytes(StandardCharsets.UTF_8));
            return;
        }
        int fromRegion = BotNavigationManager.resolveCurrentRegionId(graph, e, map, botPos);
        int toRegion = BotNavigationManager.resolveTargetRegionId(graph, e, map, target);
        var ground = BotPhysicsEngine.findGroundPoint(map, new java.awt.Point(x, y - 1));
        boolean onRope = BotPhysicsEngine.climbableAtPoint(map, target) != null;
        // skills=1 runs the skill-enabled planner so the path shows TELEPORT/FLASH_JUMP edges the bot is
        // eligible for (probe a teleport mage / flash-jump hermit). Default stays walk-only.
        boolean skills = "1".equals(q.get("skills")) || "true".equalsIgnoreCase(q.getOrDefault("skills", ""));
        var path = skills
                ? BotNavigationManager.findPathWithSkills(graph, bot, fromRegion, toRegion, target)
                : BotNavigationManager.findPath(graph, bot, fromRegion, toRegion, target);
        sb.append(",\"fromRegion\":").append(fromRegion)
                .append(",\"toRegion\":").append(toRegion)
                .append(",\"targetGroundY\":").append(ground != null ? ground.y : -1)
                .append(",\"targetOnRope\":").append(onRope)
                .append(",\"skills\":").append(skills)
                .append(",\"reachable\":").append(path != null)
                .append(",\"hops\":").append(path != null ? path.size() : 0)
                .append(",\"path\":[");
        if (path != null) {
            for (int i = 0; i < path.size(); i++) {
                var edge = path.get(i);
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"type\":").append(jsonStr(edge.type.name()))
                        .append(",\"fromR\":").append(edge.fromRegionId)
                        .append(",\"toR\":").append(edge.toRegionId)
                        .append(",\"from\":[").append(edge.startPoint.x).append(',').append(edge.startPoint.y).append("]")
                        .append(",\"to\":[").append(edge.endPoint.x).append(',').append(edge.endPoint.y).append("]}");
            }
        }
        send(ex, 200, "application/json", sb.append("]}").toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Region-to-region pathfind for the {@code /mapgraph} UI (select source region, select target region,
     * preview the route). Pathfinds on the same cached movement-profile graph the page renders (?sp/jmp/snow).
     *
     * <p>Honest reachability, unlike {@code /api/navprobe} whose {@code reachable} is just {@code path!=null}
     * (the redirecting "committed" search returns a best-effort partial even for an unreachable target, so it
     * always looks reachable). Here {@code canReach} is the directed reachability index and the search runs
     * STRICT (non-"committed", so NO redirect): {@code reached} is true only if a real path lands in the
     * target region. {@code canReach && !reached} means the A* edge-check cap gave up; {@code !canReach} means
     * a genuine graph gap, and {@code redirect} is where the live bot's best-effort would orbit instead.
     */
    private static void servePathfind(HttpExchange ex) throws IOException {
        var q = queryParams(ex.getRequestURI().getRawQuery());
        int mapId;
        int from;
        int to;
        try {
            mapId = Integer.parseInt(q.getOrDefault("id", "").trim());
            from = Integer.parseInt(q.getOrDefault("from", "").trim());
            to = Integer.parseInt(q.getOrDefault("to", "").trim());
        } catch (NumberFormatException e) {
            send(ex, 400, "application/json", "{\"error\":\"need ?id=<mapId>&from=<regionId>&to=<regionId>\"}"
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }
        MapleMap map = loadMap(mapId);
        if (map == null) {
            send(ex, 404, "application/json", "{\"error\":\"no such map\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        BotMovementProfile profile = parseProfile(q);
        BotNavigationGraph g = BotNavigationGraphProvider.getGraph(map, profile);
        if (g == null) {
            send(ex, 200, "application/json", "{\"error\":\"graph warming — retry shortly\"}"
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }
        BotNavigationGraph.Region fr = g.getRegion(from);
        BotNavigationGraph.Region tr = g.getRegion(to);
        if (fr == null || tr == null) {
            send(ex, 400, "application/json", "{\"error\":\"unknown region id (from/to not in this graph)\"}"
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }
        // Walk-only on the selected profile graph: the region tool has no live bot, so skill-edge
        // eligibility (teleport/flash-jump) can't be decided — probe those with /api/navprobe&skills=1.
        java.awt.Point fp = fr.centerPoint();
        java.awt.Point tp = tr.centerPoint();
        boolean exhaustive = "exhaustive".equalsIgnoreCase(q.getOrDefault("mode", ""))
                || "1".equals(q.get("exhaustive"));
        boolean canReach = g.canReach(from, to, 0);
        // SAME search the live bot runs (SSOT — no parallel pathfinder), two ways:
        //  normal     = "committed" (the live executor's redirecting best-effort) + bounded budget. On an
        //               unreachable/too-far target it walks AS CLOSE AS POSSIBLE; exploredSink captures the
        //               frontier it checked so the UI can paint it when the result is best-effort.
        //  exhaustive = strict ("webpathfind", no redirect) + UNBOUNDED budget: exhausts the graph so an
        //               empty path is a definitive "no route". canReach (a full directed BFS) is itself the
        //               exhaustive proof of unreachability, reported alongside.
        String caller = exhaustive ? "webpathfind" : "committed";
        int budget = exhaustive ? Integer.MAX_VALUE : BotNavigationManager.MAX_EDGE_CHECKS;
        List<BotNavigationGraph.Edge> explored = new java.util.ArrayList<>();
        List<BotNavigationGraph.Edge> path = BotNavigationManager.runSearch(
                g, map, fp, from, to, tp, caller, true, false, 0L, false, null, budget, explored).path();
        boolean reached = from == to
                || (!path.isEmpty() && path.get(path.size() - 1).toRegionId == to);
        // Best-effort = produced a path but didn't actually land in the target region (redirected/capped).
        boolean bestEffort = !reached && !path.isEmpty();
        int redirect = reached ? -1
                : (path.isEmpty() ? g.nearestReachableRegion(from, 0, tp) : path.get(path.size() - 1).toRegionId);
        StringBuilder sb = new StringBuilder("{\"map\":").append(mapId)
                .append(",\"from\":").append(from).append(",\"to\":").append(to)
                .append(",\"profile\":").append(profileJson(g.movementProfile))
                .append(",\"mode\":").append(exhaustive ? "\"exhaustive\"" : "\"normal\"")
                .append(",\"canReach\":").append(canReach)
                .append(",\"reached\":").append(reached)
                .append(",\"bestEffort\":").append(bestEffort)
                .append(",\"hops\":").append(path.size())
                .append(",\"redirect\":").append(redirect)
                .append(",\"path\":[");
        appendEdgesJson(sb, path);
        // Explored frontier: only meaningful for a best-effort result (show what was checked before giving
        // up). Omit on a clean reach to keep the payload small.
        sb.append("],\"explored\":[");
        if (bestEffort) {
            // A* re-pops regions, so the same edge object lands in the sink many times; collapse to the
            // DISTINCT edges checked (identity = from/to/type + endpoints, so genuine parallel launch-x
            // variants stay separate — those are the redundant rope/jump edges worth seeing).
            java.util.LinkedHashMap<String, BotNavigationGraph.Edge> distinct = new java.util.LinkedHashMap<>();
            for (BotNavigationGraph.Edge e : explored) {
                distinct.putIfAbsent(e.fromRegionId + "_" + e.toRegionId + "_" + e.type.ordinal()
                        + "_" + e.startPoint.x + "_" + e.startPoint.y + "_" + e.endPoint.x + "_" + e.endPoint.y, e);
            }
            appendEdgesJson(sb, new java.util.ArrayList<>(distinct.values()));
        }
        send(ex, 200, "application/json", sb.append("]}").toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Appends nav edges as a JSON array body (no enclosing brackets) — shared by path + explored lists. */
    private static void appendEdgesJson(StringBuilder sb, List<BotNavigationGraph.Edge> edges) {
        for (int i = 0; i < edges.size(); i++) {
            var edge = edges.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"type\":").append(jsonStr(edge.type.name()))
                    .append(",\"fromR\":").append(edge.fromRegionId)
                    .append(",\"toR\":").append(edge.toRegionId)
                    .append(",\"cost\":").append(edge.cost)
                    .append(",\"lsx\":").append(edge.launchStepX)
                    .append(",\"from\":[").append(edge.startPoint.x).append(',').append(edge.startPoint.y).append("]")
                    .append(",\"to\":[").append(edge.endPoint.x).append(',').append(edge.endPoint.y).append("]}");
        }
    }

    private static void serveBotDebug(HttpExchange ex) throws IOException {
        int filterId = 0;
        try {
            filterId = Integer.parseInt(queryParams(ex.getRequestURI().getRawQuery()).getOrDefault("id", "0").trim());
        } catch (NumberFormatException ignore) { /* 0 = all bots */ }
        send(ex, 200, "application/json", botDebugJson(filterId).getBytes(StandardCharsets.UTF_8));
    }

    /** {@code ?id=<botCharId>} filters to one bot AND adds a {@code "detail"} block with live stats +
     *  learned skills (read straight off the {@link Character}, the SSOT — never stale like the DB). The
     *  cheap combat-readiness fields ({@code atk}/{@code aoe}/{@code wt}/{@code noAmmo}) are on every row so
     *  "can this bot even attack" is visible in the list view (atk=0 => no offensive skill => basic swing). */
    private static String botDebugJson(int filterId) {
        StringBuilder sb = new StringBuilder("{\"bots\":[");
        boolean first = true;
        for (Character chr : onlineCharacters()) {
            if (!(chr.getClient() instanceof BotClient)) {
                continue;
            }
            if (filterId > 0 && chr.getId() != filterId) {
                continue;
            }
            BotEntry e = lookupBotEntry(chr.getId());
            if (e == null) {
                continue;
            }
            String owner = e.owner == null ? "null" : (e.owner == e.bot ? "self" : e.owner.getName());
            var wt = BotAttackExecutionProvider.getEquippedWeaponType(chr);
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"id\":").append(chr.getId())
                    .append(",\"n\":").append(jsonStr(chr.getName()))
                    .append(",\"map\":").append(chr.getMapId())
                    .append(",\"lvl\":").append(chr.getLevel())
                    .append(",\"party\":").append(Math.max(0, chr.getPartyId()))
                    .append(",\"crew\":").append(e.crewGroupId != null ? e.crewGroupId : 0)
                    .append(",\"owner\":").append(jsonStr(owner))
                    .append(",\"apParty\":").append(e.autopilotParty)
                    .append(",\"dst\":").append(e.autopilotMapId)
                    .append(",\"errand\":").append(e.autopilotErrandMapId)
                    .append(",\"grinding\":").append(e.grinding)
                    .append(",\"following\":").append(e.following)
                    .append(",\"followTo\":").append(e.followTargetId)
                    .append(",\"transit\":").append(e.autopilotTransitFollow)
                    .append(",\"waiting\":").append(e.autopilotWaitingForStragglers)
                    .append(",\"op\":").append(jsonStr(e.operatorCmd == null ? "" : e.operatorCmd.name()))
                    // combat-readiness (cheap, every row): atk=resolved single-target skill (0 => basic swing only)
                    .append(",\"wt\":").append(jsonStr(wt == null ? "none" : wt.name()))
                    .append(",\"atk\":").append(e.attackSkillId)
                    .append(",\"aoe\":").append(e.aoeSkillId)
                    .append(",\"noAmmo\":").append(e.noAmmo)
                    .append(",\"status\":").append(jsonStr(BotAutopilotManager.statusReport(e, chr)));
            if (filterId > 0) {
                appendBotDetail(sb, chr, e);
            }
            sb.append('}');
        }
        return sb.append("],\"routeCache\":").append(BotNavigationGraph.routeCacheStatsJson()).append("}").toString();
    }

    /** Live stats + learned skills for a single bot, read off the {@link Character} (SSOT). Use this over a
     *  DB {@code skills}/{@code characters} query — the in-memory character is authoritative and the DB row
     *  lags until the next save. {@code skills} maps skillId -> level; {@code atkSkill}/{@code aoeSkill} are
     *  the bot's resolved choices from {@link BotCombatManager#rebuildSkillCacheIfNeeded} (0 = none). */
    private static void appendBotDetail(StringBuilder sb, Character chr, BotEntry e) {
        java.awt.Point navPos = chr.getPosition();
        sb.append(",\"detail\":{")
                .append("\"job\":").append(chr.getJob().getId())
                .append(",\"str\":").append(chr.getTotalStr())
                .append(",\"dex\":").append(chr.getTotalDex())
                .append(",\"int\":").append(chr.getTotalInt())
                .append(",\"luk\":").append(chr.getTotalLuk())
                .append(",\"watk\":").append(chr.getTotalWatk())
                .append(",\"matk\":").append(chr.getTotalMagic())
                .append(",\"hp\":").append(chr.getHp()).append(",\"maxHp\":").append(chr.getCurrentMaxHp())
                .append(",\"mp\":").append(chr.getMp()).append(",\"maxMp\":").append(chr.getCurrentMaxMp())
                .append(",\"exp\":").append(chr.getExp()).append(",\"meso\":").append(chr.getMeso())
                .append(",\"atkSkill\":").append(e.attackSkillId)
                .append(",\"aoeSkill\":").append(e.aoeSkillId)
                // live nav state for movement-skill debugging: position, committed edge, last decision/block,
                // and whether this bot currently passes the teleport/flash-jump gate (skill + >40% MP + >500k meso).
                .append(",\"pos\":[").append(navPos == null ? 0 : navPos.x).append(',').append(navPos == null ? 0 : navPos.y).append(']')
                .append(",\"navEdge\":").append(jsonStr(BotPathLogger.navEdgeSummary(e)))
                .append(",\"navDecision\":").append(jsonStr(e.lastNavDecision == null ? "" : e.lastNavDecision))
                .append(",\"edgeBlock\":").append(jsonStr(e.lastEdgeBlockReason == null ? "" : e.lastEdgeBlockReason))
                .append(",\"canMoveSkill\":").append(BotNavigationManager.botCanUseMovementSkill(chr))
                .append(",\"skills\":{");
        boolean firstSkill = true;
        for (var entry : chr.getSkills().entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            if (!firstSkill) {
                sb.append(',');
            }
            firstSkill = false;
            sb.append('"').append(entry.getKey().getId()).append("\":").append(entry.getValue().skillevel);
        }
        sb.append("}}");
    }

    /** Live perf snapshot from {@link BotPerformanceMonitor} (per-subsystem timings incl. "scroll-scan").
     *  {@code ?on=1} enables the monitor, {@code ?on=0} disables it; no param just reports the current
     *  aggregate. Monitoring is opt-in (off by default) — enable it, let it run, then read this to see
     *  what's hot. ponytail: stateful GET toggle, LAN debug only. */
    private static void servePerf(HttpExchange ex) throws IOException {
        var q = queryParams(ex.getRequestURI().getRawQuery());
        String on = q.get("on");
        if ("1".equals(on) || "true".equalsIgnoreCase(on)) {
            BotPerformanceMonitor.setEnabled(true);
        } else if ("0".equals(on) || "false".equalsIgnoreCase(on)) {
            BotPerformanceMonitor.setEnabled(false);
        }
        int durationMs = parseBoundedInt(q.get("durationMs"), 0, 0, 60_000);
        if (durationMs > 0) {
            send(ex, 200, "application/json", samplePerfJson(durationMs).getBytes(StandardCharsets.UTF_8));
            return;
        }
        long now = System.currentTimeMillis();
        send(ex, 200, "application/json", perfJson(BotPerformanceMonitor.snapshot(), 0, now, now, null, null)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String samplePerfJson(int durationMs) {
        synchronized (PERF_SAMPLE_LOCK) {
            boolean wasEnabled = BotPerformanceMonitor.enabled();
            long startedAtMs = System.currentTimeMillis();
            java.time.Duration cpuBefore = processCpuDuration();
            Runtime rt = Runtime.getRuntime();
            long heapUsedBefore = rt.totalMemory() - rt.freeMemory();
            BotPerformanceMonitor.setEnabled(true);
            sleepForSample(durationMs);
            List<BotPerformanceMonitor.SectionSnapshot> snap = BotPerformanceMonitor.snapshot();
            long endedAtMs = System.currentTimeMillis();
            java.time.Duration cpuAfter = processCpuDuration();
            long heapUsedAfter = rt.totalMemory() - rt.freeMemory();
            if (!wasEnabled) {
                BotPerformanceMonitor.setEnabled(false);
            }
            return perfJson(snap, Math.max(1L, endedAtMs - startedAtMs), startedAtMs, endedAtMs,
                    cpuDeltaMs(cpuBefore, cpuAfter), heapUsedAfter - heapUsedBefore);
        }
    }

    private static void sleepForSample(int durationMs) {
        try {
            Thread.sleep(durationMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static java.time.Duration processCpuDuration() {
        return ProcessHandle.current().info().totalCpuDuration().orElse(null);
    }

    private static Long cpuDeltaMs(java.time.Duration before, java.time.Duration after) {
        if (before == null || after == null) {
            return null;
        }
        return Math.max(0L, after.minus(before).toMillis());
    }

    private static String perfJson(List<BotPerformanceMonitor.SectionSnapshot> snapshots, long sampleMs,
                                   long startedAtMs, long endedAtMs, Long processCpuMs,
                                   Long heapDeltaBytes) {
        List<BotPerformanceMonitor.SectionSnapshot> sorted = new ArrayList<>(snapshots);
        sorted.sort(Comparator.comparingLong(BotPerformanceMonitor.SectionSnapshot::totalNs).reversed());
        long denomNs = 0L;
        for (BotPerformanceMonitor.SectionSnapshot s : sorted) {
            if ("tick-total".equals(s.section())) {
                denomNs = s.totalNs();
                break;
            }
        }
        if (denomNs <= 0L) {
            for (BotPerformanceMonitor.SectionSnapshot s : sorted) {
                denomNs += s.totalNs();
            }
        }
        denomNs = Math.max(1L, denomNs);
        double sampleSeconds = sampleMs > 0 ? sampleMs / 1000.0 : 0.0;
        StringBuilder sb = new StringBuilder("{\"enabled\":").append(BotPerformanceMonitor.enabled())
                .append(",\"sampleMs\":").append(sampleMs)
                .append(",\"startedAtMs\":").append(startedAtMs)
                .append(",\"endedAtMs\":").append(endedAtMs);
        if (processCpuMs != null) {
            sb.append(",\"processCpuMs\":").append(processCpuMs)
                    .append(",\"processCore\":").append(sampleSeconds > 0.0 ? processCpuMs / (sampleSeconds * 1000.0) : 0.0);
        }
        Runtime rt = Runtime.getRuntime();
        sb.append(",\"heapUsedBytes\":").append(rt.totalMemory() - rt.freeMemory())
                .append(",\"heapTotalBytes\":").append(rt.totalMemory())
                .append(",\"heapMaxBytes\":").append(rt.maxMemory());
        if (heapDeltaBytes != null) {
            sb.append(",\"heapDeltaBytes\":").append(heapDeltaBytes);
        }
        sb.append(",\"sections\":[");
        boolean first = true;
        for (BotPerformanceMonitor.SectionSnapshot s : sorted) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            double totalMs = s.totalNs() / 1_000_000.0;
            double cpuMsPerSec = sampleSeconds > 0.0 ? totalMs / sampleSeconds : 0.0;
            double callsPerSec = sampleSeconds > 0.0 ? s.count() / sampleSeconds : 0.0;
            sb.append("{\"section\":").append(jsonStr(s.section()))
                    .append(",\"count\":").append(s.count())
                    .append(",\"totalMs\":").append(totalMs)
                    .append(",\"avgMs\":").append(s.avgMs())
                    .append(",\"maxMs\":").append(s.maxMs())
                    .append(",\"cpuMsPerSec\":").append(cpuMsPerSec)
                    .append(",\"core\":").append(cpuMsPerSec / 1000.0)
                    .append(",\"callsPerSec\":").append(callsPerSec)
                    .append(",\"sharePct\":").append(100.0 * s.totalNs() / denomNs)
                    .append(",\"slow\":").append(s.slowCount())
                    .append(",\"slowAvgMs\":").append(s.slowAvgMs())
                    .append('}');
        }
        return sb.append("]}").toString();
    }

    private static int parseBoundedInt(String raw, int defaultValue, int min, int max) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** On-demand per-bot path-log toggle — mirrors the {@code !botnav pathlog} command
     *  ({@link BotNavigationDebugOverlay#pathLog}): the FIRST call attaches a 120-tick ring-buffer
     *  recorder ({@code BotEntry.pathLogger}); recording is otherwise OFF (the field is null → zero
     *  per-tick overhead). The SECOND call detaches it, dumps the trace to {@code logs/bot-nav}, and
     *  returns the report text — use briefly to capture why a bot is stuck. {@code ?id=<botCharId>}.
     *  ponytail: stateful GET is deliberate (one-click toggle, matches the command); LAN debug only. */
    private static void servePathLog(HttpExchange ex) throws IOException {
        int id;
        try {
            id = Integer.parseInt(queryParams(ex.getRequestURI().getRawQuery()).getOrDefault("id", "").trim());
        } catch (NumberFormatException e) {
            send(ex, 400, "application/json", "{\"error\":\"bad id\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        BotEntry e = lookupBotEntry(id);
        if (e == null || e.bot == null) {
            send(ex, 404, "application/json", "{\"error\":\"no such bot\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        send(ex, 200, "application/json", pathLogToggleJson(e).getBytes(StandardCharsets.UTF_8));
    }

    private static synchronized String pathLogToggleJson(BotEntry e) {
        String name = e.bot.getName();
        if (e.pathLogger == null) {
            e.pathLogger = new BotPathLogger(name, e.bot.getMapId());
            return "{\"recording\":true,\"bot\":" + jsonStr(name)
                    + ",\"msg\":\"recording started — call again to dump\"}";
        }
        BotPathLogger logger = e.pathLogger;
        e.pathLogger = null; // stop recording first (tick sees null next tick), then dump the static buffer
        BotManager.TargetSnapshot snap = BotManager.getInstance().captureTargetSnapshot(e);
        String path = logger.dumpToFile(e, snap, "via /api/bot/pathlog");
        String report;
        try {
            report = Files.readString(Path.of(path)); // dumpToFile wrote it; read back to return over HTTP
        } catch (Exception io) {
            report = path; // dumpToFile returned an error string, not a path
        }
        return "{\"recording\":false,\"bot\":" + jsonStr(name) + ",\"file\":" + jsonStr(path)
                + ",\"report\":" + jsonStr(report) + "}";
    }

    /** Per-map detail for a clicked node: the mobs that spawn there (name, level, spawn-point count) and
     *  each bot currently on the map with its @botstatus line. Computed on demand (one map per click). */
    private static void serveMapInfo(HttpExchange ex) throws IOException {
        int mapId;
        try {
            mapId = Integer.parseInt(queryParams(ex.getRequestURI().getRawQuery()).getOrDefault("id", "").trim());
        } catch (NumberFormatException e) {
            send(ex, 400, "application/json", "{\"error\":\"bad id\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        send(ex, 200, "application/json", mapInfoJson(mapId).getBytes(StandardCharsets.UTF_8));
    }

    /** One mob row: spawn-point count is the number of WZ {@code life} entries for that mob on the map
     *  (NOT live count or spawn multiplier), per {@link BotSpawnIndex.MapSpawns#mobCounts}. */
    private record MobRow(String name, int level, int spawnPoints) {
    }

    private static String mapInfoJson(int mapId) {
        StringBuilder mobs = new StringBuilder();
        BotSpawnIndex.MapSpawns sp = BotSpawnIndex.get().byMap().get(mapId);
        if (sp != null) {
            MonsterInformationProvider mi = MonsterInformationProvider.getInstance();
            List<MobRow> rows = new ArrayList<>();
            for (Map.Entry<Integer, Integer> e : sp.mobCounts().entrySet()) {
                int mobId = e.getKey();
                String name = mi.getMobNameFromId(mobId);
                if (name == null || name.isEmpty()) {
                    name = "mob " + mobId;
                }
                var mon = LifeFactory.getMonster(mobId);
                int level = mon != null ? mon.getStats().getLevel() : 0;
                rows.add(new MobRow(name, level, e.getValue()));
            }
            rows.sort((a, b) -> a.level() != b.level()
                    ? Integer.compare(a.level(), b.level()) : a.name().compareTo(b.name()));
            for (MobRow r : rows) {
                if (mobs.length() > 0) {
                    mobs.append(',');
                }
                mobs.append("{\"name\":").append(jsonStr(r.name())).append(",\"level\":").append(r.level())
                        .append(",\"spawns\":").append(r.spawnPoints()).append('}');
            }
        }
        StringBuilder bots = new StringBuilder();
        BotManager bm = BotManager.getInstance();
        for (Character chr : onlineCharacters()) {
            if (chr.getMapId() != mapId || !(chr.getClient() instanceof BotClient)) {
                continue;
            }
            String status = BotAutopilotManager.statusReport(bm.getEntryByBotCharId(chr.getId()), chr);
            if (bots.length() > 0) {
                bots.append(',');
            }
            bots.append("{\"name\":").append(jsonStr(chr.getName()))
                    .append(",\"status\":").append(jsonStr(status)).append('}');
        }
        return "{\"mobs\":[" + mobs + "],\"bots\":[" + bots + "],\"chat\":[" + chatJson(mapId) + "]}";
    }

    // --- single-map nav-graph preview (the /mapgraph page) ---

    /** Renders one map's bot nav graph + live features for the {@code /mapgraph} canvas.
     *  Optional {@code sp}/{@code jmp}/{@code snow} pick which cached movement-profile graph to show. */
    private static void serveMapGraph(HttpExchange ex) throws IOException {
        var q = queryParams(ex.getRequestURI().getRawQuery());
        int mapId;
        try {
            mapId = Integer.parseInt(q.getOrDefault("id", "").trim());
        } catch (NumberFormatException e) {
            send(ex, 400, "application/json", "{\"error\":\"bad id\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        send(ex, 200, "application/json", mapGraphJson(mapId, parseProfile(q)).getBytes(StandardCharsets.UTF_8));
    }

    /** {@code sp}/{@code jmp}/{@code snow} query params → a movement profile; default speed100/jump100 base. */
    private static BotMovementProfile parseProfile(Map<String, String> q) {
        String sp = q.get("sp");
        String jmp = q.get("jmp");
        if (sp == null || jmp == null) {
            return BotMovementProfile.base();
        }
        try {
            boolean snow = "1".equals(q.get("snow")) || "true".equalsIgnoreCase(q.getOrDefault("snow", ""));
            return new BotMovementProfile(Integer.parseInt(sp.trim()), Integer.parseInt(jmp.trim()), snow);
        } catch (NumberFormatException e) {
            return BotMovementProfile.base();
        }
    }

    private static String profileJson(BotMovementProfile p) {
        return "{\"sp\":" + p.totalSpeedStat() + ",\"jmp\":" + p.totalJumpStat() + ",\"snow\":" + p.snowShoes() + "}";
    }

    /** First loaded {@link MapleMap} instance for {@code mapId} across all worlds/channels (map factory
     *  loads it from WZ on demand — footholds/portals/NPCs included — so an empty map still resolves). */
    private static MapleMap loadMap(int mapId) {
        for (World w : Server.getInstance().getWorlds()) {
            for (Channel ch : Server.getInstance().getChannelsFromWorld(w.getId())) {
                try {
                    MapleMap m = ch.getMapFactory().getMap(mapId);
                    if (m != null) {
                        return m;
                    }
                } catch (Throwable ignore) {
                    // bad/unloadable id on this channel — try the next
                }
            }
        }
        return null;
    }

    /**
     * One map's bot {@link BotNavigationGraph} plus live features, for the {@code /mapgraph} preview:
     * <ul>
     *   <li>{@code regions}: foothold regions ({@code kind:"fh"}, their segment lines) and rope/ladder
     *       regions ({@code kind:"rope"}, a vertical span) — the "accurate node sections".</li>
     *   <li>{@code edges}: inter-region nav edges (one per from/to/type), {@code t} = WALK/JUMP/DROP/CLIMB/
     *       PORTAL/TELEPORT/FLASH_JUMP with from/to points (the client arcs JUMP, colours PORTAL).</li>
     *   <li>{@code npcs}, {@code portals} (classified {@code in}=same-map shortcut / {@code cross}=other map /
     *       {@code coll}=collision-warp type), and {@code chars} (live player+bot positions).</li>
     * </ul>
     * ponytail: loads the map + blocks on the (cached) graph build on demand — a LAN debug page, not a hot
     * path; the client re-fetches the whole payload each poll and only repaints {@code chars}.
     */
    private static String mapGraphJson(int mapId, BotMovementProfile profile) {
        MapleMap map = loadMap(mapId);
        if (map == null) {
            return "{\"error\":\"map not found / not loadable\"}";
        }
        BotNavigationGraph g = BotNavigationGraphProvider.getGraph(map, profile);
        if (g == null) {
            return "{\"error\":\"graph unavailable\"}";
        }
        long[] b = {Long.MAX_VALUE, Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE}; // minX,minY,maxX,maxY

        StringBuilder regions = new StringBuilder();
        for (BotNavigationGraph.Region r : g.regions) {
            if (regions.length() > 0) {
                regions.append(',');
            }
            if (r.isRopeRegion) {
                regions.append("{\"id\":").append(r.id).append(",\"kind\":\"rope\",\"ladder\":").append(r.isLadder)
                        .append(",\"x\":").append(r.minX).append(",\"y1\":").append(r.minY)
                        .append(",\"y2\":").append(r.maxY);
            } else {
                regions.append("{\"id\":").append(r.id).append(",\"kind\":\"fh\",\"segs\":[");
                boolean firstSeg = true;
                for (BotNavigationGraph.Segment s : r.segments) {
                    if (!firstSeg) {
                        regions.append(',');
                    }
                    firstSeg = false;
                    regions.append('[').append(s.x1).append(',').append(s.y1).append(',')
                            .append(s.x2).append(',').append(s.y2).append(']');
                }
                regions.append(']');
            }
            regions.append(",\"report\":[");      // SSOT with the !pos command (BotNavigationDebugOverlay)
            boolean firstLine = true;
            for (String line : BotNavigationDebugOverlay.describeRegion(g, r.id)) {
                if (!firstLine) {
                    regions.append(',');
                }
                firstLine = false;
                regions.append(jsonStr(line));
            }
            regions.append("]}");
            expandBounds(b, r.minX, r.minY);
            expandBounds(b, r.maxX, r.maxY);
        }

        StringBuilder edges = new StringBuilder();
        // Count parallel edges per (from,to,type) so a drawn line can report how many launch-x variants it
        // collapses — n>1 (common around ropes) is exactly why the raw explored overlay shows "more edges".
        Map<Long, Integer> parallelCount = new HashMap<>();
        for (List<BotNavigationGraph.Edge> list : g.outgoingByRegionId.values()) {
            for (BotNavigationGraph.Edge e : list) {
                if (e.fromRegionId == e.toRegionId) {
                    continue;
                }
                long key = (((long) e.fromRegionId * 1000003L + e.toRegionId) << 3) | e.type.ordinal();
                parallelCount.merge(key, 1, Integer::sum);
            }
        }
        Set<Long> edgeSeen = new HashSet<>();
        for (List<BotNavigationGraph.Edge> list : g.outgoingByRegionId.values()) {
            for (BotNavigationGraph.Edge e : list) {
                if (e.fromRegionId == e.toRegionId) {
                    continue; // intra-region walk — not a drawn hop
                }
                long key = (((long) e.fromRegionId * 1000003L + e.toRegionId) << 3) | e.type.ordinal();
                if (!edgeSeen.add(key)) {
                    continue; // collapse parallel edges (multiple launch xs) to one line per from/to/type
                }
                if (edges.length() > 0) {
                    edges.append(',');
                }
                edges.append("{\"t\":\"").append(e.type.name())
                        .append("\",\"fromR\":").append(e.fromRegionId).append(",\"toR\":").append(e.toRegionId)
                        .append(",\"cost\":").append(e.cost).append(",\"lsx\":").append(e.launchStepX)
                        .append(",\"n\":").append(parallelCount.getOrDefault(key, 1))
                        .append(",\"fx\":").append(e.startPoint.x)
                        .append(",\"fy\":").append(e.startPoint.y).append(",\"tx\":").append(e.endPoint.x)
                        .append(",\"ty\":").append(e.endPoint.y).append('}');
            }
        }

        StringBuilder npcs = new StringBuilder();
        for (MapObject o : map.getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY, List.of(MapObjectType.NPC))) {
            Point p = o.getPosition();
            if (p == null) {
                continue;
            }
            String name = (o instanceof NPC n) ? n.getName() : "";
            if (npcs.length() > 0) {
                npcs.append(',');
            }
            npcs.append("{\"x\":").append(p.x).append(",\"y\":").append(p.y)
                    .append(",\"n\":").append(jsonStr(name == null ? "" : name)).append('}');
            expandBounds(b, p.x, p.y);
        }

        StringBuilder portals = new StringBuilder();
        for (Portal p : map.getPortals()) {
            Point pos = p.getPosition();
            if (pos == null) {
                continue;
            }
            int type = p.getType();
            int tm = p.getTargetMapId();
            String kind;
            if (type == 3 || type == 9 || type == 12 || type == 13) {
                kind = "coll";                       // pc / pcs / collision-jump / custom-impact: warps on touch
            } else if (tm == mapId) {
                kind = "in";                         // shortcut within this same map
            } else if (tm > 0 && tm != 999999999) {
                kind = "cross";                      // press-up portal to another map
            } else {
                continue;                            // spawn point / target-less script / unbound door
            }
            String pname = p.getName();
            if (portals.length() > 0) {
                portals.append(',');
            }
            portals.append("{\"x\":").append(pos.x).append(",\"y\":").append(pos.y).append(",\"k\":\"").append(kind)
                    .append("\",\"tm\":").append(tm).append(",\"n\":").append(jsonStr(pname == null ? "" : pname)).append('}');
            expandBounds(b, pos.x, pos.y);
        }

        StringBuilder chars = new StringBuilder();
        for (Character chr : map.getCharacters()) {
            Point pos = chr.getPosition();
            if (pos == null) {
                continue;
            }
            if (chars.length() > 0) {
                chars.append(',');
            }
            chars.append("{\"x\":").append(pos.x).append(",\"y\":").append(pos.y)
                    .append(",\"n\":").append(jsonStr(chr.getName()))
                    .append(",\"bot\":").append(chr.getClient() instanceof BotClient).append('}');
        }

        if (b[0] > b[2]) { // no geometry at all — avoid a degenerate viewport
            b[0] = 0;
            b[1] = 0;
            b[2] = 0;
            b[3] = 0;
        }
        // Cached movement-profile graphs available for this map (the web profile picker); base + active
        // are always offered (getGraph above just cached `profile`), the rest are whatever bots warmed.
        java.util.LinkedHashSet<String> profSet = new java.util.LinkedHashSet<>();
        profSet.add(profileJson(BotMovementProfile.base()));
        profSet.add(profileJson(profile));
        for (BotMovementProfile p : BotNavigationGraphProvider.cachedProfiles(mapId)) {
            profSet.add(profileJson(p));
        }
        String name = graphData().names().getOrDefault(mapId, "map " + mapId);
        return "{\"map\":" + mapId + ",\"name\":" + jsonStr(name)
                + ",\"active\":" + profileJson(profile)
                + ",\"profiles\":[" + String.join(",", profSet) + "]"
                + ",\"bounds\":{\"minX\":" + b[0] + ",\"minY\":" + b[1] + ",\"maxX\":" + b[2] + ",\"maxY\":" + b[3] + "}"
                + ",\"regions\":[" + regions + "],\"edges\":[" + edges + "],\"npcs\":[" + npcs
                + "],\"portals\":[" + portals + "],\"chars\":[" + chars + "]}";
    }

    private static void expandBounds(long[] b, int x, int y) {
        b[0] = Math.min(b[0], x);
        b[1] = Math.min(b[1], y);
        b[2] = Math.max(b[2], x);
        b[3] = Math.max(b[3], y);
    }

    // --- recent normal (map) chat, tapped from the player + bot general-chat chokepoints ---
    private static final int CHAT_HISTORY_MAX = 30; // latest map-chat lines kept per map (bump to taste)

    private record ChatMsg(String time, String name, String text) {
    }

    private static final Map<Integer, ArrayDeque<ChatMsg>> chatByMap =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Record one normal (map) chat line; newest kept, capped at {@link #CHAT_HISTORY_MAX} per map. */
    public static void recordChat(int mapId, String name, String text) {
        if (name == null || text == null || text.isEmpty()) {
            return;
        }
        ChatMsg msg = new ChatMsg(String.format("%tT", System.currentTimeMillis()), name, text);
        ArrayDeque<ChatMsg> dq = chatByMap.computeIfAbsent(mapId, k -> new ArrayDeque<>());
        synchronized (dq) {
            dq.addLast(msg);
            while (dq.size() > CHAT_HISTORY_MAX) {
                dq.removeFirst();
            }
        }
    }

    private static String chatJson(int mapId) {
        ArrayDeque<ChatMsg> dq = chatByMap.get(mapId);
        if (dq == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        synchronized (dq) {
            for (ChatMsg m : dq) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append("{\"t\":").append(jsonStr(m.time())).append(",\"n\":").append(jsonStr(m.name()))
                        .append(",\"m\":").append(jsonStr(m.text())).append('}');
            }
        }
        return sb.toString();
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

    // --- graph data (reachable subgraph, built once; shared by the worldmap view) ---

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

    /** Position for the i-th dead-end child of a parent: a tight 3-wide cluster just below it. */
    private static double[] childSlot(double[] parent, int i) {
        int col = i % 3;
        int row = i / 3;
        return new double[]{parent[0] + (col - 1) * 18.0, parent[1] + 24.0 + row * 16.0};
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
        boolean bot = chr.getClient() instanceof BotClient;
        // c = commandable: MANAGED bot (self-owned/ownerless -> RTS-controllable) vs COMPANION (following
        // an online player owner). g = persistent crew id (0 = none). p = game party id (0 = solo). The
        // roster nests party (outer) > crew (inner) > loose; commands target managed bots only.
        int commandable = 0, crew = 0;
        String status = null;
        if (bot) {
            BotEntry e = lookupBotEntry(chr.getId());
            if (e != null) {
                commandable = commandableEntry(e) ? 1 : 0;
                crew = e.crewGroupId != null ? e.crewGroupId : 0;
                status = BotAutopilotManager.statusReport(e, chr); // hover/right-panel @status (cheap: field reads)
            }
        }
        int party = Math.max(0, chr.getPartyId());
        return "{\"id\":" + chr.getId() + ",\"n\":" + jsonStr(chr.getName()) + ",\"l\":" + chr.getLevel()
                + ",\"j\":" + jsonStr(j == null ? "" : j.toString())
                + ",\"c\":" + commandable + ",\"p\":" + party + ",\"g\":" + crew
                + (status != null ? ",\"status\":" + jsonStr(status) : "") + "}";
    }

    private static BotEntry lookupBotEntry(int botCharId) {
        try {
            return BotManager.getInstance().getEntryByBotCharId(botCharId);
        } catch (Throwable t) {
            return null; // degrade safely (e.g. registry not up) — server still re-checks on /api/command
        }
    }

    /** A managed (RTS-commandable) bot: ownerless, self-owned, or whose player owner is offline. */
    private static boolean commandableEntry(BotEntry e) {
        Character o = e.owner;
        return o == null || o == e.bot || !o.isLoggedin();
    }

    // --- RTS command endpoint (write) ---

    /** POST {@code {"cmd":"idle|fidget|move|resume|dance|jump|cheer","ids":[..],"maps":[..]}}.
     *  Applies the command to each commandable bot id; MOVE resolves its destination per bot from the
     *  clicked node's {@code maps} (hub-first, else nearest). Returns {@code {ok,applied,skipped[]}}. */
    private static void serveCommand(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"POST only\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String cmdStr = jsonField(body, "cmd");
        List<Integer> ids = jsonIntArray(body, "ids");
        List<Integer> maps = jsonIntArray(body, "maps");
        boolean resume = "resume".equalsIgnoreCase(cmdStr);
        boolean moveto = "moveto".equalsIgnoreCase(cmdStr); // precise (x,y) on the bot's current map (debug + RTS)
        int moveX = jsonInt(body, "x");
        int moveY = jsonInt(body, "y");
        BotEntry.OperatorCmd cmd = parseCmd(cmdStr);
        int followTarget = jsonInt(body, "target");
        BotManager mgr = BotManager.getInstance();
        // operator-follow: count the bots that will follow so each gets a distinct formation slot (spread)
        int followTotal = 0;
        if (cmd == BotEntry.OperatorCmd.FOLLOW) {
            for (int id : ids) {
                BotEntry e = mgr.getEntryByBotCharId(id);
                if (e != null && commandableEntry(e) && followTarget > 0 && followTarget != id) {
                    followTotal++;
                }
            }
        }
        int followIdx = 0;
        int applied = 0;
        List<String> skipped = new ArrayList<>();
        for (int id : ids) {
            BotEntry e = mgr.getEntryByBotCharId(id);
            if (e == null || !commandableEntry(e)) {
                skipped.add(String.valueOf(id));
                continue;
            }
            if (resume) {
                mgr.resumeFromOperatorCommand(e);
                applied++;
                continue;
            }
            if (moveto) {
                mgr.applyOperatorMoveTo(e, new Point(moveX, moveY));
                applied++;
                continue;
            }
            if (cmd == null) {
                skipped.add(String.valueOf(id));
                continue;
            }
            int moveMap = -1;
            if (cmd == BotEntry.OperatorCmd.MOVE || cmd == BotEntry.OperatorCmd.MOVE_ATTACK) {
                moveMap = resolveMoveTarget(e, maps);
                if (moveMap <= 0) {
                    skipped.add(String.valueOf(id));
                    continue;
                }
            }
            if (cmd == BotEntry.OperatorCmd.FOLLOW) {
                if (followTarget <= 0 || followTarget == id) {
                    skipped.add(String.valueOf(id)); // need a target, and a bot can't follow itself
                    continue;
                }
                e.followOffsetX = BotManager.followSlotOffset(followIdx++, followTotal); // set before publish for visibility
            }
            mgr.applyOperatorCommand(e, cmd, moveMap, followTarget);
            applied++;
        }
        String json = "{\"ok\":true,\"applied\":" + applied + ",\"skipped\":" + rawArr(skipped) + "}";
        send(ex, 200, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    private static BotEntry.OperatorCmd parseCmd(String s) {
        if (s == null) {
            return null;
        }
        return switch (s.toLowerCase()) {
            case "idle" -> BotEntry.OperatorCmd.IDLE;
            case "fidget" -> BotEntry.OperatorCmd.FIDGET;
            case "move" -> BotEntry.OperatorCmd.MOVE;               // quiet travel (no en-route attacks)
            case "moveattack" -> BotEntry.OperatorCmd.MOVE_ATTACK;  // fight on the way
            case "follow" -> BotEntry.OperatorCmd.FOLLOW;           // follow a chosen character
            case "dance" -> BotEntry.OperatorCmd.DANCE;
            case "jump" -> BotEntry.OperatorCmd.JUMP;
            case "cheer" -> BotEntry.OperatorCmd.CHEER;
            default -> null; // includes "resume" (handled separately) and unknown verbs
        };
    }

    /** Per-bot MOVE destination from a clicked node's constituent {@code maps}: prefer a hub, else the
     *  map nearest to the bot's current map by graph hops. */
    private static int resolveMoveTarget(BotEntry e, List<Integer> maps) {
        if (maps == null || maps.isEmpty()) {
            return -1;
        }
        if (maps.size() == 1) {
            return maps.get(0);
        }
        GraphData g = graphData();
        Set<Integer> hubs = new HashSet<>();
        for (int m : maps) {
            if (isHub(g, m)) {
                hubs.add(m);
            }
        }
        int from = e.bot != null ? e.bot.getMapId() : -1;
        return resolveMoveTargetFrom(g.adj(), hubs, from, maps);
    }

    /** Testable core of the merged-node rule: hub-first, then nearest-by-hops from {@code from}. */
    static int resolveMoveTargetFrom(Map<Integer, List<Integer>> adj, Set<Integer> hubs, int from,
                                     List<Integer> maps) {
        if (maps == null || maps.isEmpty()) {
            return -1;
        }
        if (maps.size() == 1) {
            return maps.get(0);
        }
        List<Integer> pool = new ArrayList<>();
        for (int m : maps) {
            if (hubs.contains(m)) {
                pool.add(m);
            }
        }
        if (pool.isEmpty()) {
            pool = maps;
        }
        int best = pool.get(0);
        int bestDist = Integer.MAX_VALUE;
        for (int m : pool) {
            int d = hopDistance(adj, from, m);
            if (d < bestDist) {
                bestDist = d;
                best = m;
            }
        }
        return best;
    }

    private static int hopDistance(Map<Integer, List<Integer>> adj, int from, int to) {
        if (from == to) {
            return 0;
        }
        if (from < 0 || adj == null) {
            return Integer.MAX_VALUE;
        }
        java.util.Deque<Integer> q = new java.util.ArrayDeque<>();
        Map<Integer, Integer> dist = new HashMap<>();
        q.add(from);
        dist.put(from, 0);
        while (!q.isEmpty()) {
            int cur = q.poll();
            int dc = dist.get(cur);
            for (int nb : adj.getOrDefault(cur, List.of())) {
                if (nb == to) {
                    return dc + 1;
                }
                if (!dist.containsKey(nb)) {
                    dist.put(nb, dc + 1);
                    q.add(nb);
                }
            }
        }
        return Integer.MAX_VALUE;
    }

    private static String jsonField(String body, String key) {
        String pat = "\"" + key + "\"";
        int k = body.indexOf(pat);
        if (k < 0) {
            return null;
        }
        int colon = body.indexOf(':', k + pat.length());
        if (colon < 0) {
            return null;
        }
        int i = colon + 1;
        while (i < body.length() && java.lang.Character.isWhitespace(body.charAt(i))) {
            i++;
        }
        if (i >= body.length() || body.charAt(i) != '"') {
            return null;
        }
        int end = body.indexOf('"', i + 1);
        return end < 0 ? null : body.substring(i + 1, end);
    }

    private static int jsonInt(String body, String key) {
        String pat = "\"" + key + "\"";
        int k = body.indexOf(pat);
        if (k < 0) {
            return -1;
        }
        int colon = body.indexOf(':', k + pat.length());
        if (colon < 0) {
            return -1;
        }
        int i = colon + 1;
        while (i < body.length() && java.lang.Character.isWhitespace(body.charAt(i))) {
            i++;
        }
        int j = i;
        while (j < body.length() && (java.lang.Character.isDigit(body.charAt(j)) || (j == i && body.charAt(j) == '-'))) {
            j++;
        }
        try {
            return Integer.parseInt(body.substring(i, j));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ponytail: lenient extractor for the known flat {cmd,ids,maps} shape; LAN-only, trusted input.
    private static List<Integer> jsonIntArray(String body, String key) {
        List<Integer> out = new ArrayList<>();
        int k = body.indexOf("\"" + key + "\"");
        if (k < 0) {
            return out;
        }
        int lb = body.indexOf('[', k);
        int rb = lb < 0 ? -1 : body.indexOf(']', lb);
        if (lb < 0 || rb < 0) {
            return out;
        }
        for (String tok : body.substring(lb + 1, rb).split(",")) {
            tok = tok.trim();
            if (tok.isEmpty()) {
                continue;
            }
            try {
                out.add(Integer.parseInt(tok));
            } catch (NumberFormatException ignore) {
                // skip non-numeric token
            }
        }
        return out;
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
