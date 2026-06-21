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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;

/**
 * Localhost-only read-only web view of the {@link BotWorldGraph} (maps as nodes, portal
 * adjacency as edges) overlaid with live per-map occupancy of every online character — bots
 * and real players alike. Open {@code http://127.0.0.1:8089/} in a browser.
 *
 * <p>Deliberately dependency-free: the JDK's built-in {@link HttpServer}, hand-rolled JSON
 * (the payloads are flat), and a single static page that polls {@code /api/live} every ~2s.
 * Map-level only — no in-map coordinates. Started from {@code Server.init()} next to the
 * other bot-subsystem boot hooks.
 */
public final class BotWorldGraphWebServer {

    private static final Logger log = LoggerFactory.getLogger(BotWorldGraphWebServer.class);
    // ponytail: fixed localhost port; promote to a cfg knob only if it ever clashes.
    private static final int PORT = 8089;

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
            json = graphJson(BotWorldGraph.get().edges(), mapNames());
            graphJsonCache = json;
        }
        send(ex, 200, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void serveLive(HttpExchange ex) throws IOException {
        send(ex, 200, "application/json", liveJson(onlineCharacters()).getBytes(StandardCharsets.UTF_8));
    }

    // --- data gathering ---

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

    /** mapId -> display name, read once from {@code String.wz/Map.img} (same path as MapSearchHelper). */
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

    // --- JSON builders (pure, package-private for the self-check) ---

    /** {@code {"nodes":[{"id":..,"name":".."}],"edges":[[lo,hi],..]}} — edges undirected & deduped. */
    static String graphJson(Map<Integer, int[]> edges, Map<Integer, String> names) {
        Set<Integer> nodes = new HashSet<>(edges.keySet());
        for (int[] tos : edges.values()) {
            for (int to : tos) {
                nodes.add(to);
            }
        }

        StringBuilder n = new StringBuilder();
        for (int id : nodes) {
            if (n.length() > 0) {
                n.append(',');
            }
            String name = names.getOrDefault(id, String.valueOf(id));
            n.append("{\"id\":").append(id).append(",\"name\":").append(jsonStr(name)).append('}');
        }

        Set<Long> seen = new HashSet<>();
        StringBuilder e = new StringBuilder();
        for (Map.Entry<Integer, int[]> en : edges.entrySet()) {
            int from = en.getKey();
            for (int to : en.getValue()) {
                if (from == to) {
                    continue;
                }
                int lo = Math.min(from, to);
                int hi = Math.max(from, to);
                if (!seen.add(((long) lo << 32) | (hi & 0xFFFFFFFFL))) {
                    continue;
                }
                if (e.length() > 0) {
                    e.append(',');
                }
                e.append('[').append(lo).append(',').append(hi).append(']');
            }
        }
        return "{\"nodes\":[" + n + "],\"edges\":[" + e + "]}";
    }

    /** {@code {"maps":{"<id>":{"players":[..],"bots":[..]}}}} for maps that have anyone on them. */
    static String liveJson(List<Character> online) {
        Map<Integer, List<String>> players = new TreeMap<>();
        Map<Integer, List<String>> bots = new TreeMap<>();
        for (Character chr : online) {
            Map<Integer, List<String>> bucket = chr.getClient() instanceof BotClient ? bots : players;
            bucket.computeIfAbsent(chr.getMapId(), k -> new ArrayList<>()).add(chr.getName());
        }

        Set<Integer> maps = new java.util.TreeSet<>();
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

    private static void send(HttpExchange ex, int code, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}
