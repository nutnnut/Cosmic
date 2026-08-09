package server.bots;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.bots.BotZakumPrequestManager.Step;

/**
 * Pure logic for the Zakum-prequest driver: the personal-ambition roll (opt-in / stagger), the
 * step resolver, and the class-chief mapping. All WZ/DB-free.
 */
class BotZakumPrequestManagerTest {

    // ---- zakumAmbitionLevel: stable per seed, varied across seeds, always in band ----

    @Test
    void ambitionLevelWithinBand() {
        int lo = BotZakumPrequestManager.AMBITION_MIN_LEVEL;                                    // 70
        int hi = BotZakumPrequestManager.AMBITION_MIN_LEVEL + BotZakumPrequestManager.AMBITION_BAND; // 120
        for (long seed = 1; seed <= 500; seed++) {
            int lvl = BotPersonality.random(seed).zakumAmbitionLevel();
            assertTrue(lvl >= lo && lvl <= hi, "seed " + seed + " -> " + lvl + " out of [" + lo + "," + hi + "]");
        }
    }

    @Test
    void ambitionLevelStableForFixedSeed() {
        for (long seed : new long[]{1L, 42L, 9999L, 123456789L}) {
            int a = BotPersonality.random(seed).zakumAmbitionLevel();
            int b = BotPersonality.random(seed).zakumAmbitionLevel();
            assertEquals(a, b, "ambition must be deterministic for seed " + seed);
        }
    }

    @Test
    void ambitionLevelVariesAcrossSeeds() {
        Set<Integer> seen = new HashSet<>();
        for (long seed = 1; seed <= 300; seed++) {
            seen.add(BotPersonality.random(seed).zakumAmbitionLevel());
        }
        // A 51-wide band over 300 seeds should populate many distinct levels, not collapse to one.
        assertTrue(seen.size() > 20, "expected a spread of ambition levels, got " + seen.size());
    }

    @Test
    void ambitionLevelIndependentOfTempleAmbition() {
        // Distinct salts: the two traits must not be rank-correlated copies of each other.
        int sameOrder = 0, checks = 0;
        for (long seed = 1; seed <= 200; seed += 2) {
            BotPersonality a = BotPersonality.random(seed);
            BotPersonality b = BotPersonality.random(seed + 1);
            boolean templeUp = a.templeAmbitionLevel() < b.templeAmbitionLevel();
            boolean zakumUp = a.zakumAmbitionLevel() < b.zakumAmbitionLevel();
            if (templeUp == zakumUp) {
                sameOrder++;
            }
            checks++;
        }
        assertTrue(sameOrder < checks, "zakum ambition mirrors temple ambition ordering exactly");
    }

    @Test
    void ambitionLevelNeutralForDefaultProfile() {
        // seed 0 (non-managed default) lands mid-band: 70 + round(0.5 * 50) = 95.
        assertEquals(95, BotPersonality.defaults().zakumAmbitionLevel());
    }

    // ---- resolveStep: lowest unmet requirement in the fixed chain ----

    @Test
    void freshBotStartsWithApproval() {
        assertEquals(Step.APPROVAL, BotZakumPrequestManager.resolveStep(false, false, false, false, 0));
    }

    @Test
    void approvedBotRunsThePq() {
        assertEquals(Step.PQ, BotZakumPrequestManager.resolveStep(true, false, false, false, 0));
    }

    @Test
    void breathOfFireUnlocksTheLavaCourse() {
        assertEquals(Step.LAVA, BotZakumPrequestManager.resolveStep(true, false, true, false, 0));
    }

    @Test
    void bothBreathsGrindTeethUntilQuota() {
        assertEquals(Step.TEETH, BotZakumPrequestManager.resolveStep(true, false, true, true, 0));
        assertEquals(Step.TEETH, BotZakumPrequestManager.resolveStep(true, false, true, true,
                BotZakumPrequestManager.TEETH_NEEDED - 1));
    }

    @Test
    void fullSetTurnsIn() {
        assertEquals(Step.TURNIN, BotZakumPrequestManager.resolveStep(true, false, true, true,
                BotZakumPrequestManager.TEETH_NEEDED));
    }

    @Test
    void completedTrialsAreDoneRegardlessOfInventory() {
        assertEquals(Step.DONE, BotZakumPrequestManager.resolveStep(true, true, false, false, 0));
        assertEquals(Step.DONE, BotZakumPrequestManager.resolveStep(false, true, true, true, 99));
    }

    @Test
    void lostBreathOfFireFallsBackToThePq() {
        // e.g. wiped mid-chain: teeth full but 4031061 gone -> redo stage 1, not the turn-in.
        assertEquals(Step.PQ, BotZakumPrequestManager.resolveStep(true, false, false, true,
                BotZakumPrequestManager.TEETH_NEEDED));
    }

    // ---- chiefNpcFor: the five El Nath chiefs, shared by Cygnus/Aran branches ----

    @Test
    void mapsAdventurerJobsToTheirChiefs() {
        assertEquals(2020008, BotZakumPrequestManager.chiefNpcFor(110));  // fighter
        assertEquals(2020009, BotZakumPrequestManager.chiefNpcFor(230));  // priest-line
        assertEquals(2020010, BotZakumPrequestManager.chiefNpcFor(311));  // ranger-line
        assertEquals(2020011, BotZakumPrequestManager.chiefNpcFor(410));  // assassin-line
        assertEquals(2020013, BotZakumPrequestManager.chiefNpcFor(510));  // brawler-line
    }

    @Test
    void foldsCygnusAndAranOntoTheSameChiefs() {
        assertEquals(2020008, BotZakumPrequestManager.chiefNpcFor(1110)); // dawn warrior
        assertEquals(2020009, BotZakumPrequestManager.chiefNpcFor(1210)); // blaze wizard
        assertEquals(2020010, BotZakumPrequestManager.chiefNpcFor(1310)); // wind archer
        assertEquals(2020011, BotZakumPrequestManager.chiefNpcFor(1410)); // night walker
        assertEquals(2020013, BotZakumPrequestManager.chiefNpcFor(1510)); // thunder breaker
        assertEquals(2020008, BotZakumPrequestManager.chiefNpcFor(2110)); // aran
    }

    @Test
    void beginnersHaveNoChief() {
        assertEquals(-1, BotZakumPrequestManager.chiefNpcFor(0));
        assertEquals(-1, BotZakumPrequestManager.chiefNpcFor(1000));
        assertEquals(-1, BotZakumPrequestManager.chiefNpcFor(2000));
    }
}
