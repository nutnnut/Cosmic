package server.bots;

import server.life.SpawnPoint;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spot clustering substrate for the grind doctrine (BotGrindDoctrine): greedy-merges a map's
 * static WZ spawn points into scored spatial "Spots" (roughly one screen each), classifies the
 * map's spawn regime (COMPACT / SPREAD / SPARSE), and detects vertically-stacked spot columns.
 * Ported from the SoloMapling v0.3 grind audit (docs/bot/kb/kb_bot_solomapling_grind_v03.md) —
 * everything here is DERIVED from the map's real spawn geometry at first use; no per-map tables.
 *
 * Ledge identity comes from the nav graph's region ids (the same authority the combat
 * same-foothold gate and patrol mode use), so profiles are cached per (map, movement-profile
 * bucket) — same key space as the graph cache itself. Profiles are cheap (union-find over a few
 * dozen points) and built once per key.
 */
final class BotGrindSpots {

    // Clustering (SoloMapling SpotFinder constants; radii ~ one client screen)
    static final int SPOT_RADIUS_MIN = 250;
    static final int SPOT_RADIUS_MAX = 500;
    static final int SPOT_CLUSTER_MERGE_PX = 350;
    static final double SPOT_CLUSTER_VERTICAL_SCALE = 2.5; // stacked platforms split, long floors merge
    static final int MIN_LEDGE_SPAWNS_FOR_SPOT = 2;        // non-dominant ledge needs >=2 to stand alone
    static final int MIN_BAND_PX = 200;                    // personal fighting band -> shareCap
    static final int SHARE_CAP_MAX = 4;

    // Regime classification
    static final double DENSITY_HI = 0.012;
    static final double DENSITY_LO = 0.005;
    static final int GAP_LO = 600;
    static final int SPARSE_SPAWN_COUNT = 6;
    static final int MIN_CAMPABLE_SAMELEDGE_SPAWN = 4;     // best ledge feeds fewer -> roam the map

    // Stack detection (vertically layered spot columns)
    static final int STACK_DY_MIN = 40;
    static final int STACK_BLINK_DY = 180;
    static final int STACK_HOP_DY = 110;                   // consecutive gaps <= this are jump/hop-able
    static final int STACK_X_OVERLAP_MIN = 80;

    // Spot-selection score weights
    static final double SPAWN_DENSITY_W = 10.0;
    static final double TIGHTNESS_W = 8.0;
    static final double LEDGE_EXTENT_W = 1.0;
    static final int LEDGE_EXTENT_CAP_PX = 1000;
    static final double LIVE_MOB_W = 6.0;
    static final double DISTANCE_W = 0.015;
    static final double CROWDING_W = 30.0;
    static final double OVER_CAP_PENALTY = 100_000.0;
    static final double SELECT_JITTER = 12.0;

    /** Y band for snapping a spawn point onto a graph region's walkable surface. */
    private static final int SPAWN_REGION_Y_BAND = 60;
    /** Claims not renewed within this window no longer count (self-healing, no release needed). */
    static final long CLAIM_TTL_MS = 30_000L;

    private BotGrindSpots() {
    }

    /** Input point for the pure clustering core (x, y snapped to ground; regionId -1 = unknown ledge). */
    record SpawnPt(int x, int y, int regionId) {
    }

    /**
     * One harvestable cluster of spawn points. {@code sameLedgeSpawnCount} counts only the spawns on
     * the anchor's own ledge — the feed the in-spot combat gate can actually reach without leaving it.
     */
    record Spot(Point anchor, int regionId, int radius, int spawnCount, int sameLedgeSpawnCount,
                int ledgeSpanPx, int shareCap) {
        long claimKey() {
            return ((long) anchor.x << 32) | (anchor.y & 0xffffffffL);
        }
    }

    /** Vertically layered column of spots (different ledges, overlapping X). */
    record SpotStack(List<Integer> spotIndices, int x0, int x1, int topY, int bottomY,
                     int totalFeed, boolean hopTraversable) {
    }

    enum Regime {COMPACT, SPREAD, SPARSE}

    record Profile(List<Spot> spots, List<SpotStack> stacks, Regime regime, boolean roam) {
        static final Profile EMPTY = new Profile(List.of(), List.of(), Regime.SPARSE, true);
    }

    // ------------------------------------------------------------------ profile build + cache

    private static final ConcurrentHashMap<String, Profile> CACHE = new ConcurrentHashMap<>();

    /** Cached spot profile for the map as seen through this movement profile's graph. Returns
     *  {@link Profile#EMPTY} for spawnless maps; null while the graph is still warming (retry later —
     *  clustering without ledge identity would bake wrong spots into the cache). */
    static Profile profileFor(MapleMap map, BotNavigationGraph graph, BotMovementProfile profile) {
        if (map == null) {
            return Profile.EMPTY;
        }
        if (graph == null) {
            return null;
        }
        String key = map.getId() + "|" + profile;
        Profile cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        List<SpawnPt> pts = snapSpawns(map, graph);
        Profile built = buildProfile(pts, walkableSpanX(graph));
        CACHE.putIfAbsent(key, built);
        return built;
    }

