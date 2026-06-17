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

    /** farm/idle, sociability, risk fixed; everything else neutral — for behavior-by-trait checks. */
    private static BotPersonality trait(double farmIdle, double sociability, double risk) {
        return new BotPersonality(1L, 1.0, new int[24], 60, farmIdle, 0.0, 5,
                sociability, 0.3, risk, BotPersonality.Archetype.REGULAR, 60);
    }

    @Test
    void wanderlustVariesByTrait() {
        BotPersonality roamer = trait(0.95, 0.05, 0.9);   // diligent, antisocial, bold
        BotPersonality homebody = trait(0.45, 0.95, 0.2); // lazy, social, timid
        assertTrue(roamer.wanderlustChance() > homebody.wanderlustChance(),
                "an active bold loner should roam more often than a lazy social homebody");

        // each trait pushes the right way, holding the others fixed
        assertTrue(trait(0.9, 0.3, 0.5).wanderlustChance() > trait(0.4, 0.3, 0.5).wanderlustChance(),
                "more active (higher farm ratio) -> roams more");
        assertTrue(trait(0.7, 0.1, 0.5).wanderlustChance() > trait(0.7, 0.9, 0.5).wanderlustChance(),
                "more social -> roams less");
        assertTrue(trait(0.7, 0.3, 0.9).wanderlustChance() > trait(0.7, 0.3, 0.1).wanderlustChance(),
                "bolder -> roams more");

        // it's a "once in a while" rate, not a coin flip
        assertTrue(roamer.wanderlustChance() < 0.5, "wanderlust stays occasional even for the keenest bot");
    }

    @Test
    void wanderlustDiscountStrongerForBolderBots() {
        // discount multiplies travel seconds; smaller = penalty lifted harder = ranges farther
        assertTrue(trait(0.7, 0.3, 0.9).wanderlustTravelDiscount() < trait(0.7, 0.3, 0.1).wanderlustTravelDiscount(),
                "a bolder bot lifts the travel penalty harder");
        assertTrue(trait(0.7, 0.3, 1.0).wanderlustTravelDiscount() > 0.0
                        && trait(0.7, 0.3, 0.0).wanderlustTravelDiscount() < 1.0,
                "discount stays in (0,1): always lifts, never inverts");
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
