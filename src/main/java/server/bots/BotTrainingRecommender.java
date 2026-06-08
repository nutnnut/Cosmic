package server.bots;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Recommends training spots for the "where should we train" bot command.
 *
 * Backed by a curated level-bracket leveling guide (src/main/resources/bots/training-guide.txt).
 * Each row is {@code <loLevel> <hiLevel> <recommendation text>}. A character's level maps to the
 * bracket with the greatest {@code loLevel <= level} (so each level resolves to exactly one
 * bracket, even where bracket labels share an endpoint). Rows are returned in file order, so the
 * guide lists the best spots for a bracket first. No WZ scanning happens at runtime.
 */
public final class BotTrainingRecommender {

    private record Spot(int lo, int hi, String text) {}

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
            try (InputStream in = BotTrainingRecommender.class.getResourceAsStream("/bots/training-guide.txt")) {
                if (in != null) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    String line;
                    while ((line = r.readLine()) != null) {
                        line = line.strip();
                        if (line.isEmpty() || line.startsWith("#")) {
                            continue;
                        }
                        String[] p = line.split("\\s+", 3);
                        if (p.length < 3) {
                            continue;
                        }
                        try {
                            parsed.add(new Spot(Integer.parseInt(p[0]), Integer.parseInt(p[1]), p[2]));
                        } catch (NumberFormatException ignored) {
                            // skip malformed rows
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            spots = parsed;
            return parsed;
        }
    }

    /** Bracket label (e.g. "15-30") for the level, or null if no bracket matches. */
    public static String bracketLabel(int level) {
        Spot b = bracketFor(level);
        return b == null ? null : b.lo() + "-" + b.hi();
    }

    /** Up to {@code limit} curated spots for the bracket containing {@code level}, in file order. */
    public static List<String> recommend(int level, int limit) {
        Spot bracket = bracketFor(level);
        List<String> out = new ArrayList<>();
        if (bracket == null) {
            return out;
        }
        for (Spot s : load()) {
            if (s.lo() == bracket.lo() && s.hi() == bracket.hi()) {
                out.add(s.text());
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    /** The bracket with the greatest loLevel that is &lt;= level, so each level maps to one bracket. */
    private static Spot bracketFor(int level) {
        Spot best = null;
        for (Spot s : load()) {
            if (s.lo() <= level && (best == null || s.lo() > best.lo())) {
                best = s;
            }
        }
        return best;
    }
}
