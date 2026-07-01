package server.bots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WZ-free tests for the party level-gap idle-leech hysteresis decision
 * ({@link BotAutopilotManager#decideIdleLeech}). Defaults: trigger 4, release 2.
 */
class BotPartyLeechTest {
    private static final int TRIGGER = 4;
    private static final int RELEASE = 2;

    private static boolean decide(boolean cur, int my, int min) {
        return BotAutopilotManager.decideIdleLeech(cur, my, min, TRIGGER, RELEASE);
    }

    @Test
    void lowestMemberNeverLeeches() {
        assertFalse(decide(false, 20, 20)); // gap 0
        assertFalse(decide(false, 21, 20)); // gap 1
    }

    @Test
    void startsAtTrigger() {
        assertFalse(decide(false, 23, 20)); // gap 3 < trigger
        assertTrue(decide(false, 24, 20));  // gap 4 == trigger -> start
        assertTrue(decide(false, 30, 20));  // well above
    }

    @Test
    void staysUntilReleaseThenResumes() {
        // Already leeching: keep idling while the gap is still above release.
        assertTrue(decide(true, 23, 20));   // gap 3 > release -> stay
        assertFalse(decide(true, 22, 20));  // gap 2 == release -> resume
    }

    @Test
    void hysteresisBandHoldsState() {
        // In the [release, trigger) band the decision is sticky to the current state:
        // not leeching stays off, leeching stays on.
        assertFalse(decide(false, 23, 20)); // gap 3, was off -> stays off
        assertTrue(decide(true, 23, 20));   // gap 3, was on  -> stays on
    }

    @Test
    void resumesAtOrBelowRelease() {
        assertFalse(decide(true, 22, 20));  // gap 2 == release -> resume
        assertFalse(decide(true, 21, 20));  // gap 1 -> resume
    }
}
