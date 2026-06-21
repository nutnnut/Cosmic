package server.bots;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Where do mobs actually spawn? A world-wide index built once from {@code Map.wz} (the DB
 * {@code plife} table is empty — spawn points only live in the WZ): for every field, the mob ids
 * spawning there and how many spawn points each has. Used by the grind-map advisor to turn
 * "mob X is worth killing" into "map Y is worth going to" (spawn count ≈ kill density).
 *
 * <p>Scanning ~5.8k map XMLs takes seconds, so the result is cached as a TSV under
 * {@code cache/bot-spawn/v<N>/} (same convention as the nav-graph cache) and reloaded instantly
 * on later boots. Bump {@link #INDEX_VERSION} when the row format or scan semantics change.
 *
 * <p>Kept free of {@code LifeFactory}/DB on purpose: rows are plain ids and counts, so the index
 * can be built and tested with nothing but the WZ tree.
 */
final class BotSpawnIndex {

    private static final Logger log = LoggerFactory.getLogger(BotSpawnIndex.class);
    // v3: also records the NPC ids placed on each map (life type "n") and a reverse npc->maps table,
    // so the bot can resolve where a quest NPC stands and walk to it (BotQuestManager.resolveNpcMap).
    private static final int INDEX_VERSION = 4;
    private static final Path CACHE_FILE =
            Path.of("cache", "bot-spawn", "v" + INDEX_VERSION, "spawn-index.tsv");

    /** One field's spawns: total spawn points per mob id ({@code hide}-flagged life excluded),
     *  plus the playable area in px&sup2; (VR bounds, miniMap fallback; 0 = unknown), plus the NPC
     *  ids placed on the field (life type {@code n}). */
    record MapSpawns(int mapId, boolean town, int areaPx, Map<Integer, Integer> mobCounts,
                     List<Integer> npcs) {
        int totalSpawnPoints() {
            int total = 0;
            for (int c : mobCounts.values()) {
                total += c;
            }
            return total;
        }
    }

    /** A mob's presence on one map. */
    record SpawnSite(int mapId, int spawnPoints) {}

    record Index(Map<Integer, MapSpawns> byMap, Map<Integer, List<SpawnSite>> byMob,
                 Map<Integer, List<Integer>> mapsByNpc) {}

    private static volatile Index index;

    private BotSpawnIndex() {}

    /** The world spawn index, built (or loaded from cache) on first use. Never null; empty on failure. */
    static Index get() {
        Index cached = index;
        if (cached != null) {
            return cached;
        }
        synchronized (BotSpawnIndex.class) {
            if (index == null) {
                index = loadOrBuild();
            }
            return index;
        }
    }

    /** Maps where the mob spawns, best (most spawn points) first; empty list when it spawns nowhere. */
    static List<SpawnSite> spawnSites(int mobId) {
        return get().byMob().getOrDefault(mobId, List.of());
    }

    /** Maps where this NPC stands (quest-NPC walk targets), from Map.wz life nodes; empty when the
     *  NPC isn't placed on any indexed field. */
    static List<Integer> mapsWithNpc(int npcId) {
        return get().mapsByNpc().getOrDefault(npcId, List.of());
    }

    private static Index loadOrBuild() {
        Index loaded = loadCache();
        if (loaded != null) {
            log.info("Bot spawn index: loaded {} maps from cache", loaded.byMap().size());
            return loaded;
        }
        long startedAt = System.currentTimeMillis();
        Map<Integer, MapSpawns> byMap = scanWz();
        Index built = withMobIndex(byMap);
        log.info("Bot spawn index: scanned {} maps ({} with mobs) in {} ms",
                byMap.size(),
                byMap.values().stream().filter(m -> !m.mobCounts().isEmpty()).count(),
                System.currentTimeMillis() - startedAt);
        writeCache(built);
        return built;
    }

    private static Map<Integer, MapSpawns> scanWz() {
        // Per-file parsing fans out to a worker pool. XMLWZFile.getData is synchronized per
        // instance, so one shared provider would serialize the workers — each worker thread
        // builds its own (the per-provider directory walk is cheap next to parsing ~5.8k XMLs).
        Map<Integer, MapSpawns> byMap = new ConcurrentHashMap<>();
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
                                MapSpawns spawns = readMap(mapSources.get(), fileArea, mapId);
                                if (spawns != null) {
                                    byMap.put(mapId, spawns);
                                }
                            } catch (RuntimeException e) {
                                failures.incrementAndGet();
                            }
                        });
                    } catch (NumberFormatException ignored) {
                        // non-map file (e.g. AreaCode.img.xml) — skip
                    }
                }
            } catch (IOException e) {
                log.warn("Bot spawn index: can't list {}", areaDir, e);
            }
        }
        pool.shutdown();
        try {
            pool.awaitTermination(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (failures.get() > 0) {
            log.warn("Bot spawn index: {} map files failed to parse (skipped)", failures.get());
        }
        return byMap;
    }

    /** Read one map's info/life, following the same {@code info/link} indirection as MapFactory. */
    private static MapSpawns readMap(DataProvider mapSource, int area, int mapId) {
        Data mapData = mapSource.getData(mapImgPath(area, mapId));
        if (mapData == null) {
            return null;
        }
        Data info = mapData.getChildByPath("info");
        boolean town = info != null && DataTool.getInt("town", info, 0) == 1;
        String link = info != null ? DataTool.getString("link", info, "") : "";
        Data original = mapData;
        if (!link.isEmpty()) {
            try {
                int linkId = Integer.parseInt(link);
                mapData = mapSource.getData(mapImgPath(linkId / 100000000, linkId));
                if (mapData == null) {
                    return new MapSpawns(mapId, town, playableArea(original), Map.of(), List.of());
                }
            } catch (NumberFormatException ignored) {
                // malformed link — read the map as-is
            }
        }
        int areaPx = playableArea(original);
        if (areaPx <= 0) {
            areaPx = playableArea(mapData);
        }
        Map<Integer, Integer> mobCounts = new HashMap<>();
        java.util.Set<Integer> npcs = new java.util.LinkedHashSet<>();
        Data life = mapData.getChildByPath("life");
        if (life != null) {
            for (Data entry : life) {
                String type = DataTool.getString("type", entry, "");
                String id = DataTool.getString("id", entry, null);
                if (id == null) {
                    continue;
                }
                if ("m".equals(type)) {
                    if (DataTool.getInt("hide", entry, 0) == 1) {
                        continue;
                    }
                    // mobTime == -1 spawns the mob ONCE with no respawn (MapFactory.loadLifeRaw) - once
                    // killed the spot is empty forever. Counting these as grindable made the advisor pick
                    // maps that empty out after a while (a bot "grinding" a mob-less map overnight), so
                    // only count genuinely respawning spawn points. Default 0 = immediate respawn.
                    if (DataTool.getInt("mobTime", entry, 0) == -1) {
                        continue;
                    }
                    try {
                        mobCounts.merge(Integer.parseInt(id), 1, Integer::sum);
                    } catch (NumberFormatException ignored) {
                    }
                } else if ("n".equals(type)) {
                    try {
                        npcs.add(Integer.parseInt(id));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return new MapSpawns(mapId, town, areaPx, Map.copyOf(mobCounts), List.copyOf(npcs));
    }

    /** Playable field size in px&sup2;: VR bounds when baked, miniMap canvas as the fallback —
     *  the same precedence MapFactory uses for map boundings. 0 when neither exists. */
    private static int playableArea(Data mapData) {
        Data info = mapData.getChildByPath("info");
        if (info != null) {
            int top = DataTool.getInt("VRTop", info, 0);
            int bottom = DataTool.getInt("VRBottom", info, 0);
            int left = DataTool.getInt("VRLeft", info, 0);
            int right = DataTool.getInt("VRRight", info, 0);
            if (bottom != top && right != left) {
                return Math.max(0, right - left) * Math.max(0, bottom - top);
            }
        }
        Data miniMap = mapData.getChildByPath("miniMap");
        if (miniMap != null) {
            return Math.max(0, DataTool.getInt("width", miniMap, 0))
                    * Math.max(0, DataTool.getInt("height", miniMap, 0));
        }
        return 0;
    }

    private static String mapImgPath(int area, int mapId) {
        return "Map/Map" + area + "/" + String.format("%09d", mapId) + ".img";
    }

    /** Build the reverse indexes (mob->maps, npc->maps) from the per-map table. Package-private and
     *  pure (no WZ/IO) so the reverse-mapping logic is unit-testable with synthetic {@link MapSpawns}. */
    static Index withMobIndex(Map<Integer, MapSpawns> byMap) {
        Map<Integer, List<SpawnSite>> byMob = new HashMap<>();
        Map<Integer, List<Integer>> mapsByNpc = new HashMap<>();
        for (MapSpawns map : byMap.values()) {
            for (Map.Entry<Integer, Integer> e : map.mobCounts().entrySet()) {
                byMob.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                        .add(new SpawnSite(map.mapId(), e.getValue()));
            }
            for (int npcId : map.npcs()) {
                mapsByNpc.computeIfAbsent(npcId, k -> new ArrayList<>()).add(map.mapId());
            }
        }
        for (List<SpawnSite> sites : byMob.values()) {
            sites.sort((a, b) -> Integer.compare(b.spawnPoints(), a.spawnPoints()));
        }
        byMob.replaceAll((k, v) -> List.copyOf(v));
        mapsByNpc.replaceAll((k, v) -> List.copyOf(v));
        return new Index(Collections.unmodifiableMap(byMap), Collections.unmodifiableMap(byMob),
                Collections.unmodifiableMap(mapsByNpc));
    }

    // ---- disk cache: one row per map: mapId \t town(0/1) \t areaPx \t mobId:count,... \t npcId,... ----

    private static Index loadCache() {
        if (!Files.isRegularFile(CACHE_FILE)) {
            return null;
        }
        try {
            Map<Integer, MapSpawns> byMap = new HashMap<>();
            for (String line : Files.readAllLines(CACHE_FILE, StandardCharsets.US_ASCII)) {
                if (line.isBlank()) {
                    continue;
                }
                String[] cols = line.split("\t", -1);
                int mapId = Integer.parseInt(cols[0]);
                boolean town = "1".equals(cols[1]);
                int areaPx = Integer.parseInt(cols[2]);
                Map<Integer, Integer> mobCounts = new HashMap<>();
                if (cols.length > 3 && !cols[3].isEmpty()) {
                    for (String pair : cols[3].split(",")) {
                        int sep = pair.indexOf(':');
                        mobCounts.put(Integer.parseInt(pair.substring(0, sep)),
                                Integer.parseInt(pair.substring(sep + 1)));
                    }
                }
                List<Integer> npcs = new ArrayList<>();
                if (cols.length > 4 && !cols[4].isEmpty()) {
                    for (String s : cols[4].split(",")) {
                        npcs.add(Integer.parseInt(s));
                    }
                }
                byMap.put(mapId, new MapSpawns(mapId, town, areaPx, Map.copyOf(mobCounts), List.copyOf(npcs)));
            }
            return byMap.isEmpty() ? null : withMobIndex(byMap);
        } catch (IOException | RuntimeException e) {
            log.warn("Bot spawn index: cache unreadable, rescanning WZ", e);
            return null;
        }
    }

    private static void writeCache(Index built) {
        try {
            Files.createDirectories(CACHE_FILE.getParent());
            StringBuilder sb = new StringBuilder(1 << 20);
            for (MapSpawns map : built.byMap().values()) {
                sb.append(map.mapId()).append('\t').append(map.town() ? 1 : 0)
                        .append('\t').append(map.areaPx()).append('\t');
                boolean first = true;
                for (Map.Entry<Integer, Integer> e : map.mobCounts().entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    sb.append(e.getKey()).append(':').append(e.getValue());
                    first = false;
                }
                sb.append('\t');
                first = true;
                for (int npcId : map.npcs()) {
                    if (!first) {
                        sb.append(',');
                    }
                    sb.append(npcId);
                    first = false;
                }
                sb.append('\n');
            }
            Files.writeString(CACHE_FILE, sb.toString(), StandardCharsets.US_ASCII);
        } catch (IOException e) {
            log.warn("Bot spawn index: couldn't write cache (will rescan next boot)", e);
        }
    }
}
