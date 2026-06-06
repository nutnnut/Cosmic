package server.bots;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Recommends training spots for the "where should we train" bot command.
 *
 * Backed by a precomputed dataset (src/main/resources/bots/training-spots.tsv) generated offline
 * from the WZ data: each row is a non-boss mob that spawns on a named map, with level / exp / maxHP
 * and the map where it spawns most. Ranking is by exp-per-HP (kill speed), so it favours fast kills
 * over high-exp damage sponges. No WZ scanning happens at runtime.
 */
public final class BotTrainingRecommender {

    private record Spot(int level, int exp, int maxHp, String mob, String map) {
        double expPerHp() {
            return maxHp > 0 ? (double) exp / maxHp : 0;
        }
    }

    private static volatile List<Spot> spots;

    private BotTrainingRecommender() {
    }

    private static List<Spot> load() {
        List<Spot> cached = spots;
        if (cached != null) {
            return cached;
        }
        synchronized (BotTrainingRecommender.class) {
            if (spots != null) {
                return spots;
            }
            List<Spot> parsed = new ArrayList<>();
            try (InputStream in = BotTrainingRecommender.class.getResourceAsStream("/bots/training-spots.tsv")) {
                if (in != null) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    String line;
                    while ((line = r.readLine()) != null) {
                        String[] p = line.split("\t");
                        if (p.length < 5) {
                            continue;
                        }
                        parsed.add(new Spot(Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                                Integer.parseInt(p[2]), p[3], p[4]));
                    }
                }
            } catch (Exception ignored) {
            }
            spots = parsed;
            return parsed;
        }
    }

    /** Up to {@code limit} spots whose mob level is within [loLevel, hiLevel], ranked by exp/HP. */
    public static List<String> recommend(int loLevel, int hiLevel, int limit) {
        List<Spot> inRange = new ArrayList<>();
        for (Spot s : load()) {
            if (s.level() >= loLevel && s.level() <= hiLevel) {
                inRange.add(s);
            }
        }
        inRange.sort(Comparator.comparingDouble(Spot::expPerHp).reversed());

        List<String> out = new ArrayList<>();
        for (Spot s : inRange) {
            if (out.size() >= limit) {
                break;
            }
            String tag = out.isEmpty() ? ", best exp/kill" : "";
            out.add(s.mob() + " (Lv." + s.level() + tag + ") — " + s.map());
        }
        return out;
    }
}
