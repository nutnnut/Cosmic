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

    /** A town-break lingers 10-30 min in town (sell/resupply + self-scroll), longer than an in-place break. */
    static long townBreakDurationMs() {
        return 10 * 60_000L + (long) (ThreadLocalRandom.current().nextDouble() * 20 * 60_000L);
    }

    /**
     * Roll (at most once a minute) whether a grinding bot should start a break; if so, arm
     * {@code breakUntilMs} and announce. No-op while already on a break or off cooldown.
     */
    static void maybeStartBreak(BotEntry entry, Character bot, long now) {
        if (onBreak(entry, now) || entry.restErrand || now < entry.nextBreakRollAtMs) {
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
            if (bot.getMap().isTown()) {
                entry.breakUntilMs = now + townBreakDurationMs();
                entry.breakIdleAnchor = null;
            } else {
                entry.restErrand = true; // autopilot routes to a town; the rest clock starts on arrival
            }
        } else {
            entry.breakUntilMs = now + breakDurationMs(p.breakLenMeanMin());
            entry.breakIdleAnchor = null;
        }
        if (ThreadLocalRandom.current().nextDouble() < p.chattiness()) {
            BotManager.getInstance().botSay(bot, BotManager.randomReply(BREAK_MSGS));
        }
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
