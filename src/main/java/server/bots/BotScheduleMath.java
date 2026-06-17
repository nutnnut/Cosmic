package server.bots;

/**
 * Pure decision math for the living-server population scheduler ({@link BotScheduler}) — no DB, no
 * game state, fully unit-testable. The scheduler does the IO (spawn/logout/generate); this decides
 * <em>how many</em> bots should be online now, <em>which</em> offline bots most want to be online,
 * whether a bot's daily/session/career clock says it should be playing, and when a career ends.
 */
final class BotScheduleMath {

    private BotScheduleMath() {}

    /** Target online count for a server-local hour, with bounded +/- noise. {@code noiseRoll} in [0,1). */
    static int targetForHour(int[] curve, int hour, int noise, double noiseRoll) {
        int base = (curve != null && hour >= 0 && hour < curve.length) ? curve[hour] : 0;
        int jitter = noise <= 0 ? 0 : (int) Math.round((noiseRoll * 2.0 - 1.0) * noise);
        return Math.max(0, base + jitter);
    }

    /**
     * Whether a bot plays at all on a given day — deterministic per (bot, day) from its seed, so a bot
     * with daysActiveRatio 0.6 is "on" ~60% of days but consistently for that calendar day (no flicker
     * within a day). This is what makes some bots show up sporadically rather than every day.
     */
    static boolean isActiveToday(long seed, long epochDay, double daysActiveRatio) {
        double u = unitHash(seed, epochDay);
        return u < clamp01(daysActiveRatio);
    }

    /**
     * How strongly a bot wants to be online at this hour: its hour preference × engagement (which
     * decays with level) × how-often-it-plays. The scheduler ranks offline candidates by this and
     * brings the highest first, so WHO is online reflects personalities while the curve sets the count.
     * Returns 0 when the bot isn't active today (so it won't be chosen).
     */
    static double onlineDesire(BotPersonality p, int hour, int level, long epochDay) {
        if (p == null || !isActiveToday(p.seed(), epochDay, p.daysActiveRatio())) {
            return 0.0;
        }
        return p.hourWeight(hour) * p.engagementMultiplier(level) * Math.max(0.0, p.daysActiveRatio());
    }

    /** A bot's career is over (it "leaves") once it has existed longer than its careerLenDays — never for hardcore. */
    static boolean careerEnded(long ageDays, int careerLenDays, boolean hardcore) {
        return !hardcore && careerLenDays != BotPersonality.HARDCORE_FOREVER && ageDays >= careerLenDays;
    }

    /** A session has run its length and the bot should log off for the day. */
    static boolean sessionElapsed(long onlineSinceMs, long sessionLenMs, long now) {
        return onlineSinceMs > 0 && sessionLenMs > 0 && (now - onlineSinceMs) >= sessionLenMs;
    }

    /**
     * How many fresh bots to auto-generate this sweep. Only when, after waking everyone eligible, the
     * live count is still below target — and only if autogen is on and the non-retired pool has room.
     * Throttled to one per sweep so newcomers trickle in (gradual inflow, and the DB create stays off
     * the critical path) rather than a burst filling the world the instant the curve rises.
     */
    static int autogenCount(boolean autogenOn, int target, int live, int eligibleOffline,
                            int poolSize, int poolMax) {
        if (!autogenOn || poolSize >= poolMax) {
            return 0;
        }
        int deficit = target - live - eligibleOffline;
        return deficit > 0 ? 1 : 0;
    }

    /** Hardcore cap: a fresh bot may keep a HARDCORE roll only while below the veteran cap — otherwise
     *  it's re-rolled to a finite career, so the never-retiring set stays small and turnover continues. */
    static boolean hardcoreAllowed(int currentHardcore, int cap) {
        return currentHardcore < cap;
    }

    /** Deterministic [0,1) hash of two longs (splitmix64-style finalizer); stable across runs/JVMs. */
    static double unitHash(long a, long b) {
        long z = a * 0x9E3779B97F4A7C15L + (b + 0x7F4A7C159E3779B9L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return (z >>> 11) * 0x1.0p-53;
    }

    static double clamp01(double v) {
        return v < 0.0 ? 0.0 : Math.min(1.0, v);
    }
}
