package server.bots;

import java.util.Random;

/**
 * Per-bot personality / behavior profile — the persistent "who is this bot" that shapes WHEN it plays
 * (schedule), HOW it plays (farm vs. idle, breaks, risk), how SOCIAL it is, and its CAREER arc (how
 * long before it loses interest and retires). Persisted as the {@code bot_config} blob
 * ({@link BotConfigService}) — that table was scaffolded for exactly these knobs.
 *
 * <p>Deterministic from a seed (the bot's character id), so a bot's character is stable across restarts
 * even before the blob is first saved. Consumed by: the population scheduler (schedule + career), the
 * in-session break logic (farm/idle), party formation (sociability), chat (chattiness), and combat
 * (riskTolerance). All values are bounded; {@link #defaults()} gives neutral values for non-managed bots.
 *
 * <p>Serialized as a flat, forward-compatible {@code key=value} line (no JSON lib in this project):
 * unknown keys are ignored and missing keys fall back to {@link #defaults()}.
 */
public record BotPersonality(
        long seed,
        double daysActiveRatio,     // 0..1: chance the bot plays at all on a given day
        int[] hourWeights,          // 24 relative weights -> preferred play hours (server local time)
        int sessionLenMeanMin,      // mean online session length, minutes
        double farmIdleRatio,       // 0..1: fraction of an online session spent actively farming (rest = idle/breaks)
        double breakFreqPerHour,    // expected in-session town breaks per hour
        int breakLenMeanMin,        // mean break length, minutes
        double sociability,         // 0..1: tendency to party up
        double chattiness,          // 0..1: greeting / chat frequency
        double riskTolerance,       // 0..1: higher = willing to fight more dangerous mobs
        Archetype career,
        int careerLenDays,          // career length before retiring; Integer.MAX_VALUE = hardcore (never)
        int plannedFirstJobId,      // chosen-at-creation 1st job (0 = none -> autopilot picks at lv10)
        int plannedSecondJobId      // chosen-at-creation 2nd job (0 = none -> autopilot picks at lv30)
) {
    /** Career arc: how long a bot stays interested before it "leaves". HARDCORE never retires (capped count). */
    public enum Archetype { TOURIST, CASUAL, REGULAR, HARDCORE }

    public static final int HARDCORE_FOREVER = Integer.MAX_VALUE;

    /**
     * Resolve a bot's personality at spawn: parse the saved {@code bot_config} blob if present; else,
     * for a managed (server-generated) bot, roll a deterministic one (seeded by char id) and persist it;
     * else neutral {@link #defaults()}. One-off (spawn-time) cost — not on the tick path.
     */
    public static BotPersonality loadOrCreate(int botCharId) {
        String blob = BotConfigService.getInstance().load(botCharId);
        if (blob != null && !blob.isBlank()) {
            return parse(blob);
        }
        if (ManagedBotService.getInstance().isManaged(botCharId)) {
            BotPersonality p = random(botCharId);
            try {
                BotConfigService.getInstance().save(botCharId, p.serialize());
            } catch (RuntimeException ignored) {
                // persistence best-effort; the in-memory profile is still used this session
            }
            return p;
        }
        return defaults();
    }

    /** Neutral profile for non-managed / unknown bots — plays steadily, average everything, never retires. */
    public static BotPersonality defaults() {
        int[] flat = new int[24];
        java.util.Arrays.fill(flat, 1);
        return new BotPersonality(0L, 1.0, flat, 60, 0.8, 0.0, 5,
                0.3, 0.3, 0.5, Archetype.REGULAR, HARDCORE_FOREVER, 0, 0);
    }

    /** Deterministic random personality seeded by the bot's character id (stable across restarts). */
    public static BotPersonality random(long seed) {
        Random r = new Random(seed);
        Archetype career = rollArchetype(r);
        int careerLen = switch (career) {
            case TOURIST -> 1 + r.nextInt(5);     // a few days
            case CASUAL -> 7 + r.nextInt(21);     // 1-4 weeks
            case REGULAR -> 30 + r.nextInt(120);  // 1-5 months
            case HARDCORE -> HARDCORE_FOREVER;    // stays
        };
        return new BotPersonality(
                seed,
                0.45 + r.nextDouble() * 0.5,          // plays 45-95% of days
                rollHourWeights(r),
                25 + r.nextInt(95),                   // 25-120 min sessions
                0.45 + r.nextDouble() * 0.5,          // farms 45-95% of the session
                0.3 + r.nextDouble() * 1.7,           // 0.3-2 breaks/hour
                3 + r.nextInt(12),                    // 3-15 min breaks
                r.nextDouble(),                       // sociability
                r.nextDouble(),                       // chattiness
                0.2 + r.nextDouble() * 0.7,           // risk 0.2-0.9
                career,
                careerLen,
                0, 0);                                // planned jobs set by the managed-bot inflow, not the seed
    }

    /** Copy with the creation-time planned 1st/2nd job (null -> 0 = unplanned). The generated name is
     *  flavored to the 1st job, so storing it here keeps name, stored plan, and eventual class aligned. */
    public BotPersonality withPlannedJobs(client.Job first, client.Job second) {
        return new BotPersonality(seed, daysActiveRatio, hourWeights, sessionLenMeanMin, farmIdleRatio,
                breakFreqPerHour, breakLenMeanMin, sociability, chattiness, riskTolerance, career, careerLenDays,
                first == null ? 0 : first.getId(), second == null ? 0 : second.getId());
    }

    /** The planned 1st job, or null if unplanned. */
    public client.Job plannedFirstJob() {
        return plannedFirstJobId == 0 ? null : client.Job.getById(plannedFirstJobId);
    }

    /** The planned 2nd job, or null if unplanned. */
    public client.Job plannedSecondJob() {
        return plannedSecondJobId == 0 ? null : client.Job.getById(plannedSecondJobId);
    }

    private static Archetype rollArchetype(Random r) {
        // Most bots are casual/regular; tourists churn through; a few hardcore persist.
        double x = r.nextDouble();
        if (x < 0.30) return Archetype.TOURIST;
        if (x < 0.65) return Archetype.CASUAL;
        if (x < 0.92) return Archetype.REGULAR;
        return Archetype.HARDCORE;
    }

    /** A single Gaussian-ish peak (the bot's favorite hour) plus a low baseline, so every bot has a
     *  distinct daily rhythm (morning person / night owl / after-work) while never being fully offline. */
    private static int[] rollHourWeights(Random r) {
        int peak = r.nextInt(24);
        int spread = 2 + r.nextInt(4);
        int[] w = new int[24];
        for (int h = 0; h < 24; h++) {
            int dist = Math.min(Math.abs(h - peak), 24 - Math.abs(h - peak)); // wrap-around distance
            w[h] = 1 + (int) Math.round(10.0 * Math.exp(-(double) (dist * dist) / (2.0 * spread * spread)));
        }
        return w;
    }

    /** Relative weight that this bot wants to be online at the given server-local hour (0-23). */
    public int hourWeight(int hour) {
        if (hourWeights == null || hourWeights.length != 24 || hour < 0 || hour > 23) {
            return 1;
        }
        return Math.max(0, hourWeights[hour]);
    }

    public boolean isHardcore() {
        return career == Archetype.HARDCORE || careerLenDays >= HARDCORE_FOREVER;
    }

    /** ~1% of managed bots are lifelong Beginners that never job-advance (flavor: the eternal beginner).
     *  Derived deterministically from the seed (salted so it doesn't correlate with the trait rolls), so
     *  it's stable across restarts with no extra stored field. {@code seed == 0} (neutral/non-managed
     *  defaults) is never locked. */
    public boolean permanentBeginner() {
        return seed != 0 && new Random(seed ^ 0x9E3779B97F4A7C15L).nextDouble() < 0.01;
    }

    /**
     * Engagement decay: as a bot levels up it loses interest and logs in less, so not every bot grinds
     * to max. Returns a 0..1 multiplier on the daily-online chance. Hardcore bots barely decay.
     */
    public double engagementMultiplier(int level) {
        double floor = isHardcore() ? 0.6 : 0.15;
        // Linear taper from full engagement at level 1 to `floor` near level 200.
        double t = Math.max(0.0, Math.min(1.0, (level - 1) / 199.0));
        return 1.0 - (1.0 - floor) * t;
    }

    /** 0..1 "laziness": the inverse of farm/idle diligence, normalized over the rolled trait range
     *  (farm 0.95 -> 0 diligent, farm 0.45 -> 1 fully lazy). Lazier bots take longer town breaks. */
    public double laziness() {
        return Math.max(0.0, Math.min(1.0, (0.95 - farmIdleRatio) / 0.5));
    }

    /**
     * Per-login chance this becomes a CHILL session: instead of grinding, the bot heads to town and
     * lingers there (sell/resupply/self-scroll/gacha/socialize) for a half-length session. Tourists and
     * casuals chill most, hardcores almost never; lazier (low farm/idle) bots chill more within their tier.
     */
    public double chillSessionChance() {
        double base = switch (career) {
            case TOURIST -> 0.50;
            case CASUAL -> 0.30;
            case REGULAR -> 0.15;
            case HARDCORE -> 0.05;
        };
        return base * (0.5 + laziness()); // 0.5..1.5: lazier bots chill more often
    }

    /** Base per-decision wanderlust probability before trait scaling. */
    private static final double WANDERLUST_BASE = 0.12;

    /**
     * Per grind-decision chance the bot drops its local-grind travel bias and roams for a better spot
     * elsewhere. Trait-driven so behavior varies per bot: a diligent, bold loner roams often; a lazy,
     * homebody socializer (idles/parties in town) stays local. Hazard/level avoidance is enforced
     * separately, so roaming never sends a bot somewhere dangerous.
     */
    public double wanderlustChance() {
        double active = farmIdleRatio;             // lazy/idle bots (low farm) roam less
        double adventure = 0.5 + riskTolerance;    // 0.5..1.5: bold bots roam more
        double homebody = 1.0 - 0.5 * sociability; // social bots idle/party in town instead of roaming
        return WANDERLUST_BASE * active * adventure * homebody;
    }

    /**
     * Travel-time discount applied during a wanderlust decision (multiplies effective travel seconds;
     * 1.0 = no lift). Bolder bots lift the penalty harder and so range farther.
     */
    public double wanderlustTravelDiscount() {
        return 0.25 - 0.15 * riskTolerance; // risk 0 -> 0.25 (modest), risk 1 -> 0.10 (strong lift)
    }

    // ---- party sociability (P4: ad-hoc party-up) ----
    private static final double PARTY_INITIATE_BASE = 0.12;
    private static final double PARTY_MISTAKE_BASE = 0.10;

    /** Per social-tick chance the bot proactively offers/asks to party when solo and co-located with a
     *  candidate. Social AND talkative bots initiate most; a near-silent bot rarely speaks up, an
     *  antisocial one never wants company. */
    public double partyInitiateChance() {
        return PARTY_INITIATE_BASE * sociability * (0.3 + 0.7 * chattiness);
    }

    /** Willingness to accept a party offer/invite (the level-gap exp-range check is applied separately). */
    public double acceptChance() {
        return sociability;
    }

    /** Humanlike chance to offer/ask DESPITE an out-of-exp-range level gap (bolder bots slip up more). */
    public double partyMistakeChance() {
        return PARTY_MISTAKE_BASE * (0.5 + riskTolerance);
    }

    // ---- gachapon appetite (personality-driven cadence + budget) ----
    // Derived from the seed with a distinct salt so a bot's gambling appetite is stable across restarts
    // without a stored field and doesn't correlate with its other traits (same trick as permanentBeginner).
    private static final long GACHA_SALT = 0xD1B54A32D192ED03L;
    private static final long GACHA_INTERVAL_LOW_MS  = 72L * 3600_000L; // low appetite: ~once every 3 days
    private static final long GACHA_INTERVAL_HIGH_MS =  8L * 3600_000L; // high appetite: ~a few times a day
    private static final double GACHA_SPEND_FRAC_LOW  = 0.05;
    private static final double GACHA_SPEND_FRAC_HIGH = 0.40;

    /** Stable 0..1 gacha appetite. Neutral 0.3 for the seed-0 default profile (non-managed bots). */
    private double gachaAppetite() {
        return seed == 0 ? 0.3 : new Random(seed ^ GACHA_SALT).nextDouble();
    }

    /** Mean gap between gachapon trips: high appetite ~8h (a few times a day), low ~72h (once every few
     *  days). Jitter at the call site for per-session variance. */
    public long gachaIntervalMs() {
        return Math.round(GACHA_INTERVAL_LOW_MS + (GACHA_INTERVAL_HIGH_MS - GACHA_INTERVAL_LOW_MS) * gachaAppetite());
    }

    /** Max fraction of spare NX (above the reserve) the bot will blow on one gachapon trip. */
    public double gachaSpendFrac() {
        return GACHA_SPEND_FRAC_LOW + (GACHA_SPEND_FRAC_HIGH - GACHA_SPEND_FRAC_LOW) * gachaAppetite();
    }

    // ---- haggling temperament (living-economy S4) ----
    // Seed-derived with a distinct salt (same trick as gachaAppetite): stable per bot across restarts
    // with no stored field, uncorrelated with the other trait rolls. Repeated encounters with the
    // same bot should feel like the same negotiator.
    private static final long HAGGLE_SALT = 0x4841474C454631L; // "HAGLEF1"

    /** Stable 0..1 haggling stubbornness. Neutral 0.5 for the seed-0 default profile. */
    private double haggleTemper() {
        return seed == 0 ? 0.5 : new Random(seed ^ HAGGLE_SALT).nextDouble();
    }

    /** Concession firmness for counter-offers (BotMarketMath.counterPrice/counterBid): 1 = barely
     *  moves per round, 0 = meets the other side. Bounded away from the extremes so every bot both
     *  concedes something and keeps something back. */
    public double haggleFirmness() {
        return 0.25 + 0.5 * haggleTemper();
    }

    /** How many counter-offers the bot will make before it accepts-or-walks (patience, 2..4). */
    public int haggleRounds() {
        return 2 + (int) Math.round(haggleTemper() * 2.0);
    }

    /** Accept-slack fraction: the bot takes a deal within this margin of its bound instead of
     *  squeezing out one more round (BotMarketMath.acceptable/acceptableAsk). Firmer bots demand
     *  a little more headroom before settling. */
    public double haggleSlack() {
        return 0.02 + 0.04 * haggleTemper();
    }

    // ---- serialization (flat key=value; tolerant on read) ----

    public String serialize() {
        StringBuilder w = new StringBuilder();
        for (int i = 0; i < hourWeights.length; i++) {
            if (i > 0) w.append(',');
            w.append(hourWeights[i]);
        }
        return "v=1"
                + ";seed=" + seed
                + ";days=" + fmt(daysActiveRatio)
                + ";hours=" + w
                + ";sess=" + sessionLenMeanMin
                + ";farm=" + fmt(farmIdleRatio)
                + ";bfreq=" + fmt(breakFreqPerHour)
                + ";blen=" + breakLenMeanMin
                + ";soc=" + fmt(sociability)
                + ";chat=" + fmt(chattiness)
                + ";risk=" + fmt(riskTolerance)
                + ";career=" + career.name()
                + ";clen=" + careerLenDays
                + ";pj1=" + plannedFirstJobId
                + ";pj2=" + plannedSecondJobId;
    }

    /** Parse a saved blob; any missing/garbled field falls back to {@link #defaults()} (forward-compatible). */
    public static BotPersonality parse(String blob) {
        BotPersonality d = defaults();
        if (blob == null || blob.isBlank()) {
            return d;
        }
        long seed = d.seed;
        double days = d.daysActiveRatio, farm = d.farmIdleRatio, bfreq = d.breakFreqPerHour;
        double soc = d.sociability, chat = d.chattiness, risk = d.riskTolerance;
        int sess = d.sessionLenMeanMin, blen = d.breakLenMeanMin, clen = d.careerLenDays;
        int pj1 = d.plannedFirstJobId, pj2 = d.plannedSecondJobId;
        int[] hours = d.hourWeights;
        Archetype career = d.career;
        for (String part : blob.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = part.substring(0, eq).trim();
            String v = part.substring(eq + 1).trim();
            try {
                switch (k) {
                    case "seed" -> seed = Long.parseLong(v);
                    case "days" -> days = Double.parseDouble(v);
                    case "hours" -> hours = parseHours(v, d.hourWeights);
                    case "sess" -> sess = Integer.parseInt(v);
                    case "farm" -> farm = Double.parseDouble(v);
                    case "bfreq" -> bfreq = Double.parseDouble(v);
                    case "blen" -> blen = Integer.parseInt(v);
                    case "soc" -> soc = Double.parseDouble(v);
                    case "chat" -> chat = Double.parseDouble(v);
                    case "risk" -> risk = Double.parseDouble(v);
                    case "career" -> career = Archetype.valueOf(v);
                    case "clen" -> clen = Integer.parseInt(v);
                    case "pj1" -> pj1 = Integer.parseInt(v);
                    case "pj2" -> pj2 = Integer.parseInt(v);
                    default -> { /* unknown key: ignore (forward-compat) */ }
                }
            } catch (RuntimeException ignored) {
                // keep the default for this field
            }
        }
        return new BotPersonality(seed, days, hours, sess, farm, bfreq, blen, soc, chat, risk, career, clen, pj1, pj2);
    }

    private static int[] parseHours(String v, int[] fallback) {
        String[] parts = v.split(",");
        if (parts.length != 24) {
            return fallback;
        }
        int[] out = new int[24];
        for (int i = 0; i < 24; i++) {
            out[i] = Math.max(0, Integer.parseInt(parts[i].trim()));
        }
        return out;
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.3f", d);
    }
}
