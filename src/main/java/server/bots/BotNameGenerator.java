package server.bots;

import client.Job;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Procedural MMO IGN generator for auto-spawned ownerless bots.
 * <p>
 * Produces humanlike names mixing plain, stylized, and class-flavored styles.
 * All output satisfies {@code [a-zA-Z0-9]{3,12}} and is validated via
 * {@link client.Character#canCreateChar(String)} before being returned.
 */
public class BotNameGenerator {

    private static final Pattern VALID = Pattern.compile("[a-zA-Z0-9]{3,12}");
    private static final int MAX_RETRIES = 20;

    // ── Root pools ────────────────────────────────────────────────────────────

    private static final List<String> ROOTS_UNISEX = List.of(
            "Alex", "Sam", "Blake", "Robin", "Jordan", "Riley", "Morgan",
            "Casey", "Taylor", "Drew", "River", "Ash", "Sky", "Kai",
            "Noel", "Nico", "Lee", "Nova", "Zion", "Quinn"
    );

    private static final List<String> ROOTS_WARRIOR = List.of(
            "Drake", "Blade", "Axe", "Knight", "Guard", "Crusade", "Warlord",
            "Iron", "Titan", "Valor", "Crest", "Siege", "Bastion", "Rampart",
            "Steel", "Lance", "Havoc", "Grim", "Fury", "Forge", "Hero", "Tank"
    );

    private static final List<String> ROOTS_MAGE = List.of(
            "Mage", "Arcane", "Frost", "Inferno", "Sage", "Spell", "Rune",
            "Hex", "Ether", "Mystic", "Vortex", "Ember", "Wield", "Aether",
            "Prism", "Lunar", "Solar", "Enigma", "Oracle", "Wyrd", "Bishop"
    );

    private static final List<String> ROOTS_BOWMAN = List.of(
            "Arrow", "Hunter", "Ranger", "Snipe", "Marks", "Strider", "Hawk",
            "Quill", "Bolt", "Gale", "Swift", "Fledge", "Scout", "Trace",
            "Keen", "Aim", "Covert", "Volley", "Talon", "Zephyr", "Archer"
    );

    private static final List<String> ROOTS_THIEF = List.of(
            "Shadow", "Night", "Rogue", "Phantom", "Stealth", "Venom", "Dusk",
            "Ghost", "Cloak", "Shroud", "Whisper", "Shade", "Lurk", "Wraith",
            "Dagger", "Nimble", "Cipher", "Eclipse", "Thorne", "Wren", "Sin", "Ninja"
    );

    private static final List<String> ROOTS_PIRATE = List.of(
            "Cannon", "Corsair", "Storm", "Bullet", "Buccaner", "Outlaw", "Salt",
            "Brine", "Cutlass", "Flint", "Powder", "Plunder", "Wake",
            "Havoc", "Marl", "Keel", "Scurvy", "Bilge", "Mast", "Reef"
    );

    // MapleStory-specific flavor: economy / slang / class lingo distilled from real IGN samples
    // (Maple, God, Sin, Scroll, Mule, Grind, Fame, Funded, Chair, Slot, Boss, Toxic, Noob, Pro...).
    // This is what makes a generated name read like a real MapleStory player vs a generic fantasy stem.
    // All <= 6 chars so they combine (cap()s to "ScrollKing", "BossMule", "GrindGod", "ToxicNoob").
    private static final List<String> ROOTS_MAPLE = List.of(
            "Maple", "Scroll", "Fame", "Funded", "Chair", "Slot", "Boss", "Ramen", "Grind", "Toxic",
            "Noob", "God", "Lord", "King", "Dark", "Rich", "Mule", "Pog", "Pro", "Leech", "Meso",
            "Loot", "Rush", "Solo", "Carry", "Sweat", "Smega", "Whale", "Crit", "Buff", "Drop", "Kill"
    );

    // Flat merged pool used for job-agnostic generation
    private static final List<List<String>> ALL_POOLS = List.of(
            ROOTS_UNISEX, ROOTS_WARRIOR, ROOTS_MAGE, ROOTS_BOWMAN, ROOTS_THIEF, ROOTS_PIRATE, ROOTS_MAPLE
    );

    // ── Generic dictionary pool (freq-ranked, loaded from resource) ────────────
    // The combination engine's mass comes from here: ~4k combinable words → N²
    // distinct two-word names. Job pools above are flavor overlays unioned in
    // per-class; they never replace the generic pool.

    private static final List<String> GENERIC_COMBINABLE; // len 3-6, used for combos
    private static final List<String> GENERIC_STANDALONE; // len 7-8, single-word names

    static {
        List<String> comb = new ArrayList<>();
        List<String> stand = new ArrayList<>();
        try (InputStream in = BotNameGenerator.class.getResourceAsStream("/bot/name_words.txt")) {
            if (in != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
                String line;
                List<String> sink = comb;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.equals("[combinable]")) { sink = comb; continue; }
                    if (line.equals("[standalone]")) { sink = stand; continue; }
                    sink.add(line);
                }
            }
        } catch (IOException ignored) {
            // fall through to the hardcoded fallback below
        }
        if (comb.isEmpty()) {
            // ponytail: resource missing (never expected on classpath) — degrade to the
            // curated pools so the generator still works, just with far less variety.
            for (List<String> p : ALL_POOLS) for (String w : p) {
                (w.length() <= 6 ? comb : stand).add(w.toLowerCase());
            }
        }
        GENERIC_COMBINABLE = List.copyOf(comb);
        GENERIC_STANDALONE = List.copyOf(stand.isEmpty() ? comb : stand);
    }

    /** Curated themed words for a job branch (getId()/100), as a flavor overlay. */
    private static List<String> jobThemed(Job job) {
        int branch = job.getId() / 100;
        return switch (branch) {
            case 1, 11 -> ROOTS_WARRIOR;  // explorer + cygnus dawn warrior
            case 2, 12 -> ROOTS_MAGE;
            case 3, 13 -> ROOTS_BOWMAN;
            case 4, 14 -> ROOTS_THIEF;
            case 5, 15 -> ROOTS_PIRATE;
            default -> ROOTS_UNISEX;
        };
    }

    // ── Public API ────────────────────────────────────────────────────────────

    // Flat curated overlay used for job-agnostic flavor (all themed stems).
    private static final List<String> ALL_CURATED;
    static {
        List<String> all = new ArrayList<>();
        for (List<String> p : ALL_POOLS) all.addAll(p);
        ALL_CURATED = List.copyOf(all);
    }

    /**
     * Generate a random, job-agnostic name and validate it against the DB.
     * Retries up to {@value MAX_RETRIES} times; falls back to a plain {@code Root+number}.
     */
    public static String generate() {
        return generate(ALL_CURATED);
    }

    /**
     * Generate a name flavored toward the rolled class: the combination engine draws
     * from the generic dictionary UNIONed with the job's themed overlay (not replaced).
     */
    public static String generate(Job firstJob) {
        return generate(jobThemed(firstJob));
    }

    private static String generate(List<String> themedOverlay) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            String candidate = stylize(composeBase(themedOverlay, rng), rng);
            if (isValid(candidate)) return candidate;
        }
        return fallback(rng);
    }

    /**
     * Generate a name without the DB uniqueness check — for unit tests only.
     * Validates only the regex/length constraint.
     */
    static String generateForTest() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            String candidate = stylize(composeBase(ALL_CURATED, rng), rng);
            if (VALID.matcher(candidate).matches()) return candidate;
        }
        return fallback(rng);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * Build the base name before stylizing. ~55% two-word CamelCase combos (the N²
     * variety lever), ~25% single standalone word, ~20% single short word. The themed
     * overlay is mixed into each word pick (~25% chance), unioning class flavor over
     * the generic dictionary without copying the big list.
     */
    static String composeBase(List<String> themed, ThreadLocalRandom rng) {
        int roll = rng.nextInt(100);
        String base = null;
        if (roll < 55) {
            for (int i = 0; i < 6; i++) {
                String s = cap(pickCombinable(themed, rng)) + cap(pickCombinable(themed, rng));
                if (s.length() >= 3 && s.length() <= 12) {
                    base = s;
                    break;
                }
            }
            // all 6 tries too long — fall through to a single word
        }
        if (base == null) {
            base = roll < 80 ? cap(pickStandalone(themed, rng)) : cap(pickCombinable(themed, rng));
        }
        // Real players often don't capitalize at all (~7% of IGNs are fully lowercase, e.g. "larryang",
        // "dualbladeyo") — so sometimes drop the CamelCase entirely.
        if (rng.nextInt(100) < 12) {
            base = base.toLowerCase();
        }
        return base;
    }

    private static String pickCombinable(List<String> themed, ThreadLocalRandom rng) {
        if (!themed.isEmpty() && rng.nextInt(100) < 25) {
            String w = themed.get(rng.nextInt(themed.size()));
            if (w.length() <= 6) return w.toLowerCase();
        }
        return GENERIC_COMBINABLE.get(rng.nextInt(GENERIC_COMBINABLE.size()));
    }

    private static String pickStandalone(List<String> themed, ThreadLocalRandom rng) {
        if (!themed.isEmpty() && rng.nextInt(100) < 25) {
            String w = themed.get(rng.nextInt(themed.size()));
            if (w.length() >= 7 && w.length() <= 12) return w.toLowerCase();
        }
        return GENERIC_STANDALONE.get(rng.nextInt(GENERIC_STANDALONE.size()));
    }

    /** Uppercase first letter, leave the rest (words load lowercase). */
    private static String cap(String w) {
        if (w.isEmpty()) return w;
        return java.lang.Character.toUpperCase(w.charAt(0)) + w.substring(1);
    }

    /**
     * Apply 0–3 random transforms. Most names are plain (real players aren't all
     * stylized); the rare 3-stack lets full looks like "xXR4ngerXx" appear (~0.6%
     * of names) without making styling repetitive.
     * Weights: 0 = 60% (plain), 1 = 22% (light), 2 = 12%, 3 = 6% (heavy stack).
     */
    static String stylize(String root, ThreadLocalRandom rng) {
        int roll = rng.nextInt(100);
        int transforms = roll < 60 ? 0 : roll < 82 ? 1 : roll < 94 ? 2 : 3;

        String name = root;
        if (transforms == 0) {
            // Plain: optionally add a number suffix
            if (rng.nextBoolean()) {
                name = applyNumberSuffix(name, rng);
            }
            return truncate(name);
        }

        // Build a transform sequence (duplicates allowed — they add variety).
        // doubleEdge removed: it was a strict subset of prefixSuffix (same wrap, no
        // casing) — keeping both doubled edge-wrap frequency for no extra variety.
        int[] seq = new int[transforms];
        for (int i = 0; i < transforms; i++) seq[i] = rng.nextInt(4);

        for (int t : seq) {
            name = switch (t) {
                case 0 -> applyPrefixSuffix(name, rng);
                case 1 -> applyLeet(name, rng);
                case 2 -> applyAltCaps(name, rng);
                case 3 -> applyNumberSuffix(name, rng);
                default -> name;
            };
            name = truncate(name); // enforce limit after each step
        }
        return name;
    }

    // ── Transforms ────────────────────────────────────────────────────────────

    /** e.g. "xXBladeXx", "__Drake__" */
    private static String applyPrefixSuffix(String name, ThreadLocalRandom rng) {
        // Name regex is [a-zA-Z0-9] only — no underscore (canCreateChar forbids it).
        String[] edges = {"xx", "x", "xX", "Xx", "xXx", "o", "oo", "oO", "Oo", "oOo", "0", "00", "I", "II"};
        String pre = edges[rng.nextInt(edges.length)];
        if (rng.nextBoolean()) {
            pre = pre.toUpperCase();
        }
        // Suffix MIRRORS the prefix (character-reversed) so it reads symmetrically:
        // "xX" + name + "Xx", not "xX" + name + "xX".
        String suf = new StringBuilder(pre).reverse().toString();
        return truncate(pre + name + suf);
    }

    /** e.g. "B4nd1T", "Fr057" */
    private static String applyLeet(String name, ThreadLocalRandom rng) {
        char[] cs = name.toCharArray();
        for (int i = 0; i < cs.length; i++) {
            if (rng.nextInt(3) == 0) { // ~33% per char
                cs[i] = switch (java.lang.Character.toLowerCase(cs[i])) {
                    case 'a' -> '4';
                    case 'e' -> '3';
                    case 'i' -> '1';
                    case 'o' -> '0';
                    case 's' -> '5';
                    case 't' -> '7';
                    default -> cs[i];
                };
            }
        }
        return new String(cs);
    }

    /** e.g. "yOuRdAdDy", "sHaDoW" */
    private static String applyAltCaps(String name, ThreadLocalRandom rng) {
        char[] cs = name.toCharArray();
        boolean upper = rng.nextBoolean();
        for (int i = 0; i < cs.length; i++) {
            cs[i] = upper ? java.lang.Character.toUpperCase(cs[i]) : java.lang.Character.toLowerCase(cs[i]);
            if (java.lang.Character.isLetter(cs[i])) upper = !upper;
        }
        return new String(cs);
    }

    /** e.g. "Blade124", "Mage999" */
    private static String applyNumberSuffix(String name, ThreadLocalRandom rng) {
        int digits = rng.nextInt(3) + 1; // 1–3 digits
        int max = (int) Math.pow(10, digits);
        return truncate(name + rng.nextInt(max));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String truncate(String s) {
        return s.length() > 12 ? s.substring(0, 12) : s;
    }

    private static boolean isValid(String name) {
        return VALID.matcher(name).matches() && client.Character.canCreateChar(name);
    }

    /**
     * Last-resort fallback: short root + zero-padded 4-digit number.
     * Max length = 8 + 4 = 12. Callers should not rely on this being DB-unique.
     */
    private static String fallback(ThreadLocalRandom rng) {
        String[] shortRoots = {"Bot", "Anon", "Hero", "Rift", "Saga", "Dusk", "Dawn"};
        String root = shortRoots[rng.nextInt(shortRoots.length)];
        return root + String.format("%04d", rng.nextInt(10000));
    }
}
