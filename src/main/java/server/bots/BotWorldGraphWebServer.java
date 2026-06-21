package server.bots;

import client.BotClient;
import client.Character;
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
    private static final int START_MAP = 10000;        // spawn/tutorial area — graph root + layout origin
    private static final int RETURN_ANCHOR = 104000000; // Lith Harbor — the hub maps must be able to return to
    private static final double STEP = 120.0;     // graph distance one portal hop covers
    private static final double CELL = 80.0;       // collision grid pitch; < STEP so neighbours rarely share a cell

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
            s.createContext("/api/graph", BotWorldGraphWebServer::serveGraph);
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

    private static void serveGraph(HttpExchange ex) throws IOException {
        String json = graphJsonCache;
        if (json == null) {
            json = buildGraphJson();
            graphJsonCache = json;
        }
        send(ex, 200, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void serveLive(HttpExchange ex) throws IOException {
        send(ex, 200, "application/json", liveJson(onlineCharacters()).getBytes(StandardCharsets.UTF_8));
    }

    // --- graph (reachable subgraph + deterministic directional layout, built once) ---

    record GNode(int id, String name, double x, double y, boolean danger) {
    }

    private static String buildGraphJson() {
        long t0 = System.currentTimeMillis();
        BotWorldGraph.Index idx = BotWorldGraph.get();
        // Maps a bot can legally reach from spawn: portals + boarding taxis + ferries, fares assumed
        // affordable so we get the whole traversable world (SSOT: BotWorldGraph's own flood).
        Set<Integer> reachable = BotWorldGraph.reachableWithin(START_MAP, 1000,
                new BotWorldGraph.RouteOptions(false, Integer.MAX_VALUE, true));
        Map<Integer, double[]> pos = layout(idx, reachable);
        Map<Integer, String> names = mapNames();
        Set<Integer> danger = unreturnable(reachable); // reachable from Lith but can't get back to it

        List<GNode> nodes = new ArrayList<>(reachable.size());
        for (int id : reachable) {
            double[] p = pos.getOrDefault(id, new double[]{0, 0});
            nodes.add(new GNode(id, names.getOrDefault(id, String.valueOf(id)), p[0], p[1], danger.contains(id)));
        }

        Set<Long> seen = new HashSet<>();
        List<int[]> edges = new ArrayList<>();
        for (int a : reachable) {
            for (int b : idx.neighbors(a)) {
                tryEdge(a, b, reachable, seen, edges);
            }
            for (BotWorldGraph.TaxiEdge t : BotWorldGraph.taxiEdgesFrom(a)) {
                tryEdge(a, t.toMapId(), reachable, seen, edges);
            }
            for (BotFerryManager.FerryRoute f : BotFerryManager.routesBoardingAt(a)) {
                tryEdge(a, f.destinationMapId(), reachable, seen, edges);
            }
        }
        log.info("Bot world-graph web view: {} reachable maps ({} unreturnable), {} edges, laid out in {} ms",
                nodes.size(), danger.size(), edges.size(), System.currentTimeMillis() - t0);
        return graphJson(nodes, edges);
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

    private static void tryEdge(int a, int b, Set<Integer> reach, Set<Long> seen, List<int[]> out) {
        if (a == b || !reach.contains(a) || !reach.contains(b)) {
            return;
        }
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        if (seen.add(((long) lo << 32) | (hi & 0xFFFFFFFFL))) {
            out.add(new int[]{lo, hi});
        }
    }

    /**
     * Deterministic positions by a BFS from START_MAP: a neighbour B of an already-placed map A is put
     * one STEP away in the direction of A's portal that leads to B (relative to A's portal centroid),
     * snapped to the nearest free grid cell so nodes never overlap. Taxi/ferry neighbours have no
     * in-map geometry, so they fan downward.
     *
     * <p>ponytail: honours the outgoing-portal direction only; it does not average in B's reverse
     * portal back toward A. Add that relaxation pass if the rough headings aren't enough.
     */
    private static Map<Integer, double[]> layout(BotWorldGraph.Index idx, Set<Integer> reachable) {
        Map<Integer, double[]> pos = new HashMap<>();
        Set<Long> occupied = new HashSet<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        pos.put(START_MAP, new double[]{0, 0});
        occupied.add(cellKey(0, 0));
        queue.add(START_MAP);

        while (!queue.isEmpty()) {
            int a = queue.poll();
            double[] pa = pos.get(a);

            List<BotWorldGraph.PortalLink> links = BotWorldGraph.portalLinks(a);
            double cx = 0;
            double cy = 0;
            for (BotWorldGraph.PortalLink l : links) {
                cx += l.x();
                cy += l.y();
            }
            if (!links.isEmpty()) {
                cx /= links.size();
                cy /= links.size();
            }
            // accumulate a unit direction per neighbour (multiple portals to the same map average out)
            Map<Integer, double[]> dir = new HashMap<>();
            for (BotWorldGraph.PortalLink l : links) {
                if (l.toMapId() == a || !reachable.contains(l.toMapId())) {
                    continue;
                }
                double dx = l.x() - cx;
                double dy = l.y() - cy;
                double len = Math.hypot(dx, dy);
                double[] d = dir.computeIfAbsent(l.toMapId(), k -> new double[2]);
                if (len > 1e-6) {
                    d[0] += dx / len;
                    d[1] += dy / len;
                }
            }

            TreeSet<Integer> neighbours = new TreeSet<>(); // sorted -> deterministic placement order
            for (int n : idx.neighbors(a)) {
                if (reachable.contains(n)) {
                    neighbours.add(n);
                }
            }
            for (BotWorldGraph.TaxiEdge t : BotWorldGraph.taxiEdgesFrom(a)) {
                if (reachable.contains(t.toMapId())) {
                    neighbours.add(t.toMapId());
                }
            }
            for (BotFerryManager.FerryRoute f : BotFerryManager.routesBoardingAt(a)) {
                if (reachable.contains(f.destinationMapId())) {
                    neighbours.add(f.destinationMapId());
                }
            }

            int fallback = 0;
            for (int b : neighbours) {
                if (pos.containsKey(b)) {
                    continue;
                }
                double[] d = dir.get(b);
                double ang;
                if (d != null && (d[0] != 0 || d[1] != 0)) {
                    ang = Math.atan2(d[1], d[0]);
                } else {
                    ang = Math.PI / 2 + (fallback++ - 1) * 0.6; // no geometry: fan downward
                }
                double tx = pa[0] + STEP * Math.cos(ang);
                double ty = pa[1] + STEP * Math.sin(ang);
                long free = nearestFreeCell(occupied, (int) Math.round(tx / CELL), (int) Math.round(ty / CELL));
                occupied.add(free);
                pos.put(b, new double[]{(int) (free >> 32) * CELL, (int) free * CELL});
                queue.add(b);
            }
        }
        return pos;
    }

    static long cellKey(int gx, int gy) {
        return ((long) gx << 32) | (gy & 0xFFFFFFFFL);
    }

    /** The cell at (gx,gy) if free, else the nearest free cell searched in deterministic ring order. */
    static long nearestFreeCell(Set<Long> occupied, int gx, int gy) {
        if (!occupied.contains(cellKey(gx, gy))) {
            return cellKey(gx, gy);
        }
        for (int r = 1; r < 5000; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) {
                        continue; // ring perimeter only
                    }
                    long k = cellKey(gx + dx, gy + dy);
                    if (!occupied.contains(k)) {
                        return k;
                    }
                }
            }
        }
        return cellKey(gx, gy); // pathological; never hit in practice
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
                    .append('}');
        }
        StringBuilder e = new StringBuilder();
        for (int[] ed : edges) {
            if (e.length() > 0) {
                e.append(',');
            }
            e.append('[').append(ed[0]).append(',').append(ed[1]).append(']');
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

    /** {@code {"maps":{"<id>":{"players":[..],"bots":[..]}}}} for maps that have anyone on them. */
    static String liveJson(List<Character> online) {
        Map<Integer, List<String>> players = new TreeMap<>();
        Map<Integer, List<String>> bots = new TreeMap<>();
        for (Character chr : online) {
            Map<Integer, List<String>> bucket = chr.getClient() instanceof BotClient ? bots : players;
            bucket.computeIfAbsent(chr.getMapId(), k -> new ArrayList<>()).add(chr.getName());
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
            sb.append('"').append(id).append("\":{\"players\":").append(jsonArr(players.get(id)))
                    .append(",\"bots\":").append(jsonArr(bots.get(id))).append('}');
        }
        return sb.append("}}").toString();
    }

    // --- JSON helpers ---

    // ponytail: hand-rolled JSON, flat payloads only; reach for a lib only if the shape grows.
    private static String jsonArr(List<String> xs) {
        if (xs == null || xs.isEmpty()) {
            return "[]";
        }
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(jsonStr(xs.get(i)));
        }
        return b.append(']').toString();
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
