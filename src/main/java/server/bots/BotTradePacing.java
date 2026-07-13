package server.bots;

/**
 * SSOT pacing for humanlike beats inside a trade window. Every bot trade flow (shout walk-ups,
 * owner/commander manual trades, gear-offer transfers) draws its step delay here so none of them
 * acts faster than a person could: noticing the invite popup and clicking accept, finding and
 * dragging an item in, typing a meso amount — each is one STEP beat; the final confirm gets a
 * slightly longer re-read-the-window beat.
 */
final class BotTradePacing {
    /** One window step: see it, move the mouse, do it. */
    static final int STEP_MIN_MS = 900;
    static final int STEP_MAX_MS = 2_200;
    /** Final confirm: a person re-reads the window before hitting trade. */
    static final int CONFIRM_MIN_MS = 1_500;
    static final int CONFIRM_MAX_MS = 3_000;

    private BotTradePacing() {}

    static long stepDelayMs() {
        return BotManager.randMs(STEP_MIN_MS, STEP_MAX_MS);
    }

    static long confirmDelayMs() {
        return BotManager.randMs(CONFIRM_MIN_MS, CONFIRM_MAX_MS);
    }
}
