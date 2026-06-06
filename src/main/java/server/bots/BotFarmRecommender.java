package server.bots;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Answers the "where do I farm &lt;item&gt;" bot command.
 *
 * Backed by two precomputed resources generated offline from the WZ + drop SQL:
 *   bots/item-names.tsv : itemid \t display name (farmable items only)
 *   bots/item-farm.tsv  : itemid \t best dropper mob name \t map name
 * The dropper is the highest-chance non-boss mob (falling back to a boss) that spawns on a named map.
 */
public final class BotFarmRecommender {

    private static volatile Map<String, Integer> nameToId;   // lowercased name -> itemid
    private static volatile Map<Integer, String> idToName;   // itemid -> display name
    private static volatile Map<Integer, String[]> farm;     // itemid -> {mobName, mapName}

    private BotFarmRecommender() {
    }

    private static void load() {
        if (nameToId != null) {
            return;
        }
        synchronized (BotFarmRecommender.class) {
            if (nameToId != null) {
                return;
            }
            Map<String, Integer> n2i = new HashMap<>();
            Map<Integer, String> i2n = new HashMap<>();
            Map<Integer, String[]> fm = new HashMap<>();
            readTsv("/bots/item-names.tsv", p -> {
                int id = Integer.parseInt(p[0]);
                i2n.put(id, p[1]);
                n2i.putIfAbsent(p[1].toLowerCase(), id);
            });
            readTsv("/bots/item-farm.tsv", p -> {
                if (p.length >= 3) {
                    fm.put(Integer.parseInt(p[0]), new String[]{p[1], p[2]});
                }
            });
            idToName = i2n;
            nameToId = n2i;
            farm = fm;
        }
    }

    private interface RowConsumer {
        void accept(String[] parts);
    }

    private static void readTsv(String resource, RowConsumer consumer) {
        try (InputStream in = BotFarmRecommender.class.getResourceAsStream(resource)) {
            if (in == null) {
                return;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t");
                if (p.length >= 2) {
                    consumer.accept(p);
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** Returns "ItemName drops from MobName @ MapName", or null if nothing matches / no known spot. */
    public static String recommend(String query) {
        load();
        if (query == null) {
            return null;
        }
        String q = query.toLowerCase().trim();
        if (q.isEmpty()) {
            return null;
        }

        Integer id = nameToId.get(q);
        if (id == null) {
            // Fuzzy: prefer the shortest item name that contains the query (most specific match).
            String best = null;
            for (Map.Entry<String, Integer> e : nameToId.entrySet()) {
                String name = e.getKey();
                if (name.contains(q) || q.contains(name)) {
                    if (best == null || name.length() < best.length()) {
                        best = name;
                        id = e.getValue();
                    }
                }
            }
        }
        if (id == null) {
            return null;
        }
        String[] spot = farm.get(id);
        if (spot == null) {
            return null;
        }
        return idToName.getOrDefault(id, "That item") + " drops from " + spot[0] + " @ " + spot[1];
    }
}
