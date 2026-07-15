package server.bots;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure logic for the Temple driver: the personal-ambition roll (opt-in / stagger) and the
 * lowest-incomplete-mainline resolver. Both are WZ/DB-free.
 */
class BotTempleProgressionManagerTest {

    // ---- templeAmbitionLevel: stable per seed, varied across seeds, always in band ----

    @Test
    void ambitionLevelWithinBand() {
        int lo = BotTempleProgressionManager.AMBITION_MIN_LEVEL;                              // 105
        int hi = BotTempleProgressionManager.AMBITION_MIN_LEVEL + BotTempleProgressionManager.AMBITION_BAND; // 160
        for (long seed = 1; seed <= 500; seed++) {
            int lvl = BotPersonality.random(seed).templeAmbitionLevel();
            assertTrue(lvl >= lo && lvl <= hi, "seed " + seed + " -> " + lvl + " out of [" + lo + "," + hi + "]");
        }
    }

    @Test
    void ambitionLevelStableForFixedSeed() {
        for (long seed : new long[]{1L, 42L, 9999L, 123456789L}) {
            int a = BotPersonality.random(seed).templeAmbitionLevel();
            int b = BotPersonality.random(seed).templeAmbitionLevel();
            assertEquals(a, b, "ambition must be deterministic for seed " + seed);
        }
    }

    @Test
    void ambitionLevelVariesAcrossSeeds() {
        Set<Integer> seen = new HashSet<>();
        for (long seed = 1; seed <= 300; seed++) {
            seen.add(BotPersonality.random(seed).templeAmbitionLevel());
        }
        // A 56-wide band over 300 seeds should populate many distinct levels, not collapse to one.
        assertTrue(seen.size() > 20, "expected a spread of ambition levels, got " + seen.size());
    }

    @Test
    void ambitionLevelNeutralForDefaultProfile() {
        // seed 0 (non-managed default) lands mid-band: 105 + round(0.5 * 55) = 133.
        assertEquals(133, BotPersonality.defaults().templeAmbitionLevel());
    }

    // ---- nextIncompleteMainline: lowest not-yet-completed quest in the strict chain ----

    private static IntPredicate completed(Integer... ids) {
        Set<Integer> set = Set.of(ids);
        return set::contains;
    }

    @Test
    void picksFirstWhenNothingDone() {
        assertEquals(3500, BotTempleProgressionManager.nextIncompleteMainline(completed()));
    }

    @Test
    void picksNextAfterAPrefixIsDone() {
        assertEquals(3501, BotTempleProgressionManager.nextIncompleteMainline(completed(3500)));
        assertEquals(3507, BotTempleProgressionManager.nextIncompleteMainline(
                completed(3500, 3501, 3502, 3503, 3504, 3505, 3506)));
    }

    @Test
    void picksTheLowestGapNotJustTheHighestDone() {
        // 3502 skipped: the resolver must return the lowest incomplete, not the first gap after the max.
        assertEquals(3502, BotTempleProgressionManager.nextIncompleteMainline(completed(3500, 3501, 3503, 3504)));
    }

    @Test
    void returnsMinusOneWhenWholeChainDone() {
        Integer[] all = new Integer[BotTempleProgressionManager.MAINLINE_CHAIN.length];
        for (int i = 0; i < all.length; i++) {
            all[i] = BotTempleProgressionManager.MAINLINE_CHAIN[i];
        }
        assertEquals(-1, BotTempleProgressionManager.nextIncompleteMainline(completed(all)));
    }
}
