package server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Persistent disk cache for {@link ItemInformationProvider#getEquipStats(int)} results.
 * The v83 WZ data is a static XML dump, so the ~10k per-equip img parses that boot needs
 * (DressingRoom walks every equip) are identical every run — caching them cuts that load
 * from ~66s to under a second. Bump {@link #VERSION} whenever getEquipStats adds, removes
 * or renames keys, or the stale file would silently miss the new keys.
 */
public final class EquipStatsDiskCache {
    private static final Logger log = LoggerFactory.getLogger(EquipStatsDiskCache.class);
    // v1 keys: inc* + reqJob/reqLevel/reqDEX/reqSTR/reqINT/reqLUK/reqPOP/cash/tuc/cursed/fs/success
    private static final int VERSION = 1;
    private static final Path FILE = Path.of("cache", "equip-stats", "v" + VERSION, "equip-stats.tsv");

    private EquipStatsDiskCache() {
    }

    /** Primes the provider cache from disk. Returns false (and loads nothing) when the cache
     *  file is absent or unreadable — callers then do the full WZ load and {@link #dump}. */
    public static boolean preload(ItemInformationProvider ii) {
        if (!Files.isRegularFile(FILE)) {
            return false;
        }
        try {
            long start = System.currentTimeMillis();
            Map<Integer, Map<String, Integer>> entries = parse(Files.readAllLines(FILE, StandardCharsets.US_ASCII));
            ii.primeEquipStatsCache(entries);
            log.info("Primed {} equip stat entries from {} in {}ms.",
                    entries.size(), FILE, System.currentTimeMillis() - start);
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Ignoring unreadable equip stats cache {} - doing a full WZ load", FILE, e);
            return false;
        }
    }

    public static void dump(ItemInformationProvider ii) {
        try {
            Files.createDirectories(FILE.getParent());
            Files.write(FILE, serialize(ii.equipStatsCacheSnapshot()), StandardCharsets.US_ASCII);
            log.info("Wrote equip stats cache to {}.", FILE);
        } catch (IOException e) {
            log.warn("Failed to write equip stats cache to {}", FILE, e);
        }
    }

    // Row format: itemId TAB key=value;key=value;... — sorted by itemId for determinism.
    static List<String> serialize(Map<Integer, Map<String, Integer>> entries) {
        List<String> lines = new ArrayList<>(entries.size());
        for (Map.Entry<Integer, Map<String, Integer>> e : new TreeMap<>(entries).entrySet()) {
            StringBuilder sb = new StringBuilder().append(e.getKey()).append('\t');
            boolean first = true;
            for (Map.Entry<String, Integer> stat : e.getValue().entrySet()) {
                if (!first) {
                    sb.append(';');
                }
                sb.append(stat.getKey()).append('=').append(stat.getValue());
                first = false;
            }
            lines.add(sb.toString());
        }
        return lines;
    }

    static Map<Integer, Map<String, Integer>> parse(List<String> lines) {
        Map<Integer, Map<String, Integer>> entries = new LinkedHashMap<>(lines.size() * 2);
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            int tab = line.indexOf('\t');
            int itemId = Integer.parseInt(line.substring(0, tab));
            Map<String, Integer> stats = new LinkedHashMap<>();
            String payload = line.substring(tab + 1);
            if (!payload.isEmpty()) {
                for (String token : payload.split(";")) {
                    int eq = token.indexOf('=');
                    stats.put(token.substring(0, eq), Integer.parseInt(token.substring(eq + 1)));
                }
            }
            entries.put(itemId, stats);
        }
        return entries;
    }
}