    private static List<SpawnPt> snapSpawns(MapleMap map, BotNavigationGraph graph) {
        List<SpawnPt> pts = new ArrayList<>();
        for (SpawnPoint sp : map.getMonsterSpawn()) {
            Point p = sp.getPosition();
            if (p == null) {
                continue;
            }
            BotNavigationGraph.Region r = groundRegionAt(graph, p);
            pts.add(new SpawnPt(p.x, r != null ? r.pointAt(p.x).y : p.y, r != null ? r.id : -1));
        }
        return pts;
    }

    /** Nearest ground region whose surface passes under (x, ~y) — same shape as the idle attribution. */
    static BotNavigationGraph.Region groundRegionAt(BotNavigationGraph graph, Point p) {
        BotNavigationGraph.Region best = null;
        int bestDy = Integer.MAX_VALUE;
        for (BotNavigationGraph.Region r : graph.regions) {
            if (r.isRopeRegion || r.width() <= 0 || p.x < r.minX || p.x > r.maxX) {
                continue;
            }
            int dy = Math.abs(r.pointAt(p.x).y - p.y);
            if (dy < bestDy) {
                bestDy = dy;
                best = r;
            }
        }
        return bestDy <= SPAWN_REGION_Y_BAND ? best : null;
    }

    private static int walkableSpanX(BotNavigationGraph graph) {
        int span = 0;
        for (BotNavigationGraph.Region r : graph.regions) {
            if (!r.isRopeRegion) {
                span += r.width();
            }
        }
        return span;
    }

    /** Pure clustering core (unit-testable without a map/graph). */
    static Profile buildProfile(List<SpawnPt> pts, int walkableSpanX) {
        if (pts.isEmpty()) {
            return Profile.EMPTY;
        }
        List<List<SpawnPt>> clusters = greedySingleLinkage(pts);
        List<Spot> spots = new ArrayList<>();
        for (List<SpawnPt> cluster : clusters) {
            for (List<SpawnPt> ledgeGroup : partitionByLedge(cluster)) {
                spots.addAll(makeSpots(ledgeGroup, cluster));
            }
        }
        Regime regime = classify(pts.size(), walkableSpanX, clusters);
        int bestSameLedge = 0;
        for (Spot s : spots) {
            bestSameLedge = Math.max(bestSameLedge, s.sameLedgeSpawnCount());
        }
        boolean roam = bestSameLedge < MIN_CAMPABLE_SAMELEDGE_SPAWN;
        return new Profile(List.copyOf(spots), detectStacks(spots), regime, roam);
    }

