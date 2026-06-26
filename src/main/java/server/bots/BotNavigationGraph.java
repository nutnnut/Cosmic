package server.bots;

import server.maps.Foothold;
import server.maps.MapleMap;

import java.awt.*;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
        // Y launch window — the rope-climb analogue of [launchMinX, launchMaxX]. A rope-exit CLIMB edge
        // can fire from any climb height in [launchMinY, launchMaxY] (all land in toRegionId, verified at
        // graph-gen), exactly as a ground JUMP fires from any x in the X window. For non-rope edges this
        // degenerates to startPoint.y (a single height).
        final int launchMinY;
        final int launchMaxY;
        final int launchStepX;
        final int portalId;
        final int ropeX;
        final int ropeTopY;
        final int ropeBottomY;
        // Fall/grab cost at the two Y-window endpoints. Rope-exit cost varies strongly with launch height
        // (higher launch = longer fall = pricier), unlike ground jumps whose cost is ~constant across the X
        // window. The search interpolates launchCostAt(launchY) at the bot's actual climb height; the
        // reverse-Dijkstra heuristic uses minLaunchCost() (the cheapest launch — admissible). Degenerate
        // (non-rope-window) edges set both = cost.
        final int launchCostMinY;
        final int launchCostMaxY;
        final int cost;

        Edge(int fromRegionId,
             int toRegionId,
             EdgeType type,
             Point startPoint,
             Point endPoint,
             int launchMinX,
             int launchMaxX,
             int launchMinY,
             int launchMaxY,
             int launchStepX,
             int portalId,
             int ropeX,
             int ropeTopY,
             int ropeBottomY,
             int launchCostAtMinY,
             int launchCostAtMaxY,
             int cost) {
            this.fromRegionId = fromRegionId;
            this.toRegionId = toRegionId;
            this.type = type;
            this.startPoint = new Point(startPoint);
            this.endPoint = new Point(endPoint);
            this.launchMinX = Math.min(launchMinX, launchMaxX);
            this.launchMaxX = Math.max(launchMinX, launchMaxX);
            boolean yInOrder = launchMinY <= launchMaxY;
            this.launchMinY = Math.min(launchMinY, launchMaxY);
            this.launchMaxY = Math.max(launchMinY, launchMaxY);
            // Keep each endpoint cost aligned with its (normalized) Y endpoint if the args were swapped.
            this.launchCostMinY = yInOrder ? launchCostAtMinY : launchCostAtMaxY;
            this.launchCostMaxY = yInOrder ? launchCostAtMaxY : launchCostAtMinY;
            this.launchStepX = launchStepX;
            this.portalId = portalId;
            this.ropeX = ropeX;
            this.ropeTopY = ropeTopY;
            this.ropeBottomY = ropeBottomY;
            this.cost = cost;
        }

        /** Y-windowed edge with a flat cost (cost the same at both endpoints). */
        Edge(int fromRegionId,
             int toRegionId,
             EdgeType type,
             Point startPoint,
             Point endPoint,
             int launchMinX,
             int launchMaxX,
             int launchMinY,
             int launchMaxY,
             int launchStepX,
             int portalId,
             int ropeX,
             int ropeTopY,
             int ropeBottomY,
             int cost) {
            this(fromRegionId, toRegionId, type, startPoint, endPoint,
                    launchMinX, launchMaxX, launchMinY, launchMaxY,
                    launchStepX, portalId, ropeX, ropeTopY, ropeBottomY, cost, cost, cost);
        }

        /** X-windowed edge (JUMP/DROP); the Y window degenerates to startPoint.y. */
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
            this(fromRegionId, toRegionId, type, startPoint, endPoint,
                    launchMinX, launchMaxX, startPoint.y, startPoint.y,
                    launchStepX, portalId, ropeX, ropeTopY, ropeBottomY, cost);
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
                    startPoint.x, startPoint.x, startPoint.y, startPoint.y,
                    launchStepX, portalId, ropeX, ropeTopY, ropeBottomY, cost);
        }

        boolean containsLaunchX(int x) {
            return x >= launchMinX && x <= launchMaxX;
        }

        boolean containsLaunchX(int x, int tolerance) {
            return x >= launchMinX - tolerance && x <= launchMaxX + tolerance;
        }

        boolean containsLaunchY(int y) {
            return y >= launchMinY && y <= launchMaxY;
        }

        boolean containsLaunchY(int y, int tolerance) {
            return y >= launchMinY - tolerance && y <= launchMaxY + tolerance;
        }

        /** Launch point at the in-window x nearest to {@code x} (the x execution would actually fire from). */
        Point pointAtNearestLaunchX(int x) {
            return new Point(Math.clamp(x, launchMinX, launchMaxX), startPoint.y);
        }

        /** Launch point at the in-window climb height nearest to {@code y} (rope-exit analogue). */
        Point pointAtNearestLaunchY(int y) {
            return new Point(startPoint.x, Math.clamp(y, launchMinY, launchMaxY));
        }

        /** Cheapest launch cost across the Y window — an admissible lower bound for the cost-to-goal index. */
        int minLaunchCost() {
            return Math.min(launchCostMinY, launchCostMaxY);
        }

        /** Edge cost when launching at the in-window height nearest {@code y}, linearly interpolated between
         *  the two endpoint costs. Degenerate (flat) windows just return {@code cost}. */
        int launchCostAt(int y) {
            if (launchMaxY <= launchMinY) {
                return cost;
            }
            int cy = Math.clamp(y, launchMinY, launchMaxY);
            long span = launchMaxY - launchMinY;
            return (int) (launchCostMinY + (long) (launchCostMaxY - launchCostMinY) * (cy - launchMinY) / span);
        }
    }

    static final class PortalRouteIndex implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        final int[] targetRegionIds;
        final int sourceBucketsPerRegion;
        final int[][] nextEdgeIndexByTargetAndRegionBucket;

        PortalRouteIndex(int[] targetRegionIds, int sourceBucketsPerRegion, int[][] nextEdgeIndexByTargetAndRegionBucket) {
            this.targetRegionIds = targetRegionIds;
            this.sourceBucketsPerRegion = sourceBucketsPerRegion;
            this.nextEdgeIndexByTargetAndRegionBucket = nextEdgeIndexByTargetAndRegionBucket;
        }

        boolean isEmpty() {
            return targetRegionIds.length == 0;
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
    final PortalRouteIndex portalRouteIndex;
    private transient volatile Edge[] portalRouteEdgesByIndex;
    private transient volatile Map<Integer, Integer> portalRouteRegionRowById;
    private static final int PORTAL_ROUTE_SOURCE_BUCKETS = 8;

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
        this.portalRouteIndex = buildPortalRouteIndex();
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

    private transient volatile Map<Integer, Map<Integer, List<Edge>>> outgoingBySkillMask;

    List<Edge> getOutgoing(int regionId, int skillMask) {
        if ((skillMask & (SKILL_TELEPORT | SKILL_FLASH_JUMP)) == (SKILL_TELEPORT | SKILL_FLASH_JUMP)) {
            return getOutgoing(regionId);
        }
        Map<Integer, Map<Integer, List<Edge>>> byMask = outgoingBySkillMask;
        if (byMask == null) {
            synchronized (this) {
                byMask = outgoingBySkillMask;
                if (byMask == null) {
                    byMask = new ConcurrentHashMap<>();
                    outgoingBySkillMask = byMask;
                }
            }
        }
        return byMask
                .computeIfAbsent(skillMask, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(regionId, r -> filterOutgoingForSkillMask(getOutgoing(r), skillMask));
    }

    private static List<Edge> filterOutgoingForSkillMask(List<Edge> edges, int skillMask) {
        if (edges.isEmpty()) {
            return edges;
        }
        List<Edge> filtered = new ArrayList<>(edges.size());
        for (Edge edge : edges) {
            if (reachEdgeUsable(edge, skillMask)) {
                filtered.add(edge);
            }
        }
        return List.copyOf(filtered);
    }

    // --- Cost-to-goal index (runtime-only; lazy, transient per graph) -----------------------------
    // Reverse-Dijkstra distance (edge-cost lower bound) from EVERY region to a given target region,
    // over the real edge graph -- portals/teleports included. Used only as an admissible A* heuristic
    // floor: it is portal-aware (a region whose cheapest real route to goal runs through a "backward"
    // portal gets a low value, so A* beelines toward the shortcut instead of away from it) where a
    // straight-line-to-target heuristic would over-penalise and never reach. It is position-BLIND
    // (region granularity), so the caller pairs it with the live point->exit travel term to keep a
    // gradient inside wide regions -- this map alone is never used as a per-position distance.
    // Map is static per graph, so distances are stable for the graph's life. Cached per target region;
    // grind targets are shared across bots, so one reverse-Dijkstra amortises across the fleet.
    // ponytail: edge-cost-only reverse-Dijkstra; exact enough as an admissible lower bound.
    private transient volatile Map<Integer, Map<Integer, Map<Integer, Integer>>> costToGoalBySkillMaskAndTarget;

    /** Forward cost (sum of edge costs, the same units {@link Edge#cost} uses) from each region to
     *  {@code targetRegionId}. Regions that cannot reach the target are absent. Admissible lower
     *  bound: ignores intra-region travel and portal cooldown (both {@code >= 0}). */
    Map<Integer, Integer> costToGoal(int targetRegionId) {
        return costToGoal(targetRegionId, SKILL_TELEPORT | SKILL_FLASH_JUMP);
    }

    Map<Integer, Integer> costToGoal(int targetRegionId, int skillMask) {
        Map<Integer, Map<Integer, Map<Integer, Integer>>> cache = costToGoalBySkillMaskAndTarget;
        if (cache == null) {
            synchronized (this) {
                cache = costToGoalBySkillMaskAndTarget;
                if (cache == null) {
                    cache = new ConcurrentHashMap<>();
                    costToGoalBySkillMaskAndTarget = cache;
                }
            }
        }
        return cache
                .computeIfAbsent(skillMask, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(targetRegionId, target -> computeCostToGoal(target, skillMask));
    }

    private Map<Integer, Integer> computeCostToGoal(int targetRegionId, int skillMask) {
        // Reverse adjacency: forward edge r -> e.toRegionId (cost e.cost) becomes e.toRegionId -> r.
        Map<Integer, List<int[]>> rev = new HashMap<>();
        for (List<Edge> edges : outgoingByRegionId.values()) {
            for (Edge e : edges) {
                if (!reachEdgeUsable(e, skillMask)) {
                    continue;
                }
                // Cheapest launch across the (rope) window — admissible lower bound; flat for other edges.
                rev.computeIfAbsent(e.toRegionId, k -> new ArrayList<>()).add(new int[]{e.fromRegionId, e.minLaunchCost()});
            }
        }
        Map<Integer, Integer> dist = new HashMap<>();
        PriorityQueue<int[]> pq = new PriorityQueue<>(Comparator.comparingInt(a -> a[1])); // (region, dist)
        dist.put(targetRegionId, 0);
        pq.add(new int[]{targetRegionId, 0});
        while (!pq.isEmpty()) {
            int[] top = pq.poll();
            int r = top[0], d = top[1];
            if (d > dist.getOrDefault(r, Integer.MAX_VALUE)) {
                continue;
            }
            for (int[] step : rev.getOrDefault(r, List.of())) {
                int pr = step[0], nd = d + step[1];
                if (nd < dist.getOrDefault(pr, Integer.MAX_VALUE)) {
                    dist.put(pr, nd);
                    pq.add(new int[]{pr, nd});
                }
            }
        }
        return dist;
    }

    // --- Directed reachability index (runtime-only; lazy, transient per graph) --------------------
    // Per-source FORWARD reachability over the region graph, so a pathfind can early-exit when the
    // target is not reachable from the start for this bot's capability -- instead of letting A* burn
    // its edge-check budget (a high-fan-out start region caps the search before it ever reaches, or
    // rules out, the goal). Directed (follows getOutgoing), unlike the old undirected union-find: a
    // one-way DROP/JUMP into a region no longer makes it look reachable from the other side. The map
    // is static, so reachability varies only with the bot's movement profile -- speed/jump are already
    // baked into THIS graph instance (GraphCacheKey), and usable skill edges are captured by skillMask.
    // PORTAL edges are treated as always usable (static structural links), so the reachable set is a
    // SUPERSET of what the real per-edge-filtered search can traverse: "not reachable" is a sound NO
    // (safe early-exit); "reachable" just means "run the search". Lazily computed per (skillMask,
    // startRegion) and cached for the graph's life.
    // ponytail: per-source BFS, O(V+E) each, memoized; all-pairs matrix only if distinct start regions
    // ever get numerous enough to matter.
    static final int SKILL_TELEPORT = 1;
    static final int SKILL_FLASH_JUMP = 1 << 1;
    private transient volatile Map<Integer, Map<Integer, Set<Integer>>> reachableByMaskAndStart;

    /** True unless {@code targetRegionId} is provably NOT forward-reachable from {@code startRegionId}
     *  for a bot whose usable skill edges are described by {@code skillMask} (bitwise-or of
     *  {@link #SKILL_TELEPORT}/{@link #SKILL_FLASH_JUMP}; 0 = walk-only). Returns true for an unknown
     *  region (caller must not early-exit when reachability cannot be decided). */
    boolean canReach(int startRegionId, int targetRegionId, int skillMask) {
        if (startRegionId == targetRegionId) {
            return true;
        }
        if (!regionsById.containsKey(startRegionId) || !regionsById.containsKey(targetRegionId)) {
            return true;
        }
        return reachableFrom(startRegionId, skillMask).contains(targetRegionId);
    }

    private Set<Integer> reachableFrom(int startRegionId, int skillMask) {
        Map<Integer, Map<Integer, Set<Integer>>> byMask = reachableByMaskAndStart;
        if (byMask == null) {
            synchronized (this) {
                byMask = reachableByMaskAndStart;
                if (byMask == null) {
                    byMask = new ConcurrentHashMap<>();
                    reachableByMaskAndStart = byMask;
                }
            }
        }
        return byMask
                .computeIfAbsent(skillMask, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(startRegionId, s -> computeReachable(s, skillMask));
    }

    private Set<Integer> computeReachable(int startRegionId, int skillMask) {
        Set<Integer> visited = new HashSet<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        visited.add(startRegionId);
        queue.add(startRegionId);
        while (!queue.isEmpty()) {
            int regionId = queue.poll();
            for (Edge edge : getOutgoing(regionId)) {
                if (!reachEdgeUsable(edge, skillMask)) {
                    continue;
                }
                if (visited.add(edge.toRegionId)) {
                    queue.add(edge.toRegionId);
                }
            }
        }
        return visited;
    }

    private static boolean reachEdgeUsable(Edge edge, int skillMask) {
        return switch (edge.type) {
            case WALK, JUMP, DROP, CLIMB, PORTAL -> true;
            case TELEPORT -> (skillMask & SKILL_TELEPORT) != 0;
            case FLASH_JUMP -> (skillMask & SKILL_FLASH_JUMP) != 0;
        };
    }

    /** Among regions forward-reachable from {@code startRegionId} (for {@code skillMask}), the one whose
     *  nearest standable point is STRICTLY closer to {@code targetPos} than the start region itself; -1
     *  when nothing reachable beats the start (the bot is already as close as it can get). The
     *  best-effort "walk as close as possible" target when the exact target region is unreachable: a
     *  bounded A* to this known-reachable region replaces the old cap-then-give-up. */
    int nearestReachableRegion(int startRegionId, int skillMask, Point targetPos) {
        Region start = regionsById.get(startRegionId);
        long bestDist = start == null ? Long.MAX_VALUE : manhattan(start.pointAt(targetPos.x), targetPos);
        int best = -1;
        for (int regionId : reachableFrom(startRegionId, skillMask)) {
            if (regionId == startRegionId) {
                continue;
            }
            Region region = regionsById.get(regionId);
            if (region == null) {
                continue;
            }
            long dist = manhattan(region.pointAt(targetPos.x), targetPos);
            if (dist < bestDist) {
                bestDist = dist;
                best = regionId;
            }
        }
        return best;
    }

    private static long manhattan(Point a, Point b) {
        return Math.abs((long) a.x - b.x) + Math.abs((long) a.y - b.y);
    }

    private PortalRouteIndex buildPortalRouteIndex() {
        List<Integer> targets = new ArrayList<>(portalRegionIds());
        targets.sort(Integer::compare);
        if (targets.isEmpty() || regions.isEmpty()) {
            return new PortalRouteIndex(new int[0], PORTAL_ROUTE_SOURCE_BUCKETS, new int[0][]);
        }

        Edge[] indexedEdges = buildPortalRouteEdgesByIndex();
        portalRouteEdgesByIndex = indexedEdges;
        Map<Edge, Integer> edgeIndexByEdge = new HashMap<>();
        for (int i = 0; i < indexedEdges.length; i++) {
            edgeIndexByEdge.put(indexedEdges[i], i);
        }

        int[] targetIds = targets.stream().mapToInt(Integer::intValue).toArray();
        int rowCount = regions.size() * PORTAL_ROUTE_SOURCE_BUCKETS;
        int[][] nextEdges = new int[targetIds.length][rowCount];
        for (int i = 0; i < nextEdges.length; i++) {
            java.util.Arrays.fill(nextEdges[i], -1);
        }

        for (int targetIndex = 0; targetIndex < targetIds.length; targetIndex++) {
            int targetRegionId = targetIds[targetIndex];
            Map<Integer, Integer> costToPortal = computeCostToGoal(targetRegionId, 0);
            for (int regionRow = 0; regionRow < regions.size(); regionRow++) {
                Region region = regions.get(regionRow);
                if (region.id == targetRegionId) {
                    continue;
                }
                for (int bucket = 0; bucket < PORTAL_ROUTE_SOURCE_BUCKETS; bucket++) {
                    Point from = portalRouteBucketPoint(region, bucket);
                    Edge next = bestPortalRouteNextHop(region, from, costToPortal, edgeIndexByEdge);
                    if (next != null) {
                        nextEdges[targetIndex][regionRow * PORTAL_ROUTE_SOURCE_BUCKETS + bucket] =
                                edgeIndexByEdge.get(next);
                    }
                }
            }
        }
        return new PortalRouteIndex(targetIds, PORTAL_ROUTE_SOURCE_BUCKETS, nextEdges);
    }

    private Edge[] buildPortalRouteEdgesByIndex() {
        List<Edge> edges = new ArrayList<>();
        List<Integer> sourceRegionIds = new ArrayList<>(outgoingByRegionId.keySet());
        sourceRegionIds.sort(Integer::compare);
        for (int regionId : sourceRegionIds) {
            edges.addAll(outgoingByRegionId.getOrDefault(regionId, List.of()));
        }
        return edges.toArray(new Edge[0]);
    }

    private Edge bestPortalRouteNextHop(Region region,
                                        Point from,
                                        Map<Integer, Integer> costToPortal,
                                        Map<Edge, Integer> edgeIndexByEdge) {
        if (!costToPortal.containsKey(region.id)) {
            return null;
        }
        Edge best = null;
        long bestCost = Long.MAX_VALUE;
        int bestIndex = Integer.MAX_VALUE;
        for (Edge edge : getOutgoing(region.id, 0)) {
            Integer downstream = costToPortal.get(edge.toRegionId);
            if (downstream == null) {
                continue;
            }
            Point approach = edge.type == EdgeType.CLIMB && edge.launchMaxY > edge.launchMinY
                    ? edge.pointAtNearestLaunchY(from.y)
                    : edge.startPoint;
            long cost = (long) portalRouteTravelCost(region, from, approach) + edge.minLaunchCost() + downstream;
            int edgeIndex = edgeIndexByEdge.getOrDefault(edge, Integer.MAX_VALUE);
            if (cost < bestCost || (cost == bestCost && edgeIndex < bestIndex)) {
                best = edge;
                bestCost = cost;
                bestIndex = edgeIndex;
            }
        }
        return best;
    }

    private int portalRouteTravelCost(Region region, Point from, Point to) {
        if (region.isRopeRegion) {
            int travel = Math.abs(to.y - from.y);
            return Math.max(0, (int) Math.round((travel * 1000.0) / Math.max(1, BotMovementManager.cfg.CLIMB_SPEED_PXS)));
        }
        long travel = Math.abs((long) to.x - from.x) + Math.abs((long) to.y - from.y);
        return Math.max(0, (int) Math.min(Integer.MAX_VALUE,
                Math.round((travel * 1000.0) / Math.max(1.0, movementProfile.walkVelocityPxs()))));
    }

    private Point portalRouteBucketPoint(Region region, int bucket) {
        int b = Math.clamp(bucket, 0, PORTAL_ROUTE_SOURCE_BUCKETS - 1);
        if (region.isRopeRegion) {
            int span = Math.max(0, region.maxY - region.minY);
            int y = region.minY + (int) Math.round((span * (b + 0.5)) / PORTAL_ROUTE_SOURCE_BUCKETS);
            return new Point(region.minX, y);
        }
        int span = Math.max(0, region.maxX - region.minX);
        int x = region.minX + (int) Math.round((span * (b + 0.5)) / PORTAL_ROUTE_SOURCE_BUCKETS);
        return region.pointAt(x);
    }

    private int portalRouteBucket(Region region, Point point) {
        if (region == null || point == null) {
            return 0;
        }
        int span = Math.max(1, region.isRopeRegion ? region.maxY - region.minY : region.maxX - region.minX);
        int offset = region.isRopeRegion ? point.y - region.minY : point.x - region.minX;
        int bucket = (int) ((long) Math.clamp(offset, 0, span) * PORTAL_ROUTE_SOURCE_BUCKETS / (span + 1L));
        return Math.clamp(bucket, 0, PORTAL_ROUTE_SOURCE_BUCKETS - 1);
    }

    private Edge[] portalRouteEdgesByIndex() {
        Edge[] edges = portalRouteEdgesByIndex;
        if (edges == null) {
            synchronized (this) {
                edges = portalRouteEdgesByIndex;
                if (edges == null) {
                    edges = buildPortalRouteEdgesByIndex();
                    portalRouteEdgesByIndex = edges;
                }
            }
        }
        return edges;
    }

    private Map<Integer, Integer> portalRouteRegionRowById() {
        Map<Integer, Integer> rows = portalRouteRegionRowById;
        if (rows == null) {
            synchronized (this) {
                rows = portalRouteRegionRowById;
                if (rows == null) {
                    rows = new HashMap<>();
                    for (int i = 0; i < regions.size(); i++) {
                        rows.put(regions.get(i).id, i);
                    }
                    portalRouteRegionRowById = rows;
                }
            }
        }
        return rows;
    }

    boolean hasPortalRouteTarget(int targetRegionId) {
        return portalRouteTargetIndex(targetRegionId) >= 0;
    }

    List<Integer> portalRouteTargetRegionIds() {
        if (portalRouteIndex.isEmpty()) {
            return List.of();
        }
        List<Integer> ids = new ArrayList<>(portalRouteIndex.targetRegionIds.length);
        for (int id : portalRouteIndex.targetRegionIds) {
            ids.add(id);
        }
        return ids;
    }

    Edge portalNextHop(int startRegionId, int targetRegionId, Point startPoint) {
        int targetIndex = portalRouteTargetIndex(targetRegionId);
        if (targetIndex < 0) {
            return null;
        }
        Integer regionRow = portalRouteRegionRowById().get(startRegionId);
        Region region = regionsById.get(startRegionId);
        if (regionRow == null || region == null) {
            return null;
        }
        int bucket = portalRouteBucket(region, startPoint);
        int row = regionRow * portalRouteIndex.sourceBucketsPerRegion + bucket;
        int edgeIndex = portalRouteIndex.nextEdgeIndexByTargetAndRegionBucket[targetIndex][row];
        if (edgeIndex < 0) {
            return null;
        }
        Edge[] edges = portalRouteEdgesByIndex();
        return edgeIndex < edges.length ? edges[edgeIndex] : null;
    }

    List<Edge> portalRoute(int startRegionId, int targetRegionId, Point startPoint) {
        if (startRegionId == targetRegionId || !hasPortalRouteTarget(targetRegionId)) {
            return List.of();
        }
        List<Edge> route = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        int regionId = startRegionId;
        Point point = startPoint;
        while (regionId != targetRegionId && seen.add(regionId)) {
            Edge next = portalNextHop(regionId, targetRegionId, point);
            if (next == null) {
                return List.of();
            }
            route.add(next);
            regionId = next.toRegionId;
            point = next.endPoint;
        }
        return regionId == targetRegionId ? route : List.of();
    }

    private int portalRouteTargetIndex(int targetRegionId) {
        for (int i = 0; i < portalRouteIndex.targetRegionIds.length; i++) {
            if (portalRouteIndex.targetRegionIds[i] == targetRegionId) {
                return i;
            }
        }
        return -1;
    }

    // --- Lazy region-route cache (runtime-only; populated by BotNavigationManager.findNextEdge) ---
    // Holds the next-hop edge per (startRegion, targetRegion, startPointBucket, targetPointBucket,
    // routeBucket). Transient by design:
    // rebuilt per graph instance, so it dies with the graph version (no GRAPH_VERSION bump, no disk).
    // Buckets give crowd de-stacking without per-bot searches (see BotNavigationManager.ROUTE_BUCKETS).
    // ponytail: unbounded; bounded by regionPairs*buckets in practice, LRU only if a map ever blows up.
    static final Edge NO_EDGE = new Edge(-1, -1, EdgeType.WALK, new Point(), new Point(), 0, -1, 0, 0, 0, 0);
    private record RouteCacheKey(int startRegionId, int targetRegionId, int startPointBucket, int targetPointBucket) {
    }

    private transient volatile Map<RouteCacheKey, Edge[]> routeCache;
    private transient volatile List<Integer> portalRegionIds;

    private Map<RouteCacheKey, Edge[]> routeCache() {
        Map<RouteCacheKey, Edge[]> c = routeCache;
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

    // Hit/miss counters for A/B-ing the route cache (did it actually save A* calls?). Static + cumulative
    // since server start, so they survive graph rebuilds — exactly what a session-long measurement wants.
    // Exposed on /api/botdebug as "routeCache". ponytail: no reset endpoint, restart the server to zero them.
    static final java.util.concurrent.atomic.LongAdder cacheHits = new java.util.concurrent.atomic.LongAdder();
    static final java.util.concurrent.atomic.LongAdder cacheMisses = new java.util.concurrent.atomic.LongAdder();

    /** Cached next hop ({@link #NO_EDGE} = direct walk), or {@code null} if not computed for this key. */
    Edge cachedNextHop(int startRegionId, int targetRegionId, int startPointBucket, int targetPointBucket, int bucket) {
        Edge[] slots = routeCache().get(new RouteCacheKey(startRegionId, targetRegionId, startPointBucket, targetPointBucket));
        Edge hop = slots == null ? null : slots[bucket];
        (hop == null ? cacheMisses : cacheHits).increment();
        return hop;
    }

    /** {@code {"hits":N,"misses":M,"rate":0.xx}} — cumulative cache effectiveness for /api/botdebug. */
    static String routeCacheStatsJson() {
        long h = cacheHits.sum(), m = cacheMisses.sum(), t = h + m;
        return "{\"hits\":" + h + ",\"misses\":" + m + ",\"rate\":" + (t == 0 ? 0 : (double) h / t) + "}";
    }

    void putNextHop(int startRegionId, int targetRegionId, int startPointBucket, int targetPointBucket,
                    int bucket, int bucketCount, Edge edge) {
        routeCache()
                .computeIfAbsent(new RouteCacheKey(startRegionId, targetRegionId, startPointBucket, targetPointBucket),
                        k -> new Edge[bucketCount])[bucket] = edge;
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
