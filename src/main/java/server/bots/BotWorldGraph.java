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
import java.util.PriorityQueue;
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
    private static final int GRAPH_VERSION = 4;
    private static final Path CACHE_FILE =
            Path.of("cache", "bot-world", "v" + GRAPH_VERSION, "portal-graph.tsv");
    private static final int NO_TARGET_MAPID = 999999999; // tm of spawn points / doors
    // A return scroll is only worth an edge when walking to the town would take this many hops.
    static final int RETURN_SCROLL_MIN_HOPS = 3;
    // Spinel's world tour parks the bot here; its only ride back is to the saved WORLDTOUR origin
    // (set when the bot boarded), resolved per-bot so the shrine is never a static cross-continent
    // shortcut to Lith Harbor. See expand()/findTaxiEdge() and BotTravelManager.taxiRide.
    static final int MUSHROOM_SHRINE = 800000000;

    /** Per-query toggles for the consumable edges; pure portal walking ignores them all.
     *  {@code worldTourReturn} is the bot's saved WORLDTOUR origin (or -1) — the only exit Spinel
     *  offers from {@link #MUSHROOM_SHRINE}, so it's empty for any bot not standing there.
     *  {@code fmReturn} is the same shape for the Free Market: the saved FREE_MARKET town its exit
     *  portal warps to, present only while the bot stands inside the FM maps. */
    record RouteOptions(boolean withReturnScroll, int meso, boolean withFerry, boolean isBeginner,
                        int riderLevel, int worldTourReturn, int fmReturn, Set<Integer> unlockedGates) {
        /** Options without any unlocked quest gates (the Temple-of-Time corridor stays sealed). Every
         *  shorter constructor funnels through here, so a caller that isn't a specific bot — the web
         *  view, cost probes, tests — leaves {@link #unlockedGates} empty and the gated timeQuest
         *  portals never appear, exactly like the per-bot FM/shrine returns. */
        RouteOptions(boolean withReturnScroll, int meso, boolean withFerry, boolean isBeginner,
                     int riderLevel, int worldTourReturn, int fmReturn) {
            this(withReturnScroll, meso, withFerry, isBeginner, riderLevel, worldTourReturn, fmReturn, Set.of());
        }
        /** Rider options without a Free-Market return (any bot not standing in the FM). */
        RouteOptions(boolean withReturnScroll, int meso, boolean withFerry, boolean isBeginner,
                     int riderLevel, int worldTourReturn) {
            this(withReturnScroll, meso, withFerry, isBeginner, riderLevel, worldTourReturn, -1);
        }
        /** Travel options for a specific rider, without a saved world-tour return. */
        RouteOptions(boolean withReturnScroll, int meso, boolean withFerry, boolean isBeginner, int riderLevel) {
            this(withReturnScroll, meso, withFerry, isBeginner, riderLevel, -1, -1);
        }
        /** Non-beginner options (the common case); unrestricted by level (abstract reachability probes). */
        RouteOptions(boolean withReturnScroll, int meso, boolean withFerry) {
            this(withReturnScroll, meso, withFerry, false, Integer.MAX_VALUE, -1, -1);
        }
        static final RouteOptions PORTALS_ONLY = new RouteOptions(false, 0, false);
    }

    /** One paid NPC ride: stand near {@code npcId} in {@code fromMapId}, pay, land in {@code toMapId}. */
    record TaxiEdge(int fromMapId, int npcId, int toMapId, int fare, boolean beginnerOnly, int minLevel) {
        /** Standard cab edge (any job/level, no discount). */
        TaxiEdge(int fromMapId, int npcId, int toMapId, int fare) {
            this(fromMapId, npcId, toMapId, fare, false, 0);
        }
        /** Flagged cab (e.g. beginner-discount), no level gate. */
        TaxiEdge(int fromMapId, int npcId, int toMapId, int fare, boolean beginnerOnly) {
            this(fromMapId, npcId, toMapId, fare, beginnerOnly, 0);
        }
    }

    /** One outgoing travel edge from a map: ride to {@code toMapId} costing {@code seconds}. The SSOT
     *  edge type shared by the time-Dijkstra {@link #route}, the reachability flood ({@link #expand})
     *  and {@link BotTravelCost#floodSeconds}, so the edge set and per-kind costs never drift apart. */
    record WeightedEdge(int toMapId, double seconds) {}

    // Victoria cab rides, mirrored from the NPC scripts (scripts/npc/<npcId>.js): each cab
    // warps to portal 0 of the destination for the listed fare. The 10k VIP cabs go to the
    // Ant Tunnel park. Most beginner discounts are ignored — bots usually have a job — except
    // Phil's beginner-only discount cab (see the 1002000 block below).
    // Kerning City <-> NLC subway and Kerning City <-> Kerning Square train are modeled as EventManager
    // "ferry" rides in BotFerryManager (Subway/KerningTrain), as is Orbis <-> Mu Lung (Hak). El Nath / Aqua
    // Road are still the remaining stranded Orbis regions - see the TODO in BotFerryManager.
    private static final List<TaxiEdge> TAXI_EDGES = List.of(
            // Lith Harbor 104000000 — Regular Cab 1002007, VIP Cab 1002004
            new TaxiEdge(104000000, 1002007, 100000000, 1000),
            new TaxiEdge(104000000, 1002007, 102000000, 1000),
            new TaxiEdge(104000000, 1002007, 101000000, 800),
            new TaxiEdge(104000000, 1002007, 103000000, 1000),
            new TaxiEdge(104000000, 1002007, 120000000, 800),
            new TaxiEdge(104000000, 1002004, 105070001, 10000),

            // Phil (1002000), Lith Harbor's beginner cab: same Victoria-town destinations as the regular
            // cab (1002007) but beginners ride at a 90% discount (fares = script cost/10, see 1002000.js).
            // beginnerOnly => only a beginner (jobId 0) may take it, AND it's exempt from the TAXI_MIN_MESO
            // shortcut gate in expand() (the discount makes it cheap enough for a broke beginner to hop towns).
            new TaxiEdge(104000000, 1002000, 100000000, 100, true),
            new TaxiEdge(104000000, 1002000, 102000000, 100, true),
            new TaxiEdge(104000000, 1002000, 101000000, 80, true),
            new TaxiEdge(104000000, 1002000, 103000000, 100, true),
            new TaxiEdge(104000000, 1002000, 120000000, 80, true),
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
            // Maple Island exit: Shanks (NPC 22000) sails to Lith Harbor (22000.js: gainMeso(-150),
            // warp(104000000,0)). He stands on BOTH Southperry maps - the classic 60000 and the post-Big-
            // Bang 2000000 that the Adventurer Training Center (1010000 -> 1020000 -> 2000000) leads to -
            // so both need the edge or training-center bots reach Southperry but find no boat out.
            // lv7+ gate: Shanks won't ferry a bot off Maple Island before it has found its footing.
            new TaxiEdge(60000, 22000, 104000000, 150, false, 7),
            new TaxiEdge(2000000, 22000, 104000000, 150, false, 7),
            // Dolphin NPC 2060009: Herb Town <-> Aqua Road (2060009.js: 10000 meso each way).
            new TaxiEdge(251000100, 2060009, 230000000, 10000),
            new TaxiEdge(230000000, 2060009, 251000100, 10000),
            // Pason 1002002 on Lith Harbor sails to Florina Beach (1002002.js: gainMeso(-1500), warp(110000000)).
            new TaxiEdge(104000000, 1002002, 110000000, 1500),
            // Pison 1081001 on Florina Beach sails back (1081001.js: warp to saved "FLORINA", defaults to
            // 104000000 Lith Harbor; no fare). Bot lands at Lith since the 1002002 ride saved that location.
            new TaxiEdge(110000000, 1081001, 104000000, 0),
            // Crane 2090005: Mu Lung Temple <-> Herb Town, instant warp + 500 meso (2090005.js cost[2]).
            new TaxiEdge(250000100, 2090005, 251000000, 500),
            new TaxiEdge(251000000, 2090005, 250000100, 500),
            // Jeff 2030000 on Ice Valley II gates the ONLY entrance to Sharp Cliff I (211040300):
            // a free NPC-click warp (2030000.js: cm.warp(211040300, 5)) with a level-50-themed gate
            // that the script actually enforces at level >= 30. No forward portal exists (211040200's
            // only plain portal goes back to Ice Valley I), so model it as a free, lv30 taxi ride.
            new TaxiEdge(211040200, 2030000, 211040300, 0, false, 30),
            // NLC Taxi 9201056 gates the ONLY entrance to the NLC Haunted House / ghost-park cluster
            // (Bent Tree, Valley of Heroes, 40 maps): 9201056.js warps NLC <-> 682000000 for 15000 meso
            // each way, no gate. No portal connects them, so model both legs as a taxi ride.
            new TaxiEdge(600000000, 9201056, 682000000, 15000),
            new TaxiEdge(682000000, 9201056, 600000000, 15000),
            // Thomas Swift 9201022: free cab Henesys <-> Amoria (9201022.js: warp 680000000 / 100000000,
            // no fare). Amoria is its own island reachable ONLY by this cab, so model both legs as a ride.
            new TaxiEdge(100000000, 9201022, 680000000, 0),
            new TaxiEdge(680000000, 9201022, 100000000, 0),
            // Spinel / Maple Travel Agency: world-tour NPC 9000020. Maps where Spinel stands use
            // travelType 0 -> Mushroom Shrine for 3000 mesos; Boat Quay uses travelType 1 -> Malaysia
            // for 10000. The return from Mushroom Shrine is NOT a static edge: Spinel sends the bot back
            // to its saved WORLDTOUR origin, injected per-bot in expand() (see MUSHROOM_SHRINE) so the
            // shrine can't be abused as a flat-fee shortcut to Lith Harbor from any continent.
            new TaxiEdge(100000000, 9000020, 800000000, 3000),
            new TaxiEdge(101000000, 9000020, 800000000, 3000),
            new TaxiEdge(102000000, 9000020, 800000000, 3000),
            new TaxiEdge(103000000, 9000020, 800000000, 3000),
            new TaxiEdge(104000000, 9000020, 800000000, 3000),
            new TaxiEdge(200000000, 9000020, 800000000, 3000),
            new TaxiEdge(220000000, 9000020, 800000000, 3000),
            new TaxiEdge(240000000, 9000020, 800000000, 3000),
            // Leafre dock NPC 2082003: the player uses Dragon Scale (2210016), becomes a dragon,
            // flies through 200090500/200090510, and enters Temple of Time at 270000100 via
            // scripts/portal/templeenter.js. The reverse leaves through outTemple.js. Bots execute
            // the same scripted portal sequence; their flight motion is approximated by the swim
            // integrator because the server has no separate dragon-flight physics model.
            new TaxiEdge(240000110, 2082003, 270000100, 0),
            new TaxiEdge(270000100, 2082003, 240000110, 0),
            new TaxiEdge(250000000, 9000020, 800000000, 3000),
            new TaxiEdge(260000000, 9000020, 800000000, 3000),
            new TaxiEdge(680000000, 9000020, 800000000, 3000),
            new TaxiEdge(541000000, 9000020, 550000000, 10000),
            // Audrey 9201135 connects Singapore CBD, Malaysia Metropolis and Kampung Village.
            // Metropolis -> Boat Quay is the script's no-saved-location return fallback.
            new TaxiEdge(540000000, 9201135, 550000000, 42000),
            new TaxiEdge(550000000, 9201135, 551000000, 10000),
            new TaxiEdge(551000000, 9201135, 550000000, 10000),
            new TaxiEdge(550000000, 9201135, 541000000, 0));

    // NPCs whose "taxi" edge is a cross-continent scripted-warp ride with NO walking alternative
    // (the block above): Shanks (Maple Island exit), Dolphin (Aqua Road), Pason/Pison (Florina Beach),
    // Crane (Mu Lung <-> Herb Town), Jeff (Ice Valley II -> Sharp Cliff I), Spinel world tour, Audrey
    // Malaysia/Singapore travel, Thomas Swift (Henesys <-> Amoria). These stay available even to a poor
    // bot; the Victoria cab edges (shortcuts between towns that ARE walkable) are gated by the taxi tier.
    private static final Set<Integer> CONTINENT_RIDE_NPCS = Set.of(
            22000, 2060009, 1002002, 1081001, 2090005, 2030000, 9201056, 9000020, 9201135, 9201022,
            2082003);

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

    /** The taxi ride from one map to another, or null when no cab drives that route (non-beginner,
     *  level-unrestricted — abstract edge lookup with no rider). */
    static TaxiEdge findTaxiEdge(int fromMapId, int toMapId) {
        return findTaxiEdge(fromMapId, toMapId, false, Integer.MAX_VALUE);
    }

    /** The taxi ride from one map to another, or null when no cab drives that route. A beginner prefers
     *  a beginner-discount cab (Phil) over the full-price one; a non-beginner never gets a beginner cab
     *  (it would let them underpay) — so beginnerOnly edges are skipped for them. Mirrors {@code expand()}. */
    static TaxiEdge findTaxiEdge(int fromMapId, int toMapId, boolean isBeginner, int riderLevel) {
        TaxiEdge fullPrice = null;
        for (TaxiEdge edge : TAXI_BY_MAP.getOrDefault(fromMapId, List.of())) {
            if (edge.toMapId() != toMapId) {
                continue;
            }
            if (edge.minLevel() > 0 && riderLevel < edge.minLevel()) {
                continue; // level-gated ride (Shanks: lv7+ to leave Maple Island)
            }
            if (edge.beginnerOnly()) {
                if (isBeginner) {
                    return edge; // discounted cab — the beginner's first choice
                }
                continue; // non-beginner may not ride a beginner-only cab
            }
            if (fullPrice == null) {
                fullPrice = edge;
            }
        }
        // The Spinel return out of the shrine has no static edge (its destination is the bot's saved
        // WORLDTOUR origin, resolved at ride time in taxiRide); synthesize it so the executor can drive
        // the walk-to-NPC-and-pay flow. Free, ungated — matches the script.
        if (fullPrice == null && fromMapId == MUSHROOM_SHRINE && toMapId != MUSHROOM_SHRINE) {
            return new TaxiEdge(MUSHROOM_SHRINE, 9000020, toMapId, 0);
        }
        return fullPrice;
    }

    /**
     * Directed adjacency: mapId → distinct portal target mapIds (sorted), plus the per-map
     * return-scroll shortcut (mapId → returnMap town) where it beats walking.
     */
    record Index(Map<Integer, int[]> edges, Map<Integer, Integer> scrollTargets,
                 Map<Integer, Integer> returnMaps) {
        int[] neighbors(int mapId) {
            return edges.getOrDefault(mapId, new int[0]);
        }

        /** Town a return scroll warps this map to, or -1 when the walk is short enough anyway. */
        int scrollTarget(int mapId) {
            return scrollTargets.getOrDefault(mapId, -1);
        }

        /** The map you're sent to on death/return-scroll from here (info/returnMap); itself when unset. */
        int returnMap(int mapId) {
            return returnMaps.getOrDefault(mapId, mapId);
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

    /** The map you're dumped onto on death / nearest-town scroll from {@code mapId} (info/returnMap);
     *  itself when unset. Used to keep a fragile bot off maps that return into a region it can't escape. */
    static int returnMapOf(int mapId) {
        return get().returnMap(mapId);
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
        return route(get(), fromMapId, toMapId, maxHops, options, m -> false);
    }

    /** Like {@link #route(int, int, int, RouteOptions)} but refuses to path INTO any map {@code blocked}
     *  accepts — so a route that could only reach the target THROUGH a blocked map returns null. */
    static List<Integer> route(int fromMapId, int toMapId, int maxHops, RouteOptions options,
                               java.util.function.IntPredicate blocked) {
        return route(get(), fromMapId, toMapId, maxHops, options, blocked);
    }

    /** Pure BFS over an explicit graph; see {@link #route(int, int, int, RouteOptions)}. */
    static List<Integer> route(Index graph, int fromMapId, int toMapId, int maxHops, RouteOptions options) {
        return route(graph, fromMapId, toMapId, maxHops, options, m -> false);
    }

    static List<Integer> route(Index graph, int fromMapId, int toMapId, int maxHops, RouteOptions options,
                               java.util.function.IntPredicate blocked) {
        if (fromMapId == toMapId) {
            return List.of();
        }
        if (maxHops <= 0) {
            return null;
        }
        long perfT0 = BotPerformanceMonitor.start();
        try {
            return routeSearch(graph, fromMapId, toMapId, maxHops, options, blocked);
        } finally {
            BotPerformanceMonitor.recordSince("world-route", perfT0);
        }
    }

    private static List<Integer> routeSearch(Index graph, int fromMapId, int toMapId, int maxHops,
                                             RouteOptions options, java.util.function.IntPredicate blocked) {
        // Shortest-TIME path (uniform-cost / Dijkstra over the same edges {@link BotTravelCost#floodSeconds}
        // floods), bounded by maxHops. Portal hops are uniform, so a portals-only route is still the
        // fewest-hop one; the difference shows when a return scroll (5s) or town cab (30s) actually beats
        // walking it (25s/hop) — those legs now win on real travel time instead of being a wash at 1 hop.
        // Ferry uses the nominal travelrate (the flood reads the live rate); cross-continent boats have no
        // portal alternative anyway, so the magnitude never flips the pick — but a slow boat now loses to a
        // shorter walk that exists, which the old hop count wrongly preferred.
        double ferrySeconds = BotTravelCost.ferrySeconds(ms -> ms);
        Map<Integer, Integer> cameFrom = new HashMap<>();
        Set<Integer> settled = new HashSet<>();
        // {seconds, mapId, hops, prevMapId}; cheapest seconds first, fewest hops breaks time ties so the
        // path stays as short as the old BFS when costs are equal.
        PriorityQueue<double[]> frontier = new PriorityQueue<>(
                (a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[2], b[2]));
        frontier.add(new double[]{0.0, fromMapId, 0, fromMapId});
        while (!frontier.isEmpty()) {
            double[] current = frontier.poll();
            int mapId = (int) current[1];
            int hops = (int) current[2];
            if (!settled.add(mapId)) {
                continue; // already reached at a cheaper time
            }
            cameFrom.put(mapId, (int) current[3]); // commit the predecessor on the settling (cheapest) path
            if (mapId == toMapId) {
                return reconstruct(cameFrom, fromMapId, toMapId);
            }
            if (hops >= maxHops) {
                continue;
            }
            for (WeightedEdge edge : weightedNeighbors(graph, mapId, options, ferrySeconds)) {
                if (blocked.test(edge.toMapId()) || settled.contains(edge.toMapId())) {
                    continue; // never path into a blocked map; skip already-settled targets
                }
                frontier.add(new double[]{current[0] + edge.seconds(), edge.toMapId(), hops + 1, mapId});
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

    /**
     * SSOT for a map's outgoing time-weighted travel edges under {@code options}: portal hops, then
     * the affordable consumable rides (return scroll, taxi, world-tour return, ferry). The time-Dijkstra
     * {@link #route}, the reachability flood ({@link #expand}) and {@link BotTravelCost#floodSeconds} all
     * enumerate over exactly this, so the edge set and per-kind seconds can never drift apart.
     *
     * <p>{@code ferrySeconds} is supplied by the caller because ferry time scales with the runtime-mutable
     * travelrate — portal/scroll/taxi costs are fixed (see {@link BotTravelCost}). {@code taxiSpendGate}
     * applies the executor's spend policy (paid town cabs only above {@link BotManager.cfg#TAXI_MIN_MESO},
     * since those towns are walkable) for the route/reachability callers; the cost model passes {@code false}
     * so it values a destination by what the bot COULD reach if it chose to cab, not by current thrift. Hard
     * gates — beginner-only, level, fare, ferry opt-in/ticket — always apply.
     */
    static List<WeightedEdge> weightedNeighbors(Index graph, int mapId, RouteOptions options,
                                                double ferrySeconds, boolean taxiSpendGate) {
        List<WeightedEdge> out = new ArrayList<>();
        for (int next : graph.neighbors(mapId)) {
            out.add(new WeightedEdge(next, BotTravelCost.PORTAL_HOP_SECONDS));
        }
        if (options.withReturnScroll()) {
            int scrollTarget = graph.scrollTarget(mapId);
            if (scrollTarget != -1) {
                out.add(new WeightedEdge(scrollTarget, BotTravelCost.SCROLL_SECONDS));
            }
        }
        // Fares are gated per edge, not cumulatively along the route — the travel executor re-checks meso
        // at every ride, and a broke bot mid-route just falls back/re-plans.
        boolean taxiShortcuts = options.meso() >= BotManager.cfg.TAXI_MIN_MESO;
        for (TaxiEdge taxi : TAXI_BY_MAP.getOrDefault(mapId, List.of())) {
            if (taxi.beginnerOnly() && !options.isBeginner()) {
                continue; // beginner-discount cab (Phil): only a beginner may ride it
            }
            if (taxi.minLevel() > 0 && options.riderLevel() < taxi.minLevel()) {
                continue; // level-gated ride (Shanks: lv7+ to leave Maple Island)
            }
            boolean continentRide = CONTINENT_RIDE_NPCS.contains(taxi.npcId());
            // Spend policy (route/reachability only): paid town-to-town cabs cost a hop only when meso is
            // above the taxi tier; below it the bot walks. Cross-continent rides have no walk alternative,
            // and beginner cabs are cheap (90% off), so both stay available regardless of the tier.
            boolean gateOk = !taxiSpendGate || continentRide || taxi.beginnerOnly() || taxiShortcuts;
            if (gateOk && options.meso() >= taxi.fare()) {
                out.add(new WeightedEdge(taxi.toMapId(), BotTravelCost.TAXI_SECONDS));
            }
        }
        // Spinel's only ride out of the shrine is back to the saved WORLDTOUR origin (free). It exists
        // only for the bot standing here (worldTourReturn != -1), so a remote bot can't route THROUGH
        // the shrine to reach Lith Harbor cheaply — the exploit the static return edge used to allow.
        if (mapId == MUSHROOM_SHRINE && options.worldTourReturn() != -1) {
            out.add(new WeightedEdge(options.worldTourReturn(), BotTravelCost.TAXI_SECONDS));
        }
        // The Free Market's only exit (entrance out00, script market00) warps to the bot's saved
        // FREE_MARKET origin — same shape as the shrine return: the edge exists only for a bot
        // standing inside the market (fmReturn != -1), so the FM subgraph stays a dead end that
        // can never be routed THROUGH between its 24 portal towns, only out of. Room -> entrance
        // legs are ordinary unscripted portals already present from the WZ scan.
        if (mapId == constants.id.MapId.FM_ENTRANCE && options.fmReturn() != -1) {
            out.add(new WeightedEdge(options.fmReturn(), BotTravelCost.PORTAL_HOP_SECONDS));
        }
        // Temple-of-Time corridor: each forward timeQuest portal is routable only for a bot that has
        // unlocked that gate (its quest/item condition, resolved once per query into unlockedGates). Same
        // per-bot shape as the shrine/FM returns — the gated edge never enters the shared baked Index.
        for (QuestGatedEntrance g : QUEST_GATED_BY_MAP.getOrDefault(mapId, List.of())) {
            if (options.unlockedGates().contains(g.gateKey())) {
                out.add(new WeightedEdge(g.destMap(), BotTravelCost.PORTAL_HOP_SECONDS));
            }
        }
        if (options.withFerry()) {
            for (BotFerryManager.FerryRoute ferry : BotFerryManager.routesBoardingAt(mapId)) {
                if (options.meso() >= ferry.ticketCost()) {
                    out.add(new WeightedEdge(ferry.destinationMapId(), ferrySeconds));
                }
            }
        }
        return out;
    }

    /** Convenience overload for the time-weighted route/flood callers (spend gate applied). */
    static List<WeightedEdge> weightedNeighbors(Index graph, int mapId, RouteOptions options, double ferrySeconds) {
        return weightedNeighbors(graph, mapId, options, ferrySeconds, true);
    }

    /** A map's outgoing edges as plain target ids — the reachability projection of
     *  {@link #weightedNeighbors} (same edge set, spend gate applied; weights and ferry time irrelevant
     *  to who-can-reach-what). */
    private static List<Integer> expand(Index graph, int mapId, RouteOptions options) {
        List<WeightedEdge> edges = weightedNeighbors(graph, mapId, options, 0.0, true);
        List<Integer> out = new ArrayList<>(edges.size());
        for (WeightedEdge edge : edges) {
            out.add(edge.toMapId());
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
            return withScriptedEntrances(loaded);
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
        Index built = new Index(Collections.unmodifiableMap(edges), Collections.unmodifiableMap(scrollTargets),
                Collections.unmodifiableMap(returnMaps));
        writeCache(built); // cache stays pure-WZ; the scripted entrances are re-added in-memory below
        return withScriptedEntrances(built);
    }

    /** A map reachable only by a SCRIPTED portal (tm=999999999) the WZ scan can't follow — either a
     *  forward-unreachable hidden street (job instructors) or the return leg of a one-way region (you
     *  can walk IN by portal but only walk OUT via a scripted portal, so the region is a can't-return
     *  trap). {@code fromMap} has a portal named {@code portalName} whose own script warps to
     *  {@code destMap}; we teach the nav layer that destination so routing AND the live portal-finder
     *  treat it as a normal portal (the bot walks it and {@code GenericPortal.enterPortal} runs the real
     *  warp). The return entries were found by a Lith-Harbor-anchored symmetry audit (every map Lith can
     *  reach must reach back); each is the reverse of an existing graph exit, with the scripted portal's
     *  questless default == destMap. Verified vs Map.wz + scripts/portal. */
    record ScriptedEntrance(int fromMap, String portalName, int destMap) {}

    static final List<ScriptedEntrance> SCRIPTED_ENTRANCES = List.of(
            // 1st-job Magician: Ellinia -> Magic Library (Grendel), script enterMagiclibrar
            new ScriptedEntrance(101000000, "jobin00", 101000003),
            // 1st-job Bowman: Henesys school lobby -> Bowman Instructional School (Athena), script enterAchter
            new ScriptedEntrance(100000200, "in02", 100000201),
            // 4th job: Leafre -> Forest of the Priest 4th-job room, script minar_job4
            new ScriptedEntrance(240010500, "in00", 240010501),
            // Kerning subway: Ticketing Booth -> Line 1 <Area 1>, script subway_in2 (pt=7, tm=999999999).
            // Forward-unreachable grind cluster (64 Bubbling); rest of Line 1 reachable by normal portals from here.
            new ScriptedEntrance(103000100, "in00", 103000101),
            // NLC Haunted House -> Foyer (Sophilia mansion), portal st01 script halloween_enter (pt=8,
            // tm=999999999, unconditional pi.warp(682000100)). One-way: Foyer walks back but not in.
            new ScriptedEntrance(682000000, "st01", 682000100),
            // Return legs (one-way regions, Lith-anchored audit) -----------------------------------------
            // Snow Island: Dangerous Forest field -> Puro's boat dock 140020300, script enterPort (then
            // the Puro ferry returns to Lith). Without this the whole Rien/Snow Island is a can't-return trap.
            new ScriptedEntrance(140020200, "east00", 140020300),
            // Korean Folk Town: Fox Ridge -> KFT-side return map 222010200, script foxLaidy_map
            new ScriptedEntrance(222010300, "west00", 222010200),
            // Leafre: Cave of Life entrance -> Leafre field 240040600, script hontale_morph2
            new ScriptedEntrance(240040700, "out00", 240040600),
            // Helios Tower Time Control Room -> Ellin Forest Small Forest, script move_elin.
            new ScriptedEntrance(222020400, "in01", 300000100),
            // Leafre station dock -> Leafre station, script dracoout. The dock's ONLY other graph edge
            // is the ticket-gated Orbis ferry (30k, sold one map back), so a ticketless bot inside was
            // a can't-return trap — the symmetry audit missed it because the ferry edge "reaches back".
            new ScriptedEntrance(240000110, "west00", 240000100),
            // Nett's Pyramid hub (Pyramid Dunes): bots wander onto the plain desert portal piramid00
            // on 260020500 (script nets_in, unconditional warp in + saveLocation("MIRROR")), and the
            // hub's ONLY exit out00 (script nets_out, warps to the saved MIRROR = 260020500) was
            // invisible to the graph — a can't-return trap that collected dozens of bots.
            new ScriptedEntrance(926010000, "out00", 260020500)
    );

    /** A Temple-of-Time corridor portal (the {@code timeQuest} script portal, always named "in00") whose
     *  own script warps {@code fromMap -> destMap} only once the stepping player satisfies its condition
     *  (see {@code scripts/portal/timeQuest.js}). Unlike {@link ScriptedEntrance} this edge is NOT baked
     *  into the shared {@link Index}: it appears per-bot in {@link #weightedNeighbors} only when the bot's
     *  {@link RouteOptions#unlockedGates} contains {@link #gateKey}, so the corridor is sealed for a bot
     *  that hasn't done the quest. {@code gateKey} is the required quest id, except the final Ruins gate
     *  which uses {@link #TEMPLE_ITEM_GATE} (an item-or-quest condition, resolved per-bot in
     *  {@link BotAutopilotManager#unlockedTempleGates}). */
    record QuestGatedEntrance(int fromMap, String portalName, int destMap, int gateKey) {}

    /** Sentinel {@link QuestGatedEntrance#gateKey} for the Ruins hub gate (270040000 -> 270040100): the
     *  timeQuest script opens it on {@code haveItem(4032002) OR isQuestCompleted(3522)}, not a plain quest
     *  completion, so it can't carry a real quest id. */
    static final int TEMPLE_ITEM_GATE = 999999;

    // Temple-of-Time forward corridor gates, mirrored from scripts/portal/timeQuest.js. Each fromMap's
    // forward portal is the timeQuest script portal (verified named "in00" in Map.wz for all 16). gateKey
    // is the quest whose completion the script requires to warp to destMap; the last row is the item gate.
    static final List<QuestGatedEntrance> QUEST_GATED_ENTRANCES = List.of(
            new QuestGatedEntrance(270010100, "in00", 270010110, 3501),
            new QuestGatedEntrance(270010200, "in00", 270010210, 3502),
            new QuestGatedEntrance(270010300, "in00", 270010310, 3503),
            new QuestGatedEntrance(270010400, "in00", 270010410, 3504),
            new QuestGatedEntrance(270010500, "in00", 270020000, 3507),
            new QuestGatedEntrance(270020100, "in00", 270020110, 3508),
            new QuestGatedEntrance(270020200, "in00", 270020210, 3509),
            new QuestGatedEntrance(270020300, "in00", 270020310, 3510),
            new QuestGatedEntrance(270020400, "in00", 270020410, 3511),
            new QuestGatedEntrance(270020500, "in00", 270030000, 3514),
            new QuestGatedEntrance(270030100, "in00", 270030110, 3515),
            new QuestGatedEntrance(270030200, "in00", 270030210, 3516),
            new QuestGatedEntrance(270030300, "in00", 270030310, 3517),
            new QuestGatedEntrance(270030400, "in00", 270030410, 3518),
            new QuestGatedEntrance(270030500, "in00", 270040000, 3519),
            new QuestGatedEntrance(270040000, "in00", 270040100, TEMPLE_ITEM_GATE)
    );

    private static final Map<Integer, List<QuestGatedEntrance>> QUEST_GATED_BY_MAP = buildQuestGatedByMap();

    private static Map<Integer, List<QuestGatedEntrance>> buildQuestGatedByMap() {
        Map<Integer, List<QuestGatedEntrance>> byMap = new HashMap<>();
        for (QuestGatedEntrance g : QUEST_GATED_ENTRANCES) {
            byMap.computeIfAbsent(g.fromMap(), k -> new ArrayList<>()).add(g);
        }
        byMap.replaceAll((k, v) -> List.copyOf(v));
        return Collections.unmodifiableMap(byMap);
    }

    /** The scripted-entrance portal name to walk for a {@code fromMap -> destMap} hop, or null when that
     *  hop isn't a known scripted entrance. The travel executor enters it like a normal portal; its own
     *  script does the warp. */
    static String scriptedEntrancePortal(int fromMap, int destMap) {
        for (ScriptedEntrance e : SCRIPTED_ENTRANCES) {
            if (e.fromMap() == fromMap && e.destMap() == destMap) {
                return e.portalName();
            }
        }
        // Temple corridor timeQuest portals: routing already decided the hop is legal (the gate is
        // unlocked), so the executor just needs the physical portal to walk — its script re-checks and warps.
        for (QuestGatedEntrance g : QUEST_GATED_ENTRANCES) {
            if (g.fromMap() == fromMap && g.destMap() == destMap) {
                return g.portalName();
            }
        }
        // FM exit is per-bot dynamic (out00's script warps to the SAVED town), so it can't be a
        // static row: any hop out of the entrance that isn't into a room walks the exit portal.
        // If the script lands somewhere other than the planned hop, travel just replans from there.
        if (fromMap == constants.id.MapId.FM_ENTRANCE
                && !constants.game.GameConstants.isFreeMarketRoom(destMap)) {
            return "out00";
        }
        return null;
    }

    /** Add each {@link #SCRIPTED_ENTRANCES} edge ({@code fromMap -> destMap}) so routing can reach the
     *  islanded instructor map; live traversal then walks the real scripted portal. Returns a new Index;
     *  never mutates {@code base}. */
    private static Index withScriptedEntrances(Index base) {
        Map<Integer, int[]> edges = new HashMap<>(base.edges());
        for (ScriptedEntrance e : SCRIPTED_ENTRANCES) {
            int[] cur = edges.getOrDefault(e.fromMap(), new int[0]);
            if (arrayContains(cur, e.destMap())) {
                continue;
            }
            int[] next = new int[cur.length + 1];
            System.arraycopy(cur, 0, next, 0, cur.length);
            next[cur.length] = e.destMap();
            edges.put(e.fromMap(), next);
        }
        return new Index(Collections.unmodifiableMap(edges), base.scrollTargets(), base.returnMaps());
    }

    private static boolean arrayContains(int[] a, int v) {
        for (int x : a) {
            if (x == v) {
                return true;
            }
        }
        return false;
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
        Index portalsOnly = new Index(edges, Map.of(), Map.of());
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

    // ---- disk cache: one row per map: mapId \t target,target,... \t scrollTarget \t returnMap ----

    private static Index loadCache() {
        if (!Files.isRegularFile(CACHE_FILE)) {
            return null;
        }
        try {
            Map<Integer, int[]> edges = new HashMap<>();
            Map<Integer, Integer> scrollTargets = new HashMap<>();
            Map<Integer, Integer> returnMaps = new HashMap<>();
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
                if (cols.length > 3 && !cols[3].isEmpty()) {
                    returnMaps.put(mapId, Integer.parseInt(cols[3]));
                }
            }
            return edges.isEmpty() ? null
                    : new Index(Collections.unmodifiableMap(edges), Collections.unmodifiableMap(scrollTargets),
                            Collections.unmodifiableMap(returnMaps));
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
                sb.append('\t');
                int returnMap = built.returnMap(e.getKey());
                if (returnMap != e.getKey()) { // omit self (the default); keeps the file lean
                    sb.append(returnMap);
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
        return new Index(Collections.unmodifiableMap(copy), Map.copyOf(scrollTargets), Map.of());
    }
}
