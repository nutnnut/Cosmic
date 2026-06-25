package server.bots;

import server.maps.Foothold;
import server.maps.MapleMap;

import java.awt.*;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BotNavigationGraph implements Serializable {
    // Cached nav graphs are serialized to disk. Keep explicit serialVersionUIDs so
    // harmless method-only edits do not break cache loading; use GRAPH_VERSION for
    // intentional cache invalidation when the serialized data shape changes.
    @Serial
    private static final long serialVersionUID = 1L;

    enum EdgeType {
        WALK,
        JUMP,
        DROP,
        CLIMB,
        PORTAL,
        TELEPORT,
        FLASH_JUMP
    }

    static final class Segment implements Serializable {
        // Part of the on-disk BotNavigationGraph cache schema; do not remove.
        @Serial
        private static final long serialVersionUID = 1L;

        final int footholdId;
        final int x1;
        final int y1;
        final int x2;
        final int y2;
        final int minX;
        final int maxX;
        final boolean forbidFallDown;
        final boolean collidableFromBelow;

        Segment(Foothold foothold) {
            this(foothold, false);
        }

        Segment(Foothold foothold, boolean collidableFromBelow) {
            this.footholdId = foothold.getId();
            this.x1 = foothold.getX1();
            this.y1 = foothold.getY1();
            this.x2 = foothold.getX2();
            this.y2 = foothold.getY2();
            this.minX = Math.min(x1, x2);
            this.maxX = Math.max(x1, x2);
            this.forbidFallDown = foothold.isForbidFallDown();
            this.collidableFromBelow = collidableFromBelow;
        }

        boolean containsX(int x) {
            return x >= minX && x <= maxX;
        }

        int clampX(int x) {
            if (x < minX) {
                return minX;
            }
            if (x > maxX) {
                return maxX;
            }
            return x;
        }

        Point pointAt(int x) {
            int clampedX = clampX(x);
            if (x1 == x2) {
                return new Point(clampedX, Math.min(y1, y2));
            }

            double ratio = (clampedX - x1) / (double) (x2 - x1);
            int y = (int) Math.round(y1 + (y2 - y1) * ratio);
            return new Point(clampedX, y);
        }
    }

    static final class Region implements Serializable {
        // Part of the on-disk BotNavigationGraph cache schema; do not remove.
        @Serial
        private static final long serialVersionUID = 1L;

        final int id;
        final List<Segment> segments;
        final int minX;
        final int maxX;
        final int minY;
        final int maxY;
        final boolean isRopeRegion;
        final boolean isLadder;

        Region(int id, List<Segment> segments) {
            if (segments.isEmpty()) {
                throw new IllegalArgumentException("Bot nav region requires at least one segment");
            }

            this.id = id;
            this.segments = new ArrayList<>(segments);
            this.isRopeRegion = false;
            this.isLadder = false;

            int regionMinX = Integer.MAX_VALUE;
            int regionMaxX = Integer.MIN_VALUE;
            int regionMinY = Integer.MAX_VALUE;
            int regionMaxY = Integer.MIN_VALUE;
            for (Segment segment : segments) {
                regionMinX = Math.min(regionMinX, segment.minX);
                regionMaxX = Math.max(regionMaxX, segment.maxX);
                regionMinY = Math.min(regionMinY, Math.min(segment.y1, segment.y2));
                regionMaxY = Math.max(regionMaxY, Math.max(segment.y1, segment.y2));
            }

            this.minX = regionMinX;
            this.maxX = regionMaxX;
            this.minY = regionMinY;
            this.maxY = regionMaxY;
        }

        Region(int id, int ropeX, int topY, int bottomY, boolean isLadder) {
            this.id = id;
            this.segments = List.of();
            this.isRopeRegion = true;
            this.isLadder = isLadder;
            this.minX = ropeX;
            this.maxX = ropeX;
            this.minY = topY;
            this.maxY = bottomY;
        }

        int width() {
            return Math.max(0, maxX - minX);
        }

        int height() {
            return Math.max(0, maxY - minY);
        }

        Point leftPoint() {
            return pointAt(minX);
        }

        Point centerPoint() {
            if (isRopeRegion) {
                return new Point(minX, minY + height() / 2);
            }
            return pointAt(minX + width() / 2);
        }

        Point rightPoint() {
            return pointAt(maxX);
        }

        Point pointAt(int x) {
            if (isRopeRegion) {
                return new Point(minX, minY + height() / 2);
            }
            Segment bestSegment = findBestSegment(x);
            return bestSegment.pointAt(x);
        }

        boolean isForbidFallDownAt(int x) {
            if (isRopeRegion || segments.isEmpty()) {
                return false;
            }
            return findBestSegment(x).forbidFallDown;
        }

        private Segment findBestSegment(int x) {
            Segment best = segments.get(0);
            int bestDistance = distanceToSegment(best, x);
            for (int i = 1; i < segments.size(); i++) {
                Segment segment = segments.get(i);
                int distance = distanceToSegment(segment, x);
                if (distance < bestDistance) {
                    best = segment;
                    bestDistance = distance;
                }
            }
            return best;
        }

        private int distanceToSegment(Segment segment, int x) {
            if (segment.containsX(x)) {
                return 0;
            }
            return x < segment.minX ? segment.minX - x : x - segment.maxX;
        }
    }

    static final class Edge implements Serializable {
        // Part of the on-disk BotNavigationGraph cache schema; do not remove.
        @Serial
        private static final long serialVersionUID = 1L;

        final int fromRegionId;
        final int toRegionId;
        final EdgeType type;
        final Point startPoint;
        final Point endPoint;
        final int launchMinX;
        final int launchMaxX;
        final int launchStepX;
        final int portalId;
        final int ropeX;
        final int ropeTopY;
        final int ropeBottomY;
        final int cost;

        Edge(int fromRegionId,
             int toRegionId,
             EdgeType type,
             Point startPoint,
             Point endPoint,
             int launchMinX,
             int launchMaxX,
             int launchStepX,
             int portalId,
             int ropeX,
             int ropeTopY,
             int ropeBottomY,
             int cost) {
            this.fromRegionId = fromRegionId;
            this.toRegionId = toRegionId;
            this.type = type;
            this.startPoint = new Point(startPoint);
            this.endPoint = new Point(endPoint);
            this.launchMinX = Math.min(launchMinX, launchMaxX);
            this.launchMaxX = Math.max(launchMinX, launchMaxX);
            this.launchStepX = launchStepX;
            this.portalId = portalId;
            this.ropeX = ropeX;
            this.ropeTopY = ropeTopY;
            this.ropeBottomY = ropeBottomY;
            this.cost = cost;
        }

        Edge(int fromRegionId,
             int toRegionId,
             EdgeType type,
             Point startPoint,
             Point endPoint,
             int launchStepX,
             int portalId,
             int ropeX,
             int ropeTopY,
             int ropeBottomY,
             int cost) {
            this(fromRegionId, toRegionId, type, startPoint, endPoint,
                    startPoint.x, startPoint.x, launchStepX, portalId, ropeX, ropeTopY, ropeBottomY, cost);
        }

        boolean containsLaunchX(int x) {
            return x >= launchMinX && x <= launchMaxX;
        }

        boolean containsLaunchX(int x, int tolerance) {
            return x >= launchMinX - tolerance && x <= launchMaxX + tolerance;
        }

        /** Launch point at the in-window x nearest to {@code x} (the x execution would actually fire from). */
        Point pointAtNearestLaunchX(int x) {
            return new Point(Math.clamp(x, launchMinX, launchMaxX), startPoint.y);
        }
    }

    final int mapId;
    final int version;
    final BotMovementProfile movementProfile;
    final List<Region> regions;
    final Map<Integer, Region> regionsById;
    final Map<Integer, Integer> regionIdByFootholdId;
    final Map<Integer, List<Edge>> outgoingByRegionId;
    final java.util.Set<Integer> collidableWallIds;
    final java.util.Set<Integer> collidableFromBelowIds;

    BotNavigationGraph(int mapId,
                       int version,
                       BotMovementProfile movementProfile,
                       List<Region> regions,
                       Map<Integer, Region> regionsById,
                       Map<Integer, Integer> regionIdByFootholdId,
                       Map<Integer, List<Edge>> outgoingByRegionId,
                       java.util.Set<Integer> collidableWallIds) {
        this(mapId, version, movementProfile, regions, regionsById, regionIdByFootholdId, outgoingByRegionId, collidableWallIds, java.util.Set.of());
    }

    BotNavigationGraph(int mapId,
                       int version,
                       BotMovementProfile movementProfile,
                       List<Region> regions,
                       Map<Integer, Region> regionsById,
                       Map<Integer, Integer> regionIdByFootholdId,
                       Map<Integer, List<Edge>> outgoingByRegionId,
                       java.util.Set<Integer> collidableWallIds,
                       java.util.Set<Integer> collidableFromBelowIds) {
        this.mapId = mapId;
        this.version = version;
        this.movementProfile = movementProfile;
        this.regions = new ArrayList<>(regions);
        this.regionsById = new HashMap<>(regionsById);
        this.regionIdByFootholdId = new HashMap<>(regionIdByFootholdId);
        this.outgoingByRegionId = new HashMap<>();
        for (Map.Entry<Integer, List<Edge>> entry : outgoingByRegionId.entrySet()) {
            this.outgoingByRegionId.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        this.collidableWallIds = new java.util.HashSet<>(collidableWallIds);
        this.collidableFromBelowIds = new java.util.HashSet<>(collidableFromBelowIds);
    }

    BotNavigationGraph(int mapId,
                       int version,
                       List<Region> regions,
                       Map<Integer, Region> regionsById,
                       Map<Integer, Integer> regionIdByFootholdId,
                       Map<Integer, List<Edge>> outgoingByRegionId,
                       java.util.Set<Integer> collidableWallIds) {
        this(mapId, version, BotMovementProfile.base(), regions, regionsById, regionIdByFootholdId, outgoingByRegionId, collidableWallIds, java.util.Set.of());
    }

    BotNavigationGraph(int mapId,
                       int version,
                       List<Region> regions,
                       Map<Integer, Region> regionsById,
                       Map<Integer, Integer> regionIdByFootholdId,
                       Map<Integer, List<Edge>> outgoingByRegionId,
                       java.util.Set<Integer> collidableWallIds,
                       java.util.Set<Integer> collidableFromBelowIds) {
        this(mapId, version, BotMovementProfile.base(), regions, regionsById, regionIdByFootholdId, outgoingByRegionId, collidableWallIds, collidableFromBelowIds);
    }

    Region getRegion(int regionId) {
        return regionsById.get(regionId);
    }

    List<Edge> getOutgoing(int regionId) {
        return outgoingByRegionId.getOrDefault(regionId, List.of());
    }

    // --- Connected-component (island) index (runtime-only; lazy, transient per graph) -------------
    // Undirected connected components of the region graph, so a pathfind can early-exit when start and
    // target are in different islands (no possible route) instead of scanning the whole graph to prove
    // it. Two variants: base EXCLUDES the skill-gated TELEPORT/FLASH_JUMP edges (which can bridge
    // walk-islands), skill INCLUDES them -- a walk-only search uses base, a skill-enabled search uses
    // skill. Undirected is conservative: different island => unreachable both directions; same island
    // may still be directionally unreachable (the search/edge-check cap handles that case).
    private transient volatile Map<Integer, Integer> baseComponentByRegion;
    private transient volatile Map<Integer, Integer> skillComponentByRegion;

    /** Island id of {@code regionId}; {@code withSkills} includes TELEPORT/FLASH_JUMP edges. Two regions
     *  with different ids have NO route between them for that edge set. Returns -1 for an unknown region
     *  (caller should not early-exit on -1). Lazily computed once per graph instance. */
    int connectedComponentId(int regionId, boolean withSkills) {
        Map<Integer, Integer> comp = withSkills ? skillComponentByRegion : baseComponentByRegion;
        if (comp == null) {
            synchronized (this) {
                comp = withSkills ? skillComponentByRegion : baseComponentByRegion;
                if (comp == null) {
                    comp = computeComponents(withSkills);
                    if (withSkills) {
                        skillComponentByRegion = comp;
                    } else {
                        baseComponentByRegion = comp;
                    }
                }
            }
        }
        return comp.getOrDefault(regionId, -1);
    }

    private Map<Integer, Integer> computeComponents(boolean withSkills) {
        Map<Integer, Integer> parent = new HashMap<>(regions.size() * 2);
        for (Region r : regions) {
            parent.put(r.id, r.id);
        }
        for (Region r : regions) {
            for (Edge e : getOutgoing(r.id)) {
                if (!withSkills && (e.type == EdgeType.TELEPORT || e.type == EdgeType.FLASH_JUMP)) {
                    continue;
                }
                unionComponents(parent, e.fromRegionId, e.toRegionId);
            }
        }
        Map<Integer, Integer> comp = new HashMap<>(parent.size() * 2);
        for (Integer id : parent.keySet()) {
            comp.put(id, findComponent(parent, id));
        }
        return comp;
    }

    private static int findComponent(Map<Integer, Integer> parent, int x) {
        int root = x;
        while (parent.get(root) != root) {
            root = parent.get(root);
        }
        while (parent.get(x) != root) { // path compression
            int next = parent.get(x);
            parent.put(x, root);
            x = next;
        }
        return root;
    }

    private static void unionComponents(Map<Integer, Integer> parent, int a, int b) {
        if (!parent.containsKey(a) || !parent.containsKey(b)) {
            return; // edge referencing an unknown region id; ignore for connectivity
        }
        int ra = findComponent(parent, a);
        int rb = findComponent(parent, b);
        if (ra != rb) {
            parent.put(ra, rb);
        }
    }

    // --- Lazy region-route cache (runtime-only; populated by BotNavigationManager.findNextEdge) ---
    // Holds the next-hop edge per (startRegion, targetRegion, routeBucket). Transient by design:
    // rebuilt per graph instance, so it dies with the graph version (no GRAPH_VERSION bump, no disk).
    // Buckets give crowd de-stacking without per-bot searches (see BotNavigationManager.ROUTE_BUCKETS).
    // ponytail: unbounded; bounded by regionPairs*buckets in practice, LRU only if a map ever blows up.
    static final Edge NO_EDGE = new Edge(-1, -1, EdgeType.WALK, new Point(), new Point(), 0, -1, 0, 0, 0, 0);
    private transient volatile Map<Long, Edge[]> routeCache;
    transient volatile boolean portalRoutesWarmed;
    private transient volatile List<Integer> portalRegionIds;

    private Map<Long, Edge[]> routeCache() {
        Map<Long, Edge[]> c = routeCache;
        if (c == null) {
            synchronized (this) {
                c = routeCache;
                if (c == null) {
                    c = new java.util.concurrent.ConcurrentHashMap<>();
                    routeCache = c;
                }
            }
        }
        return c;
    }

    private static long routeKey(int startRegionId, int targetRegionId) {
        return ((long) startRegionId << 32) | (targetRegionId & 0xffffffffL);
    }

    // Hit/miss counters for A/B-ing the route cache (did it actually save A* calls?). Static + cumulative
    // since server start, so they survive graph rebuilds — exactly what a session-long measurement wants.
    // Exposed on /api/botdebug as "routeCache". ponytail: no reset endpoint, restart the server to zero them.
    static final java.util.concurrent.atomic.LongAdder cacheHits = new java.util.concurrent.atomic.LongAdder();
    static final java.util.concurrent.atomic.LongAdder cacheMisses = new java.util.concurrent.atomic.LongAdder();

    /** Cached next hop ({@link #NO_EDGE} = direct walk), or {@code null} if not computed for this (pair, bucket). */
    Edge cachedNextHop(int startRegionId, int targetRegionId, int bucket) {
        Edge[] slots = routeCache().get(routeKey(startRegionId, targetRegionId));
        Edge hop = slots == null ? null : slots[bucket];
        (hop == null ? cacheMisses : cacheHits).increment();
        return hop;
    }

    /** {@code {"hits":N,"misses":M,"rate":0.xx}} — cumulative cache effectiveness for /api/botdebug. */
    static String routeCacheStatsJson() {
        long h = cacheHits.sum(), m = cacheMisses.sum(), t = h + m;
        return "{\"hits\":" + h + ",\"misses\":" + m + ",\"rate\":" + (t == 0 ? 0 : (double) h / t) + "}";
    }

    void putNextHop(int startRegionId, int targetRegionId, int bucket, int bucketCount, Edge edge) {
        routeCache().computeIfAbsent(routeKey(startRegionId, targetRegionId), k -> new Edge[bucketCount])[bucket] = edge;
    }

    /** Distinct regions that contain a portal (PORTAL edge sources); the hubs warmed at first use. */
    List<Integer> portalRegionIds() {
        List<Integer> ids = portalRegionIds;
        if (ids == null) {
            Set<Integer> set = new java.util.LinkedHashSet<>();
            for (List<Edge> edges : outgoingByRegionId.values()) {
                for (Edge e : edges) {
                    if (e.type == EdgeType.PORTAL) {
                        set.add(e.fromRegionId);
                    }
                }
            }
            ids = new ArrayList<>(set);
            portalRegionIds = ids;
        }
        return ids;
    }

    boolean hasInterRegionEdge(int fromRegionId, int toRegionId) {
        for (Edge edge : getOutgoing(fromRegionId)) {
            if (edge.fromRegionId != edge.toRegionId && edge.toRegionId == toRegionId) {
                return true;
            }
        }
        return false;
    }

    Set<Integer> getMutualAdjacentRegionIds(int regionId) {
        Set<Integer> adjacent = new HashSet<>();
        for (Edge edge : getOutgoing(regionId)) {
            if (edge.fromRegionId == edge.toRegionId) {
                continue;
            }
            if (hasInterRegionEdge(edge.toRegionId, regionId)) {
                adjacent.add(edge.toRegionId);
            }
        }
        return adjacent;
    }

    int findRegionId(MapleMap map, Point position) {
        if (position == null || map.getFootholds() == null) {
            return -1;
        }

        Foothold foothold = BotPhysicsEngine.findGroundFoothold(map, position);
        if (foothold != null) {
            int regionId = regionIdByFootholdId.getOrDefault(foothold.getId(), -1);
            if (regionId >= 0) {
                return regionId;
            }
        }

        return findRopeRegionId(position);
    }

    int findRopeRegionId(Point position) {
        for (Region region : regions) {
            if (!region.isRopeRegion) {
                continue;
            }
            if (Math.abs(position.x - region.minX) <= BotPhysicsEngine.cfg.ROPE_GRAB_X
                    && position.y >= region.minY
                    && position.y <= region.maxY) {
                return region.id;
            }
        }
        return -1;
    }
}
