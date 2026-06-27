package server.bots;

import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.IntToLongFunction;

/**
 * Runtime travel-time model: how long would the bot take to get anywhere? A uniform-cost
 * (Dijkstra) flood over the same edge kinds {@link BotWorldGraph} routes on — portal hops,
 * return scrolls, taxis and ferries, gated by the same {@link BotWorldGraph.RouteOptions} —
 * with per-edge time estimates instead of hop counts.
 *
 * <p>Ferry time depends on the world's <b>runtime-mutable</b> {@code travelrate} config, so it
 * is computed per query through the supplied {@code transportationTime} function (production:
 * {@code ms -> world.getTransportationTime(ms)}) and never cached or baked into any graph.
 * The Boats event departs 5 minutes after docking and rides for 10, both travelrate-scaled,
 * so the expected ferry cost is half the departure window (average wait) plus the ride.
 *
 * <p>{@link #scoreWeight} turns the flooded seconds into the autopilot's score multiplier:
 * full value nearby, decaying linearly over a 1-hour horizon to a floor — a ~17 min ferry
 * trip keeps ~70% of the destination's value, so genuinely better continents still win.
 */
final class BotTravelCost {

    /** Walking across one map to its exit portal. */
    static final double PORTAL_HOP_SECONDS = 25.0;
    /** Pulling out and using a return scroll. */
    static final double SCROLL_SECONDS = 5.0;
    /** Walking to the cab NPC and taking the ride. */
    static final double TAXI_SECONDS = 30.0;
    /** Travel penalty horizon: a map this far away keeps only the floor weight. */
    static final double HORIZON_SECONDS = 3600.0;

    /** Below this level the travel penalty is amplified (a fragile bot shouldn't trek across the
     *  world); at/above it travel is costed normally. */
    static final int TRAVEL_RISK_LEVEL = 30;
    /** Multiplier on effective travel time at level 1 — every travel-second feels this many times
     *  costlier, so a low-level bot strongly prefers nearby grind spots. Eases LINEARLY to 1.0 by
     *  {@link #TRAVEL_RISK_LEVEL}. */
    static final double MAX_LOW_LEVEL_TRAVEL_RISK = 4.0;

    private BotTravelCost() {}

    /** Estimated seconds from one map to every reachable map (production world graph). */
    static Map<Integer, Double> floodSeconds(int fromMapId, int maxHops,
                                             BotWorldGraph.RouteOptions options,
                                             IntToLongFunction transportationTime) {
        return floodSeconds(BotWorldGraph.get(), fromMapId, maxHops, options, transportationTime);
    }

    /** Uniform-cost flood over an explicit graph; includes fromMapId itself at 0 seconds. */
    static Map<Integer, Double> floodSeconds(BotWorldGraph.Index graph, int fromMapId, int maxHops,
                                             BotWorldGraph.RouteOptions options,
                                             IntToLongFunction transportationTime) {
        double ferrySeconds = ferrySeconds(transportationTime);
        Map<Integer, Double> seconds = new HashMap<>();
        // {seconds, mapId, hops} ordered by seconds — settle each map at its cheapest time.
        PriorityQueue<double[]> frontier = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        frontier.add(new double[]{0.0, fromMapId, 0});
        while (!frontier.isEmpty()) {
            double[] current = frontier.poll();
            double cost = current[0];
            int mapId = (int) current[1];
            int hops = (int) current[2];
            if (seconds.putIfAbsent(mapId, cost) != null) {
                continue; // already settled cheaper
            }
            if (hops >= maxHops) {
                continue;
            }
            for (int next : graph.neighbors(mapId)) {
                offer(frontier, seconds, next, cost + PORTAL_HOP_SECONDS, hops);
            }
            if (options.withReturnScroll()) {
                int scrollTarget = graph.scrollTarget(mapId);
                if (scrollTarget != -1) {
                    offer(frontier, seconds, scrollTarget, cost + SCROLL_SECONDS, hops);
                }
            }
            for (BotWorldGraph.TaxiEdge taxi : BotWorldGraph.taxiEdgesFrom(mapId)) {
                if (options.meso() >= taxi.fare()) {
                    offer(frontier, seconds, taxi.toMapId(), cost + TAXI_SECONDS, hops);
                }
            }
            // Spinel's free ride back out of the shrine to the saved WORLDTOUR origin (mirrors
            // BotWorldGraph.expand): present only for the bot standing here, so it's costed as the
            // first hop back to where it boarded — never a through shortcut to Lith Harbor.
            if (mapId == BotWorldGraph.MUSHROOM_SHRINE && options.worldTourReturn() != -1) {
                offer(frontier, seconds, options.worldTourReturn(), cost + TAXI_SECONDS, hops);
            }
            if (options.withFerry()) {
                for (BotFerryManager.FerryRoute ferry : BotFerryManager.routesBoardingAt(mapId)) {
                    if (options.meso() >= ferry.ticketCost()) {
                        offer(frontier, seconds, ferry.destinationMapId(), cost + ferrySeconds, hops);
                    }
                }
            }
        }
        return seconds;
    }

