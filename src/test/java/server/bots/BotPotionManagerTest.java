package server.bots;

import client.Character;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotPotionManagerTest {

    /**
     * A self-owned (@botme) bot is its own owner; it must not try to beg a pot share from itself.
     * requestPotShare early-returns false before any donor/sibling lookup.
     */
    @Test
    void selfOwnedBotDoesNotRequestPotShareFromItself() {
        Character bot = mock(Character.class);
        when(bot.getTrade()).thenReturn(null);
        BotEntry entry = new BotEntry(bot, bot, null); // owner == bot

        assertFalse(BotPotionManager.requestPotShare(entry, bot, true, false));
        assertFalse(BotPotionManager.requestPotShare(entry, bot, false, false));
    }
}
