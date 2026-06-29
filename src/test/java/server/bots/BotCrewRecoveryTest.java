package server.bots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the "who fires inert-crew recovery" rule ({@link BotManager#shouldTriggerCrewRecovery}). The
 * regression it guards: a per-bot SOLO recovery silently dissolves a party cohort, and a too-strict
 * leader-only gate strands a non-leader that goes inert while the leader is still active.
 */
class BotCrewRecoveryTest {
    @Test
    void leaderAlwaysFires() {
        assertTrue(BotManager.shouldTriggerCrewRecovery(true, true));
        assertTrue(BotManager.shouldTriggerCrewRecovery(true, false)); // all-inert crew: leader fires once
    }

    @Test
    void nonLeaderFiresOnlyWhenLeaderActive() {
        assertTrue(BotManager.shouldTriggerCrewRecovery(false, true));   // orphaned: leader won't self-recover
        assertFalse(BotManager.shouldTriggerCrewRecovery(false, false)); // defer to the inert leader's tick
    }
}
