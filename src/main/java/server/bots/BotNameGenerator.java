package server.bots;

import client.Job;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    // 2nd/3rd/4th class flavor, keyed by the PLANNED 2nd job — each explorer branch forks into distinct
    // job trees, so an assassin (sin->hermit->night lord) must never read as a shadower (bandit tree).
    // Only overlaid when the bot's 2nd job is planned; words are the tree's class names + signature skills.
    private static final Map<Job, List<String>> ROOTS_BY_2ND = Map.ofEntries(
            // WARRIOR
            Map.entry(Job.FIGHTER, List.of("Fighter", "Crusade", "Hero", "Brandish", "Rage", "Combo")),
            Map.entry(Job.PAGE, List.of("Page", "Paladin", "Charge", "Blast", "Divine", "Threaten")),
            Map.entry(Job.SPEARMAN, List.of("Spear", "DrK", "Berserk", "Crusher", "Sacrifice", "Beholder")),
            // MAGICIAN
            Map.entry(Job.FP_WIZARD, List.of("Flame", "Meteor", "Poison", "Ifrit", "Ember", "Paralyze")),
            Map.entry(Job.IL_WIZARD, List.of("Frost", "Blizz", "Ice", "Thunder", "Glacier", "Elquines")),
            Map.entry(Job.CLERIC, List.of("Cleric", "Priest", "Bishop", "Holy", "Genesis", "Angel")),
            // BOWMAN
            Map.entry(Job.HUNTER, List.of("Hunter", "Ranger", "Bowmstr", "Phoenix", "Hurri", "Inferno")),
            Map.entry(Job.CROSSBOWMAN, List.of("Sniper", "Marks", "Frostprey", "Pierce", "Strafe", "Blizzard")),
            // THIEF
            Map.entry(Job.ASSASSIN, List.of("Sin", "Hermit", "NightLord", "Avenger", "Triple", "Shadow")),
            Map.entry(Job.BANDIT, List.of("Bandit", "Chief", "Shadower", "Boomerang", "Assault", "Meso")),
            // PIRATE
            Map.entry(Job.BRAWLER, List.of("Brawler", "Marauder", "Bucc", "Barrage", "Dragon", "Fist")),
            Map.entry(Job.GUNSLINGER, List.of("Slinger", "Outlaw", "Corsair", "Octopus", "Burst", "Rapid"))
    );

    private static final List<String> ROOTS_2ND_ALL;
    static {
        List<String> all = new ArrayList<>();
        for (List<String> p : ROOTS_BY_2ND.values()) all.addAll(p);
        ROOTS_2ND_ALL = List.copyOf(all);
    }

    // Flat merged pool used for job-agnostic generation (job-agnostic names make no class claim, so the
    // tree words are fine to mix here — the per-tree split only matters for FLAVORED names).
    private static final List<List<String>> ALL_POOLS = List.of(
            ROOTS_UNISEX, ROOTS_WARRIOR, ROOTS_MAGE, ROOTS_BOWMAN, ROOTS_THIEF, ROOTS_PIRATE, ROOTS_MAPLE,
            ROOTS_2ND_ALL
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

    /**
     * Generate a name flavored toward the planned 1st AND 2nd job: the 1st-job branch overlay plus the
     * specific 2nd-job tree's class/skill words, so e.g. an assassin reads as Sin/Hermit/NightLord and
     * never picks up a bandit-tree word like Shadower. Falls back to 1st-job-only when 2nd is unplanned.
     */
    public static String generate(Job firstJob, Job secondJob) {
        List<String> tree = secondJob == null ? List.of() : ROOTS_BY_2ND.getOrDefault(secondJob, List.of());
        if (tree.isEmpty()) {
            return generate(firstJob);
        }
        List<String> overlay = new ArrayList<>(jobThemed(firstJob));
        overlay.addAll(tree);
        return generate(overlay);
    }

    private static String generate(List<String> themedOverlay) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            String candidate = assemble(themedOverlay, rng);
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
            String candidate = assemble(ALL_CURATED, rng);
            if (VALID.matcher(candidate).matches()) return candidate;
        }
        return fallback(rng);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /** Test/back-compat entry: compose with the full 12-char budget. */
    static String composeBase(List<String> themed, ThreadLocalRandom rng) {
        return composeBase(themed, rng, 12);
    }

    /**
     * Build the (decoration-free) base name to fit within {@code budget} chars: pick 1-2
     * lowercase words whose joined length never exceeds the budget — words are filtered to
     * fit, NEVER truncated mid-string — then apply one of six capitalization styles. ~55%
     * two-word combos (the N² variety lever), else a single standalone or short word. The
     * themed overlay is mixed into each word pick (~25%), unioning class flavor over the
     * generic dictionary. When the budget is tight this naturally falls to shorter / single
     * words.
     */
    static String composeBase(List<String> themed, ThreadLocalRandom rng, int budget) {
        return applyCapitalization(pickWords(themed, rng, budget), pickCapStyle(rng));
    }

    /** Roll 1-2 lowercase words that fit {@code budget} (joined length); never truncates a word. */
    private static List<String> pickWords(List<String> themed, ThreadLocalRandom rng, int budget) {
        int roll = rng.nextInt(100);
        if (budget >= 6 && roll < 55) { // two-word combo, each word >= 3
            String w1 = pickCombinableFitting(themed, rng, budget - 3);
            if (w1 != null) {
                String w2 = pickCombinableFitting(themed, rng, budget - w1.length());
                if (w2 != null) return List.of(w1, w2);
            }
        }
        if (budget >= 7 && roll < 80) { // single standalone (7-8)
            String s = pickStandaloneFitting(themed, rng, budget);
            if (s != null) return List.of(s);
        }
        String c = pickCombinableFitting(themed, rng, budget); // single short word (budget>=3 always fits)
        return List.of(c != null ? c : "bot");
    }

    /** Combinable word (len 3-6) with length &lt;= maxLen, themed overlay mixed in; null if none. */
    private static String pickCombinableFitting(List<String> themed, ThreadLocalRandom rng, int maxLen) {
        int cap = Math.min(6, maxLen);
        if (cap < 3) return null;
        if (!themed.isEmpty() && rng.nextInt(100) < 25) {
            String w = themed.get(rng.nextInt(themed.size())).toLowerCase();
            if (w.length() >= 3 && w.length() <= cap) return w;
        }
        return pickFitting(GENERIC_COMBINABLE, rng, 3, cap);
    }

    /** Standalone word (len 7-8) with length &lt;= maxLen, themed overlay mixed in; null if none. */
    private static String pickStandaloneFitting(List<String> themed, ThreadLocalRandom rng, int maxLen) {
        int cap = Math.min(12, maxLen);
        if (cap < 7) return null;
        if (!themed.isEmpty() && rng.nextInt(100) < 25) {
            String w = themed.get(rng.nextInt(themed.size())).toLowerCase();
            if (w.length() >= 7 && w.length() <= cap) return w;
        }
        return pickFitting(GENERIC_STANDALONE, rng, 7, cap);
    }

    /** Random word from {@code pool} with len in [minLen,maxLen]: ~8 random tries, then linear scan; null if none. */
    private static String pickFitting(List<String> pool, ThreadLocalRandom rng, int minLen, int maxLen) {
        if (maxLen < minLen || pool.isEmpty()) return null;
        for (int i = 0; i < 8; i++) {
            String w = pool.get(rng.nextInt(pool.size()));
            if (w.length() >= minLen && w.length() <= maxLen) return w;
        }
        for (String w : pool) if (w.length() >= minLen && w.length() <= maxLen) return w;
        return null;
    }

    // ── Capitalization (length-neutral; chosen independently of word length) ────

    private enum Cap { CAMEL, LOWER, UPPER, ALT_EVEN, ALT_ODD, FIRST_LAST }

    /** Weighted style pick: camel 55, lower 15, upper 10, first+last 10, alt-even 5, alt-odd 5. */
    private static Cap pickCapStyle(ThreadLocalRandom rng) {
        int r = rng.nextInt(100);
        if (r < 55) return Cap.CAMEL;
        if (r < 70) return Cap.LOWER;
        if (r < 80) return Cap.UPPER;
        if (r < 90) return Cap.FIRST_LAST;
        if (r < 95) return Cap.ALT_EVEN;
        return Cap.ALT_ODD;
    }

    private static String applyCapitalization(List<String> words, Cap style) {
        return switch (style) {
            case CAMEL -> { StringBuilder sb = new StringBuilder(); for (String w : words) sb.append(cap(w)); yield sb.toString(); }
            case LOWER -> String.join("", words);
            case UPPER -> String.join("", words).toUpperCase();
            case ALT_EVEN -> altCaps(String.join("", words), true);
            case ALT_ODD -> altCaps(String.join("", words), false);
            case FIRST_LAST -> { StringBuilder sb = new StringBuilder(); for (String w : words) sb.append(capFirstLast(w)); yield sb.toString(); }
        };
    }

    /** Uppercase first letter, leave the rest (words load lowercase). */
    private static String cap(String w) {
        if (w.isEmpty()) return w;
        return java.lang.Character.toUpperCase(w.charAt(0)) + w.substring(1);
    }

    /** Uppercase the first AND last character of a word, e.g. "scrolL"; 1-char word -> uppercased. */
    private static String capFirstLast(String w) {
        if (w.isEmpty()) return w;
        if (w.length() == 1) return w.toUpperCase();
        char[] cs = w.toCharArray();
        cs[0] = java.lang.Character.toUpperCase(cs[0]);
        cs[cs.length - 1] = java.lang.Character.toUpperCase(cs[cs.length - 1]);
        return new String(cs);
    }

    /** Alternating caps toggled over LETTERS only, e.g. "ScRoLl" (startUpper) / "sCrOlL". */
    private static String altCaps(String s, boolean startUpper) {
        char[] cs = s.toCharArray();
        boolean upper = startUpper;
        for (int i = 0; i < cs.length; i++) {
            if (java.lang.Character.isLetter(cs[i])) {
                cs[i] = upper ? java.lang.Character.toUpperCase(cs[i]) : java.lang.Character.toLowerCase(cs[i]);
                upper = !upper;
            }
        }
        return new String(cs);
    }

    // ── Decoration pipeline (decorations sized FIRST so words fit the leftover) ──

    /**
     * A length-decided decoration plan. Sizes are fixed up front (wrap edge, the literal number
     * string, the minor-flavor kind) so {@link #cost()} is known before words are picked — that is
     * what lets {@link #composeBase} fit words into the leftover budget without ever truncating one.
     * {@code leet} is length-neutral.
     */
    private static final class Plan {
        String wrapEdge;        // null = no wrap; suffix is its char-reverse
        String numberStr = "";  // "" = no number
        int minorKind = -1;     // -1 = none; else 0..3 (see applyMinor)
        boolean leet;

        int cost() {
            int c = 0;
            if (wrapEdge != null) c += 2 * wrapEdge.length();
            c += numberStr.length();
            c += MINOR_COST[minorKind + 1]; // index 0 = none
            return c;
        }

        /** Drop the cheapest-to-lose decoration to free a char; returns false when nothing left to drop. */
        boolean shrink() {
            if (!numberStr.isEmpty()) { numberStr = ""; return true; }
            if (minorKind >= 0) { minorKind = -1; return true; }
            if (wrapEdge != null) { wrapEdge = null; return true; }
            return false;
        }
    }

    // Worst-case extra length per minor kind, indexed by (minorKind + 1): none, iPrefix, laugh, zPlural, wordLeet.
    private static final int[] MINOR_COST = {0, 2, 2, 1, 1};

    private static final String[] EDGES =
            {"xx", "x", "xX", "Xx", "xXx", "o", "oo", "oO", "Oo", "oOo", "0", "00", "I", "II"};

    /** Roll each decoration independently. Names stay mostly plain; numbers are intentionally uncommon. */
    private static Plan planDecorations(ThreadLocalRandom rng) {
        Plan p = new Plan();
        if (rng.nextInt(100) < 7) {                 // ~7% wrap, e.g. xX..Xx
            String pre = EDGES[rng.nextInt(EDGES.length)];
            if (rng.nextBoolean()) pre = pre.toUpperCase();
            p.wrapEdge = pre;
        }
        if (rng.nextInt(100) < 12) p.numberStr = rollNumberSuffix(rng); // ~12% number (single roll)
        p.leet = rng.nextInt(100) < 7;              // ~7% leet (length-neutral)
        if (rng.nextInt(100) < 8) p.minorKind = rng.nextInt(4); // ~8% minor flavor
        return p;
    }

    /**
     * Apply the plan in a fixed order: leet (neutral) -> wrap -> number -> minor flavor. The caller
     * guarantees {@code base.length() + plan.cost() <= 12}, so nothing is clipped; {@link #truncate}
     * remains only as a paranoid backstop.
     */
    private static String applyPlan(String base, Plan p, ThreadLocalRandom rng) {
        String name = base;
        if (p.leet) name = applyLeet(name, rng);
        if (p.wrapEdge != null) {
            String suf = new StringBuilder(p.wrapEdge).reverse().toString();
            name = p.wrapEdge + name + suf;
        }
        if (!p.numberStr.isEmpty()) name = name + p.numberStr;
        if (p.minorKind >= 0) name = applyMinor(name, p.minorKind, rng);
        return truncate(name);
    }

    /** Build a full name: decorations first, then words fit the leftover budget (never truncated). */
    static String assemble(List<String> themed, ThreadLocalRandom rng) {
        Plan p = planDecorations(rng);
        int budget = 12 - p.cost();
        while (budget < 3 && p.shrink()) budget = 12 - p.cost(); // keep room for at least a 3-char word
        if (budget < 3) budget = 3;
        return applyPlan(composeBase(themed, rng, budget), p, rng);
    }

    /**
     * Decorate a fixed root so the result fits within 12 chars — shares the SSOT plan/apply helpers with
     * {@link #assemble}. Retained for tests; production names go through {@link #assemble}.
     */
    static String stylize(String root, ThreadLocalRandom rng) {
        Plan p = planDecorations(rng);
        while (root.length() + p.cost() > 12 && p.shrink()) { /* drop decorations until the root fits */ }
        return applyPlan(root, p, rng);
    }

    /**
     * Weighted human number-suffix patterns (returns the literal digit string, len 1-4): repeating
     * (777), sequential (1234), lucky/meme (42/69/1337), round thousands (9000), years (1950-2030),
     * with plain random kept as a low-weight fallback.
     */
    static String rollNumberSuffix(ThreadLocalRandom rng) {
        int roll = rng.nextInt(100);
        if (roll < 25) {                                   // repeating digit, len 2-4
            char d = (char) ('0' + rng.nextInt(10));
            return String.valueOf(d).repeat(2 + rng.nextInt(3));
        }
        if (roll < 45) {                                   // sequential ascending, len 2-4
            int len = 2 + rng.nextInt(3);
            int start = 1 + rng.nextInt(10 - len);         // keep all digits <= 9
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) sb.append((char) ('0' + start + i));
            return sb.toString();
        }
        if (roll < 60) {                                   // lucky / meme
            String[] lucky = {"7", "42", "69", "88", "99", "123", "321", "420", "777", "1337", "9000"};
            return lucky[rng.nextInt(lucky.length)];
        }
        if (roll < 70) return (1 + rng.nextInt(9)) + "000"; // round thousand
        if (roll < 85) return String.valueOf(1950 + rng.nextInt(81)); // year 1950-2030
        int digits = 1 + rng.nextInt(3);                   // plain random, len 1-3
        return String.valueOf(rng.nextInt((int) Math.pow(10, digits)));
    }

    /**
     * Rare text-speak flavor (kind 0..3, ADD-only — never mangles letters): i/ii prefix, xD/XD laugh
     * suffix, trailing-z pluralization, number-as-word leet (4=for/2=to/U=you). No truncation here —
     * {@link #applyPlan} budgeted the room and backstops.
     */
    private static String applyMinor(String name, int kind, ThreadLocalRandom rng) {
        return switch (kind) {
            case 0 -> (rng.nextBoolean() ? "ii" : "i") + name;         // "iiCloud"
            case 1 -> name + (rng.nextBoolean() ? "xD" : "XD");        // "KevinxD"
            case 2 -> applyZPlural(name);                              // "Maplez"
            default -> applyWordLeet(name, rng);                       // "Fame4Fame" / "2Funded"
        };
    }

    /** Trailing-z pluralization, e.g. "Maplez" (replaces a trailing s rather than doubling). */
    private static String applyZPlural(String name) {
        if (name.endsWith("s") || name.endsWith("S")) {
            name = name.substring(0, name.length() - 1);
        }
        return name + "z";
    }

    /** Number-as-word leet (adds 1 char): infix "4" between two CamelCase words, or a "4"/"2" prefix, or a "U" suffix. */
    private static String applyWordLeet(String name, ThreadLocalRandom rng) {
        int split = -1;
        for (int i = 1; i < name.length(); i++) {
            if (java.lang.Character.isUpperCase(name.charAt(i)) && java.lang.Character.isLetter(name.charAt(i - 1))) {
                split = i;
                break;
            }
        }
        return switch (rng.nextInt(4)) {
            case 0 -> split > 0 ? name.substring(0, split) + "4" + name.substring(split) : "4" + name;
            case 1 -> "4" + name; // 4 = "for"
            case 2 -> "2" + name; // 2 = "to"
            default -> name + "U"; // U = "you"
        };
    }

    // ── Transforms ────────────────────────────────────────────────────────────

    /** e.g. "B4nd1T", "Fr057" — length-neutral leet substitution. */
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
