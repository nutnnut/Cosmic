package server.bots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parser check for the "goto <map>" chat command matcher (pure regex, no WZ/DB). */
class BotChatGotoTest {

    @Test
    void parsesGotoTokens() {
        assertEquals("610020005", BotChatManager.matchGotoArgs("goto 610020005"));
        assertEquals("upper ascent", BotChatManager.matchGotoArgs("go to upper ascent"));
        assertEquals("610020005", BotChatManager.matchGotoArgs("go to map 610020005"));
        assertEquals("perion", BotChatManager.matchGotoArgs("goto perion!"));
        assertEquals("henesys", BotChatManager.matchGotoArgs("go to the henesys")); // optional "the" is stripped
    }

    @Test
    void rejectsNonGotoMessages() {
        assertNull(BotChatManager.matchGotoArgs("goto"));          // needs an argument
        assertNull(BotChatManager.matchGotoArgs("go together"));   // party-autopilot, handled earlier
        assertNull(BotChatManager.matchGotoArgs("go solo"));       // autopilot, handled earlier
        assertNull(BotChatManager.matchGotoArgs("hello there"));
        assertTrue(BotChatManager.isGotoCommand("goto henesys"));
        assertFalse(BotChatManager.isGotoCommand("go grind together"));
    }
}