    /** Union-find single linkage with anisotropic distance: dy scaled so stacked platforms split
     *  while one long floor stays a single cluster. */
    private static List<List<SpawnPt>> greedySingleLinkage(List<SpawnPt> pts) {
        int n = pts.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        long limitSq = (long) SPOT_CLUSTER_MERGE_PX * SPOT_CLUSTER_MERGE_PX;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double dx = pts.get(i).x() - pts.get(j).x();
                double dy = (pts.get(i).y() - pts.get(j).y()) * SPOT_CLUSTER_VERTICAL_SCALE;
                if (dx * dx + dy * dy <= limitSq) {
                    union(parent, i, j);
                }
            }
        }
        Map<Integer, List<SpawnPt>> byRoot = new HashMap<>();
        for (int i = 0; i < n; i++) {
            byRoot.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(pts.get(i));
        }
        return new ArrayList<>(byRoot.values());
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        parent[find(parent, a)] = find(parent, b);
    }

    /** Split a cluster along ledge (region-id) boundaries. Dominant ledge = largest bucket (ties ->
     *  lower id); a non-dominant real ledge with >= MIN_LEDGE_SPAWNS_FOR_SPOT stands alone; strays
     *  and unknown-ledge (-1) spawns fold into the dominant bucket. No graph regions at all
     *  (every id -1) -> one group per cluster. */
    private static List<List<SpawnPt>> partitionByLedge(List<SpawnPt> cluster) {
        Map<Integer, List<SpawnPt>> byRegion = new HashMap<>();
        for (SpawnPt p : cluster) {
            byRegion.computeIfAbsent(p.regionId(), k -> new ArrayList<>()).add(p);
        }
        if (byRegion.size() == 1) {
            return List.of(cluster);
        }
        int dominantId = Integer.MIN_VALUE;
        int dominantSize = -1;
        for (Map.Entry<Integer, List<SpawnPt>> e : byRegion.entrySet()) {
            if (e.getKey() < 0) {
                continue;
            }
            int size = e.getValue().size();
            if (size > dominantSize || (size == dominantSize && e.getKey() < dominantId)) {
                dominantSize = size;
                dominantId = e.getKey();
            }
        }
        if (dominantSize < 0) { // all -1: no ledge identity, keep whole cluster
            return List.of(cluster);
        }
        List<SpawnPt> dominant = new ArrayList<>(byRegion.get(dominantId));
        List<List<SpawnPt>> groups = new ArrayList<>();
        for (Map.Entry<Integer, List<SpawnPt>> e : byRegion.entrySet()) {
            if (e.getKey() == dominantId) {
                continue;
            }
            if (e.getKey() >= 0 && e.getValue().size() >= MIN_LEDGE_SPAWNS_FOR_SPOT) {
                groups.add(e.getValue());
            } else {
                dominant.addAll(e.getValue()); // strays / unknown fold into dominant
            }
        }
        groups.add(dominant);
        return groups;
    }

    /** One Spot per ledge group when compact; wide groups tile into 2*SPOT_RADIUS_MAX slices by x. */
    private static List<Spot> makeSpots(List<SpawnPt> ledgeGroup, List<SpawnPt> wholeCluster) {
        if (ledgeGroup.isEmpty()) {
            return List.of();
        }
        if (robustHalfSpreadX(ledgeGroup) <= SPOT_RADIUS_MAX) {
            return List.of(makeSpot(ledgeGroup, wholeCluster));
        }
        List<SpawnPt> sorted = new ArrayList<>(ledgeGroup);
        sorted.sort(Comparator.comparingInt(SpawnPt::x));
        List<Spot> spots = new ArrayList<>();
        int sliceStart = sorted.get(0).x();
        List<SpawnPt> slice = new ArrayList<>();
        for (SpawnPt p : sorted) {
            if (p.x() - sliceStart > 2 * SPOT_RADIUS_MAX && !slice.isEmpty()) {
                spots.add(makeSpot(slice, slice));
                slice = new ArrayList<>();
                sliceStart = p.x();
            }
            slice.add(p);
        }
        if (!slice.isEmpty()) {
            spots.add(makeSpot(slice, slice));
        }
        return spots;
    }

    private static Spot makeSpot(List<SpawnPt> ledgeGroup, List<SpawnPt> wholeCluster) {
        long sumX = 0;
        for (SpawnPt p : ledgeGroup) {
            sumX += p.x();
        }
        int centroidX = (int) (sumX / ledgeGroup.size());
        SpawnPt anchor = ledgeGroup.get(0);
        for (SpawnPt p : ledgeGroup) {
            if (Math.abs(p.x() - centroidX) < Math.abs(anchor.x() - centroidX)) {
                anchor = p;
            }
        }
        int radius = clamp(robustHalfSpreadX(wholeCluster), SPOT_RADIUS_MIN, SPOT_RADIUS_MAX);
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        for (SpawnPt p : ledgeGroup) {
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
        }
        int shareCap = clamp((2 * radius) / MIN_BAND_PX, 1, SHARE_CAP_MAX);
        return new Spot(new Point(anchor.x(), anchor.y()), anchor.regionId(), radius,
                wholeCluster.size(), ledgeGroup.size(), Math.max(0, maxX - minX), shareCap);
    }

    /** p90 of |x - meanX| — ignores a lone outlier so one stray spawn doesn't balloon the radius. */
    static int robustHalfSpreadX(List<SpawnPt> pts) {
        if (pts.size() <= 1) {
            return 0;
        }
        long sum = 0;
        for (SpawnPt p : pts) {
            sum += p.x();
        }
        double mean = (double) sum / pts.size();
        List<Integer> devs = new ArrayList<>(pts.size());
        for (SpawnPt p : pts) {
            devs.add((int) Math.abs(p.x() - mean));
        }
        Collections.sort(devs);
        return devs.get((int) Math.round(0.9 * (devs.size() - 1)));
    }

    private static Regime classify(int spawnCount, int walkableSpanX, List<List<SpawnPt>> clusters) {
        double density = spawnCount / (double) Math.max(1, walkableSpanX);
        if (spawnCount <= SPARSE_SPAWN_COUNT || density <= DENSITY_LO) {
            return Regime.SPARSE;
        }
        if (clusters.size() <= 1 || (density >= DENSITY_HI && meanClusterGapX(clusters) <= GAP_LO)) {
            return Regime.COMPACT;
        }
        return Regime.SPREAD;
    }

    private static int meanClusterGapX(List<List<SpawnPt>> clusters) {
        if (clusters.size() < 2) {
            return 0;
        }
        List<Integer> centers = new ArrayList<>(clusters.size());
        for (List<SpawnPt> c : clusters) {
            long sum = 0;
            for (SpawnPt p : c) {
                sum += p.x();
            }
            centers.add((int) (sum / c.size()));
        }
        Collections.sort(centers);
        long gapSum = 0;
        for (int i = 1; i < centers.size(); i++) {
            gapSum += centers.get(i) - centers.get(i - 1);
        }
        return (int) (gapSum / (centers.size() - 1));
    }

    /** Union spots into vertical columns: different real ledges, dy in [STACK_DY_MIN, STACK_BLINK_DY],
     *  X extents overlapping >= STACK_X_OVERLAP_MIN. Keeps groups of >= 2, members sorted top-down. */
    private static List<SpotStack> detectStacks(List<Spot> spots) {
        int n = spots.size();
        if (n < 2) {
            return List.of();
        }
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (stackAdjacent(spots.get(i), spots.get(j))) {
                    union(parent, i, j);
                }
            }
        }
        Map<Integer, List<Integer>> byRoot = new HashMap<>();
        for (int i = 0; i < n; i++) {
            byRoot.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        }
        List<SpotStack> stacks = new ArrayList<>();
        for (List<Integer> members : byRoot.values()) {
            if (members.size() < 2) {
                continue;
            }
            members.sort(Comparator.comparingInt(i -> spots.get(i).anchor().y)); // top-down
            int x0 = Integer.MAX_VALUE;
            int x1 = Integer.MIN_VALUE;
            int feed = 0;
            boolean hoppable = true;
            for (int k = 0; k < members.size(); k++) {
                Spot s = spots.get(members.get(k));
                x0 = Math.min(x0, s.anchor().x - s.radius());
                x1 = Math.max(x1, s.anchor().x + s.radius());
                feed += s.sameLedgeSpawnCount();
                if (k > 0) {
                    int dy = s.anchor().y - spots.get(members.get(k - 1)).anchor().y;
                    if (dy > STACK_HOP_DY) {
                        hoppable = false;
                    }
                }
            }
            stacks.add(new SpotStack(List.copyOf(members), x0, x1,
                    spots.get(members.get(0)).anchor().y,
                    spots.get(members.get(members.size() - 1)).anchor().y,
                    feed, hoppable));
        }
        return stacks;
    }

    private static boolean stackAdjacent(Spot a, Spot b) {
        if (a.regionId() < 0 || b.regionId() < 0 || a.regionId() == b.regionId()) {
            return false;
        }
        int dy = Math.abs(a.anchor().y - b.anchor().y);
        if (dy < STACK_DY_MIN || dy > STACK_BLINK_DY) {
            return false;
        }
        int overlap = Math.min(a.anchor().x + a.radius(), b.anchor().x + b.radius())
                - Math.max(a.anchor().x - a.radius(), b.anchor().x - b.radius());
        return overlap >= STACK_X_OVERLAP_MIN;
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ------------------------------------------------------------------ claims (cohort spreading)

    /**
     * Live spot occupancy, keyed by (mapId, anchor claim key). Claims are RENEWED every doctrine tick
     * and expire after {@link #CLAIM_TTL_MS} — no release bookkeeping, so a bot that dies, warps, or
     * logs out can never leak a claim.
     */
    static final class Claims {
        private static final ConcurrentHashMap<Integer, ConcurrentHashMap<Long, ConcurrentHashMap<Integer, Long>>>
                BY_MAP = new ConcurrentHashMap<>();

        static void renew(int mapId, long claimKey, int charId, long now) {
            BY_MAP.computeIfAbsent(mapId, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(claimKey, k -> new ConcurrentHashMap<>())
                    .put(charId, now);
        }

        static void release(int mapId, long claimKey, int charId) {
            ConcurrentHashMap<Long, ConcurrentHashMap<Integer, Long>> spots = BY_MAP.get(mapId);
            if (spots == null) {
                return;
            }
            ConcurrentHashMap<Integer, Long> holders = spots.get(claimKey);
            if (holders != null) {
                holders.remove(charId);
            }
        }

        /** Live holder count, excluding the asking bot itself. */
        static int holders(int mapId, long claimKey, int excludeCharId, long now) {
            ConcurrentHashMap<Long, ConcurrentHashMap<Integer, Long>> spots = BY_MAP.get(mapId);
            if (spots == null) {
                return 0;
            }
            ConcurrentHashMap<Integer, Long> holders = spots.get(claimKey);
            if (holders == null) {
                return 0;
            }
            int count = 0;
            for (Map.Entry<Integer, Long> e : holders.entrySet()) {
                if (now - e.getValue() > CLAIM_TTL_MS) {
                    holders.remove(e.getKey(), e.getValue()); // lazy expiry
                } else if (e.getKey() != excludeCharId) {
                    count++;
                }
            }
            return count;
        }
    }
}
