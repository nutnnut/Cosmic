package server.bots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WZ/DB-free tests for the managed-bot scheduling gate ({@link ManagedBotService.ManagedBot#schedulable()}).
 * A bot is schedulable only while enabled AND not retired — the safety predicate the population
 * scheduler honors so it never auto-spawns a paused or retired (or non-managed) character.
 */
class ManagedBotServiceTest {

    private static ManagedBotService.ManagedBot bot(boolean enabled, boolean retired) {
        return new ManagedBotService.ManagedBot(1, null, enabled, retired);
    }

    @Test
    void enabledActiveIsSchedulable() {
        assertTrue(bot(true, false).schedulable());
    }

    @Test
    void disabledIsNotSchedulable() {
        assertFalse(bot(false, false).schedulable());
    }

    @Test
    void retiredIsNotSchedulable() {
        assertFalse(bot(true, true).schedulable());
        assertFalse(bot(false, true).schedulable());
    }

    @Test
    void groupMembershipDoesNotAffectSchedulability() {
        assertTrue(new ManagedBotService.ManagedBot(1, 7, true, false).schedulable());
    }
}
