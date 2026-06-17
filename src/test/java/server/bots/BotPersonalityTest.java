package server.bots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WZ/DB-free tests for the personality model (deterministic generation, serialization, decay). */
class BotPersonalityTest {

    @Test
    void randomIsDeterministicPerSeed() {
        BotPersonality a = BotPersonality.random(12345L);
        BotPersonality b = BotPersonality.random(12345L);
        assertEquals(a.serialize(), b.serialize(), "same seed must yield the same personality");
        BotPersonality c = BotPersonality.random(999L);
        assertNotEquals(a.serialize(), c.serialize(), "different seeds should differ");
    }

    @Test
    void serializeParseRoundTrips() {
        BotPersonality p = BotPersonality.random(77L);
        BotPersonality back = BotPersonality.parse(p.serialize());
        assertEquals(p.serialize(), back.serialize());
        assertEquals(p.career(), back.career());
        assertEquals(p.sessionLenMeanMin(), back.sessionLenMeanMin());
        assertEquals(p.careerLenDays(), back.careerLenDays());
    }

    @Test
    void parseToleratesMissingAndGarbledKeys() {
        BotPersonality d = BotPersonality.defaults();
        // unknown key ignored, missing keys default, bad value keeps default
        BotPersonality p = BotPersonality.parse("v=1;unknownKey=foo;sess=notanumber;soc=0.9");
        assertEquals(d.sessionLenMeanMin(), p.sessionLenMeanMin(), "garbled sess falls back to default");
        assertEquals(0.9, p.sociability(), 1e-9, "valid soc is applied");
        assertEquals(BotPersonality.defaults().serialize(), BotPersonality.parse(null).serialize());
        assertEquals(BotPersonality.defaults().serialize(), BotPersonality.parse("").serialize());
    }

    @Test
    void hourWeightsAreBoundedAndPeaked() {
        BotPersonality p = BotPersonality.random(42L);
        int total = 0;
        for (int h = 0; h < 24; h++) {
            assertTrue(p.hourWeight(h) >= 1, "every hour keeps a baseline weight");
            total += p.hourWeight(h);
        }
        assertTrue(total > 24, "a peak hour should lift the distribution above the flat baseline");
        assertEquals(1, p.hourWeight(-1), "out-of-range hour is safe");
    }

    @Test
    void engagementDecaysWithLevelExceptHardcore() {
        BotPersonality regular = new BotPersonality(1L, 1.0, new int[24], 60, 0.8, 1.0, 5,
                0.3, 0.3, 0.5, BotPersonality.Archetype.REGULAR, 60);
        assertTrue(regular.engagementMultiplier(1) > regular.engagementMultiplier(100),
                "a leveling regular bot should taper off");
        assertTrue(regular.engagementMultiplier(200) < 0.5);

        BotPersonality hardcore = new BotPersonality(1L, 1.0, new int[24], 60, 0.8, 1.0, 5,
                0.3, 0.3, 0.5, BotPersonality.Archetype.HARDCORE, BotPersonality.HARDCORE_FOREVER);
        assertTrue(hardcore.isHardcore());
        assertTrue(hardcore.engagementMultiplier(200) >= 0.6, "hardcore barely decays");
    }
}
