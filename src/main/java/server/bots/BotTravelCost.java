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
    /** Penalty floor: even the far side of the world keeps a quarter of its score. */
    static final double MIN_SCORE_WEIGHT = 0.25;

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
            if (options.withFerry()) {
                BotFerryManager.FerryRoute ferry = BotFerryManager.routeBoardingAt(mapId);
                if (ferry != null && options.meso() >= ferry.ticketCost()) {
                    offer(frontier, seconds, ferry.destinationMapId(), cost + ferrySeconds, hops);
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
     *  the flood (unreachable under these options) get the floor. */
    static double scoreWeight(Map<Integer, Double> travelSeconds, int mapId) {
        Double s = travelSeconds.get(mapId);
        double sec = s != null ? s : HORIZON_SECONDS;
        return Math.max(MIN_SCORE_WEIGHT, 1.0 - sec / HORIZON_SECONDS);
    }
}
