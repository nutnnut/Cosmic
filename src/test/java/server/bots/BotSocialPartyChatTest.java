package server.bots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WZ/DB-free tests for the player party-chat triggers (Flow 1 affirmative, Flow 3 request). */
class BotSocialPartyChatTest {

    @Test
    void partyRequestAliasesMatch() {
        for (String s : new String[]{"pt", "PT", "party", "party?", "party up", "lfp", "lf party",
                "lfg", "invite me", "inv me", "invite", "inv", "join", "can i join", "lemme join",
                "let me join", "group", "group up", "  party!  "}) {
            assertTrue(BotSocialManager.isPartyRequest(s), "should match party request: '" + s + "'");
        }
    }

    @Test
    void partyRequestRejectsUnrelated() {
        for (String s : new String[]{"hello", "where are you", "lets grind", "party time is over",
                "i hate parties", "", "ptw", "joining the guild"}) {
            assertFalse(BotSocialManager.isPartyRequest(s), "should NOT match: '" + s + "'");
        }
    }

    @Test
    void affirmativeAliasesMatch() {
        for (String s : new String[]{"yes", "ya", "yeah", "yep", "sure", "ok", "okay", "k", "y",
                "im in", "i'm in", "lets go", "let's go", "sounds good", "sg", "down", "OK!"}) {
            assertTrue(BotSocialManager.isAffirmative(s), "should match affirmative: '" + s + "'");
        }
    }

    @Test
    void affirmativeRejectsUnrelated() {
        for (String s : new String[]{"no", "nah", "maybe", "okay so what", "yesterday", "", "kk lol"}) {
            assertFalse(BotSocialManager.isAffirmative(s), "should NOT match: '" + s + "'");
        }
    }

    @Test
    void familiarityKeyRoundTrips() {
        long k = BotFamiliarityManager.key(123456, 789012);
        assertEquals(123456, (int) (k >> 32));
        assertEquals(789012, (int) k);
    }
}
