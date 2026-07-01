package server.bots;

/**
 * Anti-freeze watchdog shared by every retreat reason (touch-danger self-preservation and
 * ranged-spacing). A retreat exists to open distance / reach safety; if it runs without the
 * triggering condition ever clearing, the bot is failing to make progress (mob chases at the same
 * speed, blocked nav, pinned on a rope) and would loop forever with the attack gate shut — frozen.
 *
 * <p>This is the SINGLE place that breaks that loop: while the condition stays {@code active} past
 * {@code maxStreakMs}, give up and FIGHT in place for {@code fightWindowMs}. The streak resets the
 * instant the condition clears, so healthy kiting (back off, mob out of band, re-engage) never trips
 * it. One instance per reason on {@link BotEntry} keeps each reason's streak/window independent
 * while sharing this logic instead of duplicating it.
 */
final class RetreatGiveUp {
    private long streakStartMs = 0L;
    private long fightUntilMs = 0L;

    /**
     * @param active true when the retreat's triggering condition holds this tick (still dangerous /
     *               still crowded). "Progress" = this going false, which resets the streak.
     * @return true => STOP retreating and fight this tick: either inside a committed fight window, or
     *         the streak just exceeded the cap. false => retreat may proceed.
     */
    boolean forcedFight(boolean active, long now, int maxStreakMs, int fightWindowMs) {
        if (now < fightUntilMs) {
            return true; // committed fight window — never freeze
        }
        if (!active) {
            streakStartMs = 0L; // condition cleared (opened distance / reached safety)
            return false;
        }
        if (streakStartMs == 0L) {
            streakStartMs = now;
            return false;
        }
        if (now - streakStartMs > maxStreakMs) {
            fightUntilMs = now + fightWindowMs;
            streakStartMs = 0L;
            return true;
        }
        return false;
    }

    /** Whether a give-up fight window is currently open (telemetry). */
    boolean inFightWindow(long now) {
        return now < fightUntilMs;
    }

    /** ms left in the current fight window, or 0 if none (telemetry). */
    long fightWindowLeftMs(long now) {
        return Math.max(0L, fightUntilMs - now);
    }

    /** ms the current unbroken streak has run, or 0 if no streak (telemetry). */
    long streakAgeMs(long now) {
        return streakStartMs == 0L ? 0L : Math.max(0L, now - streakStartMs);
    }
}