    private static void offer(PriorityQueue<double[]> frontier, Map<Integer, Double> settled,
                              int mapId, double cost, int hopsSoFar) {
        if (!settled.containsKey(mapId)) {
            frontier.add(new double[]{cost, mapId, hopsSoFar + 1});
        }
    }

    /** Expected ferry time: half the 5-min departure window (average wait) + the 10-min ride,
     *  both through the runtime travelrate. */
    static double ferrySeconds(IntToLongFunction transportationTime) {
        return transportationTime.applyAsLong(300_000) / 2000.0
                + transportationTime.applyAsLong(600_000) / 1000.0;
    }

    /** The travel penalty for a candidate map: multiply its score by this. Maps missing from
     *  the flood (unreachable under these options) get the floor. Level-neutral. */
    static double scoreWeight(Map<Integer, Double> travelSeconds, int mapId) {
        return scoreWeight(travelSeconds, mapId, TRAVEL_RISK_LEVEL);
    }

    /** Level-scaled travel penalty at full strength (no wanderlust discount). */
    static double scoreWeight(Map<Integer, Double> travelSeconds, int mapId, int botLevel) {
        return scoreWeight(travelSeconds, mapId, botLevel, 1.0);
    }

    /** Level-scaled travel penalty: a low-level bot treats every travel-second as
     *  {@link #travelRiskFactor} times costlier, so it prefers nearby grind spots and won't trek
     *  across the world while fragile. Eases LINEARLY to the level-neutral cost by
     *  {@link #TRAVEL_RISK_LEVEL}.
     *
     *  <p>{@code travelDiscount} (1.0 = normal) shrinks effective travel time for an occasional
     *  "wanderlust" decision, so distant maps keep most of their value and grind quality, not
     *  proximity, drives the pick. Hazard/level avoidance is enforced separately (the reachable-set
     *  prune + per-mob danger scoring), so a discounted decision still never routes into danger. */
    static double scoreWeight(Map<Integer, Double> travelSeconds, int mapId, int botLevel, double travelDiscount) {
        Double s = travelSeconds.get(mapId);
        double sec = s != null ? s : HORIZON_SECONDS;
        double effectiveSec = sec * travelRiskFactor(botLevel) * travelDiscount;
        return Math.max(BotManager.cfg.TRAVEL_PENALTY_FLOOR, 1.0 - effectiveSec / HORIZON_SECONDS);
    }

    /** Linear travel-risk multiplier: {@link #MAX_LOW_LEVEL_TRAVEL_RISK} at level 1, easing straight
     *  down to 1.0 at {@link #TRAVEL_RISK_LEVEL} and staying 1.0 above it. */
    static double travelRiskFactor(int botLevel) {
        if (botLevel >= TRAVEL_RISK_LEVEL) {
            return 1.0;
        }
        double belowFrac = (TRAVEL_RISK_LEVEL - botLevel) / (double) TRAVEL_RISK_LEVEL;
        return 1.0 + belowFrac * (MAX_LOW_LEVEL_TRAVEL_RISK - 1.0);
    }
}
