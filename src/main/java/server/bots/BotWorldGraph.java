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
    private static final int GRAPH_VERSION = 3;
    private static final Path CACHE_FILE =
            Path.of("cache", "bot-world", "v" + GRAPH_VERSION, "portal-graph.tsv");
    private static final int NO_TARGET_MAPID = 999999999; // tm of spawn points / doors
    // A return scroll is only worth an edge when walking to the town would take this many hops.
    static final int RETURN_SCROLL_MIN_HOPS = 3;

    /** Per-query toggles for the consumable edges; pure portal walking ignores them all. */
    record RouteOptions(boolean withReturnScroll, int meso, boolean withFerry, boolean isBeginner, int riderLevel) {
        /** Non-beginner options (the common case); unrestricted by level (abstract reachability probes). */
        RouteOptions(boolean withReturnScroll, int meso, boolean withFerry) {
            this(withReturnScroll, meso, withFerry, false, Integer.MAX_VALUE);
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
            new TaxiEdge(211040200, 2030000, 211040300, 0, false, 30));

    // NPCs whose "taxi" edge is a cross-continent scripted-warp ride with NO walking alternative
    // (the block above): Shanks (Maple Island exit), Dolphin (Aqua Road), Pason/Pison (Florina Beach),
    // Crane (Mu Lung <-> Herb Town), Jeff (Ice Valley II -> Sharp Cliff I). These stay available even to
    // a poor bot; the Victoria cab edges
    // (optional shortcuts between towns that ARE walkable) are gated by the taxi meso tier in expand().
    private static final Set<Integer> CONTINENT_RIDE_NPCS = Set.of(22000, 2060009, 1002002, 1081001, 2090005, 2030000);

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
                    if (blocked.test(next)) {
                        continue; // never path into a blocked map -> any route through it is pruned
                    }
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
        // Spend policy: paid taxi SHORTCUTS (walkable town-to-town cabs) only when meso is above the
        // taxi tier; below it the bot walks. Cross-continent rides (CONTINENT_RIDE_NPCS) have no walk
        // alternative, so they're always allowed (still subject to the per-edge fare check).
        boolean taxiShortcuts = options.meso() >= BotManager.cfg.TAXI_MIN_MESO;
        for (TaxiEdge taxi : TAXI_BY_MAP.getOrDefault(mapId, List.of())) {
            if (taxi.beginnerOnly() && !options.isBeginner()) {
                continue; // beginner-discount cab (Phil): only a beginner may ride it
            }
            if (taxi.minLevel() > 0 && options.riderLevel() < taxi.minLevel()) {
                continue; // level-gated ride (Shanks: lv7+ to leave Maple Island)
            }
            boolean continentRide = CONTINENT_RIDE_NPCS.contains(taxi.npcId());
            // Beginner cabs are exempt from the meso-shortcut gate: the 90% discount makes them cheap
            // enough that a broke beginner should still hop towns rather than walk.
            boolean gateOk = continentRide || taxi.beginnerOnly() || taxiShortcuts;
            if (gateOk && options.meso() >= taxi.fare()) {
                out.add(taxi.toMapId());
            }
        }
        if (options.withFerry()) {
            for (BotFerryManager.FerryRoute ferry : BotFerryManager.routesBoardingAt(mapId)) {
                if (options.meso() >= ferry.ticketCost()) {
                    out.add(ferry.destinationMapId());
                }
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
            // Return legs (one-way regions, Lith-anchored audit) -----------------------------------------
            // Snow Island: Dangerous Forest field -> Puro's boat dock 140020300, script enterPort (then
            // the Puro ferry returns to Lith). Without this the whole Rien/Snow Island is a can't-return trap.
            new ScriptedEntrance(140020200, "east00", 140020300),
            // Korean Folk Town: Fox Ridge -> KFT-side return map 222010200, script foxLaidy_map
            new ScriptedEntrance(222010300, "west00", 222010200),
            // Leafre: Cave of Life entrance -> Leafre field 240040600, script hontale_morph2
            new ScriptedEntrance(240040700, "out00", 240040600)
    );

    /** The scripted-entrance portal name to walk for a {@code fromMap -> destMap} hop, or null when that
     *  hop isn't a known scripted entrance. The travel executor enters it like a normal portal; its own
     *  script does the warp. */
    static String scriptedEntrancePortal(int fromMap, int destMap) {
        for (ScriptedEntrance e : SCRIPTED_ENTRANCES) {
            if (e.fromMap() == fromMap && e.destMap() == destMap) {
                return e.portalName();
            }
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
