package server.bots;

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
            "Steel", "Lance", "Havoc", "Grim", "Fury", "Forge"
    );

    private static final List<String> ROOTS_MAGE = List.of(
            "Mage", "Arcane", "Frost", "Inferno", "Sage", "Spell", "Rune",
            "Hex", "Ether", "Mystic", "Vortex", "Ember", "Wield", "Aether",
            "Prism", "Lunar", "Solar", "Enigma", "Oracle", "Wyrd"
    );

    private static final List<String> ROOTS_BOWMAN = List.of(
            "Arrow", "Hunter", "Ranger", "Snipe", "Marks", "Strider", "Hawk",
            "Quill", "Bolt", "Gale", "Swift", "Fledge", "Scout", "Trace",
            "Keen", "Aim", "Covert", "Volley", "Talon", "Zephyr"
    );

    private static final List<String> ROOTS_THIEF = List.of(
            "Shadow", "Night", "Rogue", "Phantom", "Stealth", "Venom", "Dusk",
            "Ghost", "Cloak", "Shroud", "Whisper", "Shade", "Lurk", "Wraith",
            "Dagger", "Nimble", "Cipher", "Eclipse", "Thorne", "Wren"
    );

    private static final List<String> ROOTS_PIRATE = List.of(
            "Cannon", "Corsair", "Storm", "Bullet", "Buccaner", "Outlaw", "Salt",
            "Brine", "Cutlass", "Flint", "Powder", "Plunder", "Wake",
            "Havoc", "Marl", "Keel", "Scurvy", "Bilge", "Mast", "Reef"
    );

    // Flat merged pool used for job-agnostic generation
    private static final List<List<String>> ALL_POOLS = List.of(
            ROOTS_UNISEX, ROOTS_WARRIOR, ROOTS_MAGE, ROOTS_BOWMAN, ROOTS_THIEF, ROOTS_PIRATE
    );

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Generate a random, job-agnostic name and validate it against the DB.
     * Retries up to {@value MAX_RETRIES} times; falls back to a plain {@code Root+number}.
     */
    public static String generate() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            String candidate = stylize(pickRoot(rng), rng);
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
            String candidate = stylize(pickRoot(rng), rng);
            if (VALID.matcher(candidate).matches()) return candidate;
        }
        return fallback(rng);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private static String pickRoot(ThreadLocalRandom rng) {
        List<String> pool = ALL_POOLS.get(rng.nextInt(ALL_POOLS.size()));
        return pool.get(rng.nextInt(pool.size()));
    }

    /**
     * Apply 0–2 random transforms.
     * Weights: 0 transforms = 40% (plain/boring), 1 = 35% (light), 2 = 25% (heavy).
     */
    static String stylize(String root, ThreadLocalRandom rng) {
        int roll = rng.nextInt(100);
        int transforms = roll < 40 ? 0 : roll < 75 ? 1 : 2;

        String name = root;
        if (transforms == 0) {
            // Plain: optionally add a number suffix
            if (rng.nextBoolean()) {
                name = applyNumberSuffix(name, rng);
            }
            return truncate(name);
        }

        // Build a transform sequence (duplicates allowed — they add variety)
        int[] seq = new int[transforms];
        for (int i = 0; i < transforms; i++) seq[i] = rng.nextInt(5);

        for (int t : seq) {
            name = switch (t) {
                case 0 -> applyPrefixSuffix(name, rng);
                case 1 -> applyLeet(name, rng);
                case 2 -> applyAltCaps(name, rng);
                case 3 -> applyNumberSuffix(name, rng);
                case 4 -> applyDoubleEdge(name, rng);
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
        String[] edges = {"xx", "ii", "oo", "vv", "zz"};
        String edge = edges[rng.nextInt(edges.length)];
        String pre = rng.nextBoolean() ? edge.toUpperCase() : edge;
        String suf = rng.nextBoolean() ? edge.toUpperCase() : edge;
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

    /** e.g. "ooMANAoo", "xxBladexx" */
    private static String applyDoubleEdge(String name, ThreadLocalRandom rng) {
        String[] edges = {"oo", "xx", "ii", "zz"};
        String edge = edges[rng.nextInt(edges.length)];
        return truncate(edge + name + edge);
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
