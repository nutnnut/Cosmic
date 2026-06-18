package server.bots;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts the pure appearance picker only ever produces ids drawn from the supplied legal pools,
 * with the hair = base + color contract honored. Fixtures mirror the verified WZ legal sets
 * (Etc.wz/MakeCharInfo.img Info/CharMale + Info/CharFemale) so a passing test means a bot's look
 * would survive MakeCharInfoValidator. We do not load WZ here (no test loads DataProviderFactory),
 * so the WZ values are pinned as the fixture and the SSOT wiring is exercised by compilation +
 * the production random() path.
 */
class BotAppearanceTest {
    // Verified from wz/Etc.wz/MakeCharInfo.img.xml -> Info/CharMale
    private static final Set<Integer> MALE_FACES = Set.of(20000, 20001, 20002);
    private static final Set<Integer> MALE_HAIR_BASES = Set.of(30030, 30020, 30000);
    // Verified Info/CharFemale
    private static final Set<Integer> FEMALE_FACES = Set.of(21000, 21001, 21002);
    private static final Set<Integer> FEMALE_HAIR_BASES = Set.of(31000, 31040, 31050);
    // Hair colors and skins are identical across genders in this data set
    private static final Set<Integer> HAIR_COLORS = Set.of(0, 7, 3, 2);
    private static final Set<Integer> SKINS = Set.of(0, 1, 2, 3);
    // Gender-specific starter gear (male top/bottom are the 1040/1060 range, female the 1041/1061
    // range); shoes/weapon are shared. Distinct pools per gender so a leak would fail the assertion.
    private static final Set<Integer> MALE_TOPS = Set.of(1040002, 1040006);
    private static final Set<Integer> MALE_BOTTOMS = Set.of(1060002, 1060006);
    private static final Set<Integer> FEMALE_TOPS = Set.of(1041002, 1041006);
    private static final Set<Integer> FEMALE_BOTTOMS = Set.of(1061002, 1061006);
    private static final Set<Integer> SHOES = Set.of(1072001, 1072005);
    private static final Set<Integer> WEAPONS = Set.of(1302000, 1322005);

    @Test
    void malePicksAreAlwaysLegal() {
        for (int i = 0; i < 2000; i++) {
            BotAppearance a = BotAppearance.pick(true, MALE_FACES, MALE_HAIR_BASES, HAIR_COLORS, SKINS,
                    MALE_TOPS, MALE_BOTTOMS, SHOES, WEAPONS);
            assertTrue(a.gender == 0, "male gender must be 0");
            assertTrue(MALE_FACES.contains(a.face), "illegal face " + a.face);
            assertTrue(SKINS.contains(a.skin), "illegal skin " + a.skin);
            assertLegalHair(a.hair, MALE_HAIR_BASES);
            assertTrue(MALE_TOPS.contains(a.top), "illegal male top " + a.top);
            assertTrue(MALE_BOTTOMS.contains(a.bottom), "illegal male bottom " + a.bottom);
            assertTrue(SHOES.contains(a.shoes), "illegal shoes " + a.shoes);
            assertTrue(WEAPONS.contains(a.weapon), "illegal weapon " + a.weapon);
        }
    }

    @Test
    void femalePicksAreAlwaysLegal() {
        for (int i = 0; i < 2000; i++) {
            BotAppearance a = BotAppearance.pick(false, FEMALE_FACES, FEMALE_HAIR_BASES, HAIR_COLORS, SKINS,
                    FEMALE_TOPS, FEMALE_BOTTOMS, SHOES, WEAPONS);
            assertTrue(a.gender == 1, "female gender must be 1");
            assertTrue(FEMALE_FACES.contains(a.face), "illegal face " + a.face);
            assertTrue(SKINS.contains(a.skin), "illegal skin " + a.skin);
            assertLegalHair(a.hair, FEMALE_HAIR_BASES);
            assertTrue(FEMALE_TOPS.contains(a.top), "illegal female top " + a.top);
            assertTrue(FEMALE_BOTTOMS.contains(a.bottom), "illegal female bottom " + a.bottom);
            assertTrue(SHOES.contains(a.shoes), "illegal shoes " + a.shoes);
            assertTrue(WEAPONS.contains(a.weapon), "illegal weapon " + a.weapon);
        }
    }

    /** Mirrors MakeCharInfo.verifyHairId / verifyHairColorId: base = hair - hair%10, color = hair%10. */
    private static void assertLegalHair(int hair, Set<Integer> legalBases) {
        int base = hair - (hair % 10);
        int color = hair % 10;
        assertTrue(legalBases.contains(base), "illegal hair base " + base + " (hair " + hair + ")");
        assertTrue(HAIR_COLORS.contains(color), "illegal hair color " + color + " (hair " + hair + ")");
    }
}
