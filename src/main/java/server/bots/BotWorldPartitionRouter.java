package server.bots;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/**
 * Component-aware cross-map routing over {@link BotMapPartition}s. Where {@link BotWorldGraph} routes
 * map-to-map (assuming any portal in a map is reachable from any other), this routes by ARRIVAL PLATFORM:
 * a node is {@code (mapId, arrivalPortalName)}, and a map's reachable exits depend on which platform you
 * landed on. So a bot stranded on a platform it can't walk off is sent the long way round (out a reachable
 * portal, back via one whose arrival platform it needs) instead of being promised a portal it can't reach.
 *
 * <p>The START is the bot's live position on its current map — its reachable exits are supplied directly
 * (computed by the caller via {@link BotNavigationGraph#canReach}, the SSOT, on the loaded graph), so the
 * current map needs no partition. Downstream maps are expanded from their (persisted) partitions. Two
 * platforms of the same map are distinct nodes, so a correct route may re-enter a map it already left.
 */
final class BotWorldPartitionRouter {

    private BotWorldPartitionRouter() {}

    /** A travel node: a specific arrival platform (portal) within a map. */
    record Node(int mapId, String arrivalPortal) {}

    /**
     * Shortest route from the bot's current map to ANY platform of {@code toMapId}, as the sequence of
     * nodes to enter (ending on {@code toMapId}). {@code startExits} are the cross-map exits the bot can
     * actually reach from where it stands right now. Empty when already on the target map; null when
     * unreachable within {@code maxHops} hops or a needed downstream partition is missing.
     */
    static List<Node> route(IntFunction<BotMapPartition> partitionProvider, int fromMapId,
                            List<BotMapPartition.PortalRef> startExits, int toMapId, int maxHops) {
        return route(partitionProvider, fromMapId, startExits, toMapId, maxHops, m -> false);
    }

    /**
     * Like {@link #route(IntFunction, int, List, int, int)} but refuses to path INTO any map {@code blocked}
     * accepts — the same SSOT danger gate ({@code BotAutopilotManager.routeBlockFor}) the map-level route
     * uses, so a fragile bot is never routed through e.g. Sleepywood / a return-map trap.
     */
    static List<Node> route(IntFunction<BotMapPartition> partitionProvider, int fromMapId,
                            List<BotMapPartition.PortalRef> startExits, int toMapId, int maxHops,
                            IntPredicate blocked) {
        if (fromMapId == toMapId) {
            return List.of();
        }
        if (maxHops <= 0) {
            return null;
        }
        Node start = new Node(fromMapId, null); // sentinel: the bot's live position, not a real arrival
        Map<Node, Node> cameFrom = new HashMap<>();
        cameFrom.put(start, start);
        ArrayDeque<Node> frontier = new ArrayDeque<>();
        frontier.add(start);

        int depth = 0;
        while (!frontier.isEmpty() && depth < maxHops) {
            depth++;
            for (int level = frontier.size(); level > 0; level--) {
                Node current = frontier.poll();
                List<BotMapPartition.PortalRef> exits;
                if (current.equals(start)) {
                    exits = startExits;
                } else {
                    BotMapPartition part = partitionProvider.apply(current.mapId());
                    if (part == null) {
                        continue; // missing downstream partition -> dead end (caller falls back to map-level)
                    }
                    exits = part.usableExits(current.arrivalPortal());
                }
                for (BotMapPartition.PortalRef exit : exits) {
                    Node next = new Node(exit.targetMapId(), exit.targetPortalName());
                    if (blocked.test(next.mapId())) {
                        continue; // danger gate: never path INTO a blocked map -> any route through it is pruned
                    }
                    if (cameFrom.putIfAbsent(next, current) != null) {
                        continue;
                    }
                    if (next.mapId() == toMapId) {
                        return reconstruct(cameFrom, start, next);
                    }
                    frontier.add(next);
                }
            }
        }
        return null;
    }

    private static List<Node> reconstruct(Map<Node, Node> cameFrom, Node start, Node goal) {
        List<Node> hops = new ArrayList<>();
        for (Node at = goal; !at.equals(start); at = cameFrom.get(at)) {
            hops.add(at);
        }
        Collections.reverse(hops);
        return List.copyOf(hops);
    }
}
