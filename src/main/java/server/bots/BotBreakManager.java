package server.bots;

import client.Character;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * In-session "take a break" behavior: a grinding bot periodically stops and idles for a while instead
 * of farming non-stop, so even while online it isn't a 24/7 grinder. Frequency and length come from
 * the bot's {@link BotPersonality} ({@code breakFreqPerHour}, {@code breakLenMeanMin}); diligent bots
 * (high {@code farmIdleRatio}) break less. Pure decision seam ({@link #startsBreak}) for unit testing;
 * the idle execution reuses the grind tick's held-anchor idle (see {@code BotManager.tickGrindMode}).
 */
final class BotBreakManager {
    private static final List<String> BREAK_MSGS = List.of(
            "taking a quick break", "brb, resting a sec", "gonna idle a bit");
    private static final List<String> RESUME_MSGS = List.of(
            "ok, back to it", "break over, grinding", "alright, back at it");

    private BotBreakManager() {}

    static boolean onBreak(BotEntry entry, long now) {
        return now < entry.breakUntilMs;
    }

    /**
     * Pure per-minute start decision. {@code rollUnit} is a uniform [0,1) sample; the per-minute break
     * probability is {@code breakFreqPerHour/60}, damped by diligence (a high farm/idle ratio breaks
     * less). Bounded so a degenerate profile can't break every minute.
     */
    static boolean startsBreak(double breakFreqPerHour, double farmIdleRatio, double rollUnit) {
        double perMinute = Math.max(0.0, breakFreqPerHour) / 60.0;
        perMinute *= (1.0 - 0.5 * clamp01(farmIdleRatio)); // diligent bots break less often
        return rollUnit < Math.min(0.5, perMinute);
    }

    /** Jittered break length in ms from the personality mean (0.5x..1.5x). */
    static long breakDurationMs(int breakLenMeanMin) {
        int meanMin = Math.max(1, breakLenMeanMin);
        double factor = 0.5 + ThreadLocalRandom.current().nextDouble(); // 0.5..1.5
        return Math.round(meanMin * 60_000L * factor);
    }

    /** A town-break lingers in town (sell/resupply + self-scroll), longer than an in-place break. Scales
     *  with laziness: a diligent bot rests 10-30 min, a maximally lazy one 20-60 min. */
    static long townBreakDurationMs(double laziness) {
        double lazy = clamp01(laziness);
        long lowMs = Math.round((10 + 10 * lazy) * 60_000.0);   // 10..20 min floor
        long spanMs = Math.round((20 + 40 * lazy) * 60_000.0);  // 20..60 min span
        return lowMs + (long) (ThreadLocalRandom.current().nextDouble() * spanMs);
    }

    /**
     * Roll (at most once a minute) whether a grinding bot should start a break; if so, arm
     * {@code breakUntilMs} and announce. No-op while already on a break or off cooldown.
     */
    static void maybeStartBreak(BotEntry entry, Character bot, long now) {
        if (onBreak(entry, now) || entry.restErrand || now < entry.nextBreakRollAtMs) {
            return;
        }
        // Stay-online QoL: a bot grouped with a real player doesn't wander off on a break.
        if (BotManager.partyHasRealPlayer(bot)) {
            return;
        }
        entry.nextBreakRollAtMs = now + 60_000L;
        BotPersonality p = entry.personality != null ? entry.personality : BotPersonality.defaults();
        if (!startsBreak(p.breakFreqPerHour(), p.farmIdleRatio(), ThreadLocalRandom.current().nextDouble())) {
            return;
        }
        // Self-scroll bots on autopilot take their break in a TOWN (sell trash + resupply + tinker with
        // gear there); already in a town -> rest right here. Everyone else keeps the in-place break.
        if (entry.selfScrollEnabled && BotAutopilotManager.isActive(entry) && bot.getMap() != null) {
            startTownBreak(entry, bot, now);
        } else {
            entry.breakUntilMs = now + breakDurationMs(p.breakLenMeanMin());
            entry.breakIdleAnchor = null;
        }
        if (ThreadLocalRandom.current().nextDouble() < p.chattiness()) {
            BotManager.getInstance().botSay(bot, BotManager.randomReply(BREAK_MSGS));
        }
    }

    /** Begin a town-break for one bot: rest in place if already in a town, else flag a rest errand so
     *  the autopilot routes it to a town (the in-town rest clock starts on arrival). Shared by the solo
     *  break roll and the leader-driven group break. */
    static void startTownBreak(BotEntry entry, Character bot, long now) {
        if (bot.getMap() != null && bot.getMap().isTown()) {
            BotPersonality p = entry.personality != null ? entry.personality : BotPersonality.defaults();
            entry.breakUntilMs = now + townBreakDurationMs(p.laziness());
            entry.breakIdleAnchor = null;
        } else {
            entry.restErrand = true;
        }
    }

    /**
     * Whether a cohort member should SKIP a group break and keep grinding to catch up: it sits in the
     * low-level cluster, separated from the pack by a gap of at least {@code trigger} levels. Members at
     * or below the first {@code >= trigger} jump from the bottom of the sorted cohort levels split off
     * (so a low pair like 10,11 below a 20,21 pack both split); no qualifying gap -> nobody splits.
     */
    static boolean catchUpSplit(int memberLevel, int[] sortedAscLevels, int trigger) {
        if (sortedAscLevels.length < 2 || trigger <= 0) {
            return false;
        }
        int lowClusterMax = sortedAscLevels[0];
        for (int i = 1; i < sortedAscLevels.length; i++) {
            if (sortedAscLevels[i] - sortedAscLevels[i - 1] >= trigger) {
                break; // first gap from the bottom: lowClusterMax is the level just below it
            }
            lowClusterMax = sortedAscLevels[i];
        }
        return lowClusterMax < sortedAscLevels[sortedAscLevels.length - 1] && memberLevel <= lowClusterMax;
    }

    /** Called when a break ends to clear state and optionally announce the resume. */
    static void endBreak(BotEntry entry, Character bot) {
        entry.breakUntilMs = 0L;
        entry.breakIdleAnchor = null;
        BotPersonality p = entry.personality != null ? entry.personality : BotPersonality.defaults();
        if (bot != null && ThreadLocalRandom.current().nextDouble() < p.chattiness()) {
            BotManager.getInstance().botSay(bot, BotManager.randomReply(RESUME_MSGS));
        }
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : Math.min(1.0, v);
    }
}
