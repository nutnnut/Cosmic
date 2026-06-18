package server.bots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WZ/DB-free tests for the bot social/party decision math (P4). */
class BotSocialMathTest {

    private static BotPersonality p(double soc, double chat, double risk) {
        return new BotPersonality(1L, 1.0, new int[24], 60, 0.8, 0.0, 5, soc, chat, risk,
                BotPersonality.Archetype.REGULAR, 60);
    }

    @Test
    void antisocialNeverInitiatesAndPartiedNever() {
        assertFalse(BotSocialMath.shouldInitiate(p(0.0, 1.0, 0.5), false, 0.0001), "soc 0 -> never offers");
        assertFalse(BotSocialMath.shouldInitiate(p(1.0, 1.0, 0.5), true, 0.0), "already partied -> never offers");
    }

    @Test
    void socialAndTalkativeInitiateMore() {
        assertTrue(p(1.0, 1.0, 0.5).partyInitiateChance() > p(1.0, 0.05, 0.5).partyInitiateChance(),
                "a chatty social bot offers more than a near-silent one");
        assertTrue(BotSocialMath.shouldInitiate(p(1.0, 1.0, 0.5), false, 0.01), "keen bot fires at a low roll");
    }

    @Test
    void acceptsInRangeBySociability() {
        assertEquals(BotSocialMath.Response.ACCEPT,
                BotSocialMath.respondToOffer(p(1.0, 1.0, 0.5), 2, 5, 0.5, 0.9, 0.5));
        assertNotEquals(BotSocialMath.Response.ACCEPT,
                BotSocialMath.respondToOffer(p(0.0, 1.0, 0.5), 2, 5, 0.5, 0.9, 0.5), "soc 0 never accepts");
    }

    @Test
    void declinesOrIgnoresOutOfExpRange() {
        // gap 9 > window 5, no mistake: a chatty bot speaks the decline, a silent one just ignores.
        assertEquals(BotSocialMath.Response.DECLINE,
                BotSocialMath.respondToOffer(p(1.0, 1.0, 0.5), 9, 5, 0.0, 0.99, 0.0));
        assertEquals(BotSocialMath.Response.IGNORE,
                BotSocialMath.respondToOffer(p(1.0, 0.0, 0.5), 9, 5, 0.0, 0.99, 0.5));
    }

    @Test
    void mistakeRollAcceptsAcrossTooBigGap() {
        // gap 9 > window 5, but the mistake roll slips through and the bot is sociable -> accept anyway.
        assertEquals(BotSocialMath.Response.ACCEPT,
                BotSocialMath.respondToOffer(p(1.0, 1.0, 0.9), 9, 5, 0.0, 0.0001, 0.5));
    }
}
