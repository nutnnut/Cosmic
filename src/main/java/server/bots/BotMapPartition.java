package server.bots;

import server.maps.Portal;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * Per-map summary of which cross-map exits are reachable depending on which platform you ARRIVE on —
 * the cross-map glue on top of {@link BotNavigationGraph#canReach}, which is the SSOT for intra-map
 * (directed) reachability. This type does NOT re-derive reachability; it only composes {@code canReach}
 * verdicts into a compact, serializable form so the cross-map router can reason about split maps WITHOUT
 * loading every downstream nav graph.
 *
 * <p>Why "by arrival portal": when a bot enters a map it spawns at a specific portal (a specific
 * platform). On a split map (e.g. an upper deck you can down-jump off but not climb back to) the set of
 * exits it can then walk to depends entirely on which platform it landed on. So the unit is
 * {@code arrivalPortalName -> reachable cross-map exits}, every entry decided by {@code canReach} over the
 * walk-only (skillMask 0, the s100/j100 reference) edge graph.
 *
 * <p>The current map a bot stands on never needs this object — the live nav graph answers {@code canReach}
 * directly. It exists for DOWNSTREAM maps in a multi-hop route, whose graphs aren't loaded; it's persisted
 * (see {@link BotMapPartitionProvider}) so routing reads it without materializing those maps.
 */
final class BotMapPartition {

    private static final int NO_TARGET_MAPID = 999999999;

    /** A portal as the model needs it: where it stands, and where taking it lands you. */
    record PortalRef(String name, Point position, int targetMapId, String targetPortalName) {}

    // ---- shared portal eligibility (SSOT for "what the travel layer treats as a portal") ----------

    /** A bot-traversable targeted portal: open, not a town door, and backed by a real target map.
     *  Positive-target scripted portals are allowed because their own script still decides whether entry
     *  succeeds; script-only tm=999999999 portals stay allowlist-only. */
    static boolean isTravelPortal(Portal p) {
        int tm = p.getTargetMapId();
        return p.getPortalStatus()
                && p.getType() != Portal.DOOR_PORTAL
                && tm > 0
                && tm != NO_TARGET_MAPID;
    }

    /** A plain portal whose target is a real OTHER map — i.e. a walkable cross-map exit. */
    static boolean isTravelCrossMapPortal(Portal p, int currentMapId) {
        int tm = p.getTargetMapId();
        return tm != currentMapId && isTravelPortal(p);
    }

    final int mapId;
    private final List<PortalRef> exits;
    /** arrival portal name -> reachable exit names. Empty when {@link #fullyConnected}. */
    private final Map<String, Set<String>> reachableExitNamesByArrival;
    /** true when every arrival reaches every exit (the common, non-split case) — stored without the map. */
    final boolean fullyConnected;

    BotMapPartition(int mapId, List<PortalRef> exits,
                    Map<String, Set<String>> reachableExitNamesByArrival, boolean fullyConnected) {
        this.mapId = mapId;
        this.exits = List.copyOf(exits);
        Map<String, Set<String>> copy = new HashMap<>();
        reachableExitNamesByArrival.forEach((k, v) -> copy.put(k, Set.copyOf(v)));
        this.reachableExitNamesByArrival = Map.copyOf(copy);
        this.fullyConnected = fullyConnected;
    }

    /** A fully-connected partition (every arrival reaches every exit): the fallback for a map whose nav
     *  graph isn't warm yet, identical to today's map-level "any portal reaches any portal" assumption. */
    static BotMapPartition fullyConnected(int mapId, List<PortalRef> exits) {
        return new BotMapPartition(mapId, exits, Map.of(), true);
    }

    /** All cross-map exits of this map. */
    List<PortalRef> exits() {
        return exits;
    }

    /** Cross-map exits reachable after ARRIVING via {@code arrivalPortalName}. Optimistic (all exits) for
     *  a fully-connected map or an unknown arrival name — never falsely prunes a route. */
    List<PortalRef> usableExits(String arrivalPortalName) {
        if (fullyConnected) {
            return exits;
        }
        Set<String> names = reachableExitNamesByArrival.get(arrivalPortalName);
        if (names == null) {
            return exits; // unknown arrival -> degrade to map-level rather than strand the route
        }
        List<PortalRef> out = new ArrayList<>();
        for (PortalRef e : exits) {
            if (names.contains(e.name())) {
                out.add(e);
            }
        }
        return out;
    }

    // ---- derivation: compose canReach (the intra-map partition SSOT) into the per-arrival summary -----

    /**
     * Build the summary by asking {@link BotNavigationGraph#canReach} for each (arrival portal, exit
     * portal) pair — walk-only (skillMask 0). {@code allPortals} is every named portal (any can be an
     * arrival point); {@code exitNames} marks the plain cross-map exits (decided by the caller via
     * {@link #isTravelCrossMapPortal}); {@code regionResolver} maps a portal to the region it stands in
     * (production: {@link BotNavigationGraph#findRegionId}; tests inject region ids).
     */
    static BotMapPartition fromNavGraph(int mapId, BotNavigationGraph graph, List<PortalRef> allPortals,
                                        Set<String> exitNames, ToIntFunction<PortalRef> regionResolver) {
        Map<String, Integer> regionByName = new HashMap<>();
        for (PortalRef p : allPortals) {
            regionByName.put(p.name(), regionResolver.applyAsInt(p));
        }
        List<PortalRef> exits = new ArrayList<>();
        for (PortalRef p : allPortals) {
            if (exitNames.contains(p.name())) {
                exits.add(p);
            }
        }
        Map<String, Set<String>> reach = new HashMap<>();
        boolean fully = true;
        for (PortalRef arrival : allPortals) {
            int aReg = regionByName.getOrDefault(arrival.name(), -1);
            Set<String> reachable = new HashSet<>();
            for (PortalRef ex : exits) {
                int eReg = regionByName.getOrDefault(ex.name(), -1);
                // canReach is the SSOT; unknown region -> optimistic (its own "can't decide -> true").
                if (aReg < 0 || eReg < 0 || graph.canReach(aReg, eReg, 0)) {
                    reachable.add(ex.name());
                }
            }
            reach.put(arrival.name(), reachable);
            if (reachable.size() != exits.size()) {
                fully = false;
            }
        }
        return new BotMapPartition(mapId, exits, fully ? Map.of() : reach, fully);
    }

    // ---- on-disk serialization (one TSV row per map) -----------------------------------------------
    // Columns: mapId | fullyConnected(0/1) | exits(name>tm>tn;...) | [arrival=ex|ex;... — only when NOT
    //          fully connected]. Assumes portal names are simple ASCII tokens (no tab/semicolon/'>'/'='/'|'),
    //          which MapleStory portal names are.

    String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append(mapId).append('\t').append(fullyConnected ? 1 : 0).append('\t');
        boolean first = true;
        for (PortalRef e : exits) {
            if (!first) {
                sb.append(';');
            }
            sb.append(e.name()).append('>').append(e.targetMapId()).append('>')
                    .append(e.targetPortalName() == null ? "" : e.targetPortalName());
            first = false;
        }
        sb.append('\t');
        if (!fullyConnected) {
            first = true;
            for (Map.Entry<String, Set<String>> en : reachableExitNamesByArrival.entrySet()) {
                if (!first) {
                    sb.append(';');
                }
                sb.append(en.getKey()).append('=');
                boolean f2 = true;
                for (String name : en.getValue()) {
                    if (!f2) {
                        sb.append('|');
                    }
                    sb.append(name);
                    f2 = false;
                }
                first = false;
            }
        }
        return sb.toString();
    }

    static BotMapPartition deserialize(String line) {
        String[] c = line.split("\t", -1);
        int mapId = Integer.parseInt(c[0]);
        boolean fully = "1".equals(c[1]);

        List<PortalRef> exits = new ArrayList<>();
        if (!c[2].isEmpty()) {
            for (String entry : c[2].split(";")) {
                String[] f = entry.split(">", -1);
                exits.add(new PortalRef(f[0], null, Integer.parseInt(f[1]), f[2]));
            }
        }

        Map<String, Set<String>> reach = new HashMap<>();
        if (!fully && c.length > 3 && !c[3].isEmpty()) {
            for (String entry : c[3].split(";")) {
                int eq = entry.indexOf('=');
                String arrival = entry.substring(0, eq);
                Set<String> names = new HashSet<>();
                String body = entry.substring(eq + 1);
                if (!body.isEmpty()) {
                    for (String n : body.split("\\|")) {
                        names.add(n);
                    }
                }
                reach.put(arrival, names);
            }
        }
        return new BotMapPartition(mapId, exits, reach, fully);
    }
}
