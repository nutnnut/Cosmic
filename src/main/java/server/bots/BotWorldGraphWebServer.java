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
import server.life.LifeFactory;
import server.life.MonsterInformationProvider;

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

    // Worldmap panel transforms for the /map view (worldmap id -> {x, y, scale}), captured by dragging the
    // worldmaps into a real-world arrangement and exporting. Worldmaps not listed fall back to a grid.
    private static final Map<String, double[]> WORLDMAP_LAYOUT = buildWorldMapLayout();

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
    private static volatile String graphJsonCache; // graph is static for the server's lifetime

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
            s.createContext("/", BotWorldGraphWebServer::servePage);
            s.createContext("/map", BotWorldGraphWebServer::serveWorldMapPage);
            s.createContext("/api/graph", BotWorldGraphWebServer::serveGraph);
            s.createContext("/api/relayout", BotWorldGraphWebServer::serveRelayout);
            s.createContext("/api/worldmaps", BotWorldGraphWebServer::serveWorldMaps);
            s.createContext("/wm/", BotWorldGraphWebServer::serveWorldMapImg);
            s.createContext("/api/live", BotWorldGraphWebServer::serveLive);
            s.createContext("/api/mapinfo", BotWorldGraphWebServer::serveMapInfo);
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

    /** WorldMap spots in image-local pixels. {@code alias} maps every constituent map id to its merge
     *  group's canonical id (entries sharing a map are one node); {@code occur} counts worldmaps per map. */
    private record WorldSpots(List<String> wmIds, Map<String, List<WorldSpot>> byWm,
                              Map<Integer, Integer> occur, Map<Integer, Integer> alias) {
    }

    private static int canon(WorldSpots ws, int map) {
        return ws.alias().getOrDefault(map, map);
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

    private static int ufFind(Map<Integer, Integer> uf, int x) {
        int r = x;
        while (uf.getOrDefault(r, r) != r) {
            r = uf.get(r);
        }
        while (uf.getOrDefault(x, x) != x) {
            int next = uf.get(x);
            uf.put(x, r);
            x = next;
        }
        return r;
    }

    private static void ufUnion(Map<Integer, Integer> uf, int a, int b) {
        int ra = ufFind(uf, a);
        int rb = ufFind(uf, b);
        if (ra != rb) {
            if (ra < rb) {
                uf.put(rb, ra); // root = smaller id, deterministic
            } else {
                uf.put(ra, rb);
            }
        }
    }

    private static WorldSpots scanWorldMapSpots() {
        DataProvider dp = DataProviderFactory.getDataProvider(WZFiles.MAP);
        List<String> wmIds = new ArrayList<>();
        Map<String, List<WorldSpot>> byWm = new TreeMap<>();
        Map<Integer, Integer> occur = new HashMap<>(); // mapId -> # of distinct ROOT worldmaps showing it
        Map<Integer, Integer> uf = new HashMap<>();     // union-find over maps sharing a MapList entry
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
                for (int i = 1; i < maps.size(); i++) {
                    ufUnion(uf, maps.get(0), maps.get(i)); // merge a multi-mapNo entry into one node
                }
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
        Map<Integer, Integer> alias = new HashMap<>();
        for (List<WorldSpot> spots : byWm.values()) {
            for (WorldSpot s : spots) {
                for (int m : s.maps()) {
                    alias.put(m, ufFind(uf, m));
                }
            }
        }
        return new WorldSpots(wmIds, byWm, occur, alias);
    }

    /**
     * {@code {"worldmaps":[{"id":"000","x":,"y":,"scale":,"nodes":[{id,maps,names,x,y,anchor,hub,dup,danger,
     * leaf,unreachable}]}],"edges":[[id,id,"p"|"t"]]}} — node x/y are image-local pixels; a node may stand
     * for several maps (merged {@code mapNo}) and edges use the node's canonical id.
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
                appendWorldNode(nodes, g, ws, s.maps(), s.x(), s.y(), true, dup);
            }
            for (int m : naByWm.getOrDefault(wm, List.of())) {
                double[] p = naLocal.get(m);
                appendWorldNode(nodes, g, ws, List.of(m), p[0], p[1], false, false);
            }
            wmsOut.add("{\"id\":\"" + wm + "\",\"x\":" + Math.round(tx) + ",\"y\":" + Math.round(ty)
                    + ",\"scale\":" + ts + ",\"nodes\":[" + nodes + "]}");
        }
        // Edges over every source map (entry constituents + non-anchors) aliased to its node's canonical id,
        // from the full portal graph — including unreachable worldmap spots, so nothing floats bare.
        BotWorldGraph.Index idx = BotWorldGraph.get();
        Set<Integer> sources = new HashSet<>(ws.alias().keySet());
        sources.addAll(naLocal.keySet());
        Set<Integer> renderedCanon = new HashSet<>();
        for (int m : sources) {
            renderedCanon.add(canon(ws, m));
        }
        Set<Long> seen = new HashSet<>();
        StringBuilder es = new StringBuilder();
        for (int a : sources) {                           // portals first (type p = walkable)
            int ca = canon(ws, a);
            for (int b : idx.neighbors(a)) {
                appendWorldEdge(es, ca, canon(ws, b), 'p', renderedCanon, seen);
            }
        }
        for (int a : sources) {                           // taxi/ferry NPC rides (type t)
            int ca = canon(ws, a);
            for (BotWorldGraph.TaxiEdge t : BotWorldGraph.taxiEdgesFrom(a)) {
                appendWorldEdge(es, ca, canon(ws, t.toMapId()), 't', renderedCanon, seen);
            }
            for (BotFerryManager.FerryRoute f : BotFerryManager.routesBoardingAt(a)) {
                appendWorldEdge(es, ca, canon(ws, f.destinationMapId()), 't', renderedCanon, seen);
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
    private static void appendWorldNode(StringBuilder sb, GraphData g, WorldSpots ws, List<Integer> maps,
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
        sb.append("{\"id\":").append(canon(ws, maps.get(0)))
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
        Set<Integer> spots = ws.alias().keySet(); // every map id that belongs to a worldmap entry
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
