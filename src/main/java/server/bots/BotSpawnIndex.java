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
    private static final int INDEX_VERSION = 1;
    private static final Path CACHE_FILE =
            Path.of("cache", "bot-spawn", "v" + INDEX_VERSION, "spawn-index.tsv");

    /** One field's spawns: total spawn points per mob id ({@code hide}-flagged life excluded). */
    record MapSpawns(int mapId, boolean town, Map<Integer, Integer> mobCounts) {
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

    record Index(Map<Integer, MapSpawns> byMap, Map<Integer, List<SpawnSite>> byMob) {}

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
        Map<Integer, MapSpawns> byMap = new HashMap<>();
        DataProvider mapSource = DataProviderFactory.getDataProvider(WZFiles.MAP);
        Path mapRoot = Path.of(WZFiles.MAP.getFilePath(), "Map");
        int failures = 0;
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
                    try {
                        int mapId = Integer.parseInt(name.substring(0, name.length() - ".img.xml".length()));
                        MapSpawns spawns = readMap(mapSource, area, mapId);
                        if (spawns != null) {
                            byMap.put(mapId, spawns);
                        }
                    } catch (NumberFormatException ignored) {
                        // non-map file (e.g. AreaCode.img.xml) — skip
                    } catch (RuntimeException e) {
                        failures++;
                    }
                }
            } catch (IOException e) {
                log.warn("Bot spawn index: can't list {}", areaDir, e);
            }
        }
        if (failures > 0) {
            log.warn("Bot spawn index: {} map files failed to parse (skipped)", failures);
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
        if (!link.isEmpty()) {
            try {
                int linkId = Integer.parseInt(link);
                mapData = mapSource.getData(mapImgPath(linkId / 100000000, linkId));
                if (mapData == null) {
                    return new MapSpawns(mapId, town, Map.of());
                }
            } catch (NumberFormatException ignored) {
                // malformed link — read the map as-is
            }
        }
        Map<Integer, Integer> mobCounts = new HashMap<>();
        Data life = mapData.getChildByPath("life");
        if (life != null) {
            for (Data entry : life) {
                String type = DataTool.getString("type", entry, "");
                if (!"m".equals(type)) {
                    continue;
                }
                if (DataTool.getInt("hide", entry, 0) == 1) {
                    continue;
                }
                String id = DataTool.getString("id", entry, null);
                if (id == null) {
                    continue;
                }
                try {
                    mobCounts.merge(Integer.parseInt(id), 1, Integer::sum);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return new MapSpawns(mapId, town, Map.copyOf(mobCounts));
    }

    private static String mapImgPath(int area, int mapId) {
        return "Map/Map" + area + "/" + String.format("%09d", mapId) + ".img";
    }

    private static Index withMobIndex(Map<Integer, MapSpawns> byMap) {
        Map<Integer, List<SpawnSite>> byMob = new HashMap<>();
        for (MapSpawns map : byMap.values()) {
            for (Map.Entry<Integer, Integer> e : map.mobCounts().entrySet()) {
                byMob.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                        .add(new SpawnSite(map.mapId(), e.getValue()));
            }
        }
        for (List<SpawnSite> sites : byMob.values()) {
            sites.sort((a, b) -> Integer.compare(b.spawnPoints(), a.spawnPoints()));
        }
        byMob.replaceAll((k, v) -> List.copyOf(v));
        return new Index(Collections.unmodifiableMap(byMap), Collections.unmodifiableMap(byMob));
    }

    // ---- disk cache: one row per map: mapId \t town(0/1) \t mobId:count,mobId:count ----

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
                Map<Integer, Integer> mobCounts = new HashMap<>();
                if (cols.length > 2 && !cols[2].isEmpty()) {
                    for (String pair : cols[2].split(",")) {
                        int sep = pair.indexOf(':');
                        mobCounts.put(Integer.parseInt(pair.substring(0, sep)),
                                Integer.parseInt(pair.substring(sep + 1)));
                    }
                }
                byMap.put(mapId, new MapSpawns(mapId, town, Map.copyOf(mobCounts)));
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
                sb.append(map.mapId()).append('\t').append(map.town() ? 1 : 0).append('\t');
                boolean first = true;
                for (Map.Entry<Integer, Integer> e : map.mobCounts().entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    sb.append(e.getKey()).append(':').append(e.getValue());
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
