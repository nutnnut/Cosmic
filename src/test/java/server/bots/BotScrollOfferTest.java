package server.bots;

import client.Job;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WZ-free tests for the proactive-scroll-offer job stat-relevance seam
 * ({@link BotOfferManager#scrollStatRelevantToJob}). Stat keys are the getEquipStats "inc"-stripped
 * keys (WATK=PAD, MATK=MAD). The CATEGORY half (a bow scroll fits no warrior gear) is the pure
 * id-math {@code BotScrollManager.applicable}; here we cover the job-specific stat half.
 */
class BotScrollOfferTest {

    @Test
    void intScrollUsefulOnlyToIntJobs() {
        Map<String, Integer> intScroll = Map.of("INT", 3);
        assertTrue(BotOfferManager.scrollStatRelevantToJob(intScroll, Job.MAGICIAN));
        assertFalse(BotOfferManager.scrollStatRelevantToJob(intScroll, Job.WARRIOR));
        assertFalse(BotOfferManager.scrollStatRelevantToJob(intScroll, Job.BOWMAN));
    }

    @Test
    void lukScrollUsefulToThiefNotWarrior() {
        Map<String, Integer> lukScroll = Map.of("LUK", 3);
        assertTrue(BotOfferManager.scrollStatRelevantToJob(lukScroll, Job.THIEF));
        assertFalse(BotOfferManager.scrollStatRelevantToJob(lukScroll, Job.WARRIOR));
    }

    @Test
    void strScrollUsefulToWarriorNotMage() {
        Map<String, Integer> strScroll = Map.of("STR", 3);
        assertTrue(BotOfferManager.scrollStatRelevantToJob(strScroll, Job.WARRIOR));
        assertFalse(BotOfferManager.scrollStatRelevantToJob(strScroll, Job.MAGICIAN));
    }

    @Test
    void attackScrollIsStatRelevantToBothMeleeAndBow() {
        // PAD (weapon att) is valued by warriors AND bowmen by STAT; only the category check
        // (applicable) keeps a bow-att scroll off a warrior. This documents why stat-only relevance
        // (the old isIrrelevantEquipScroll) was too weak and the offer path is category-aware.
        Map<String, Integer> attScroll = Map.of("PAD", 5);
        assertTrue(BotOfferManager.scrollStatRelevantToJob(attScroll, Job.WARRIOR));
        assertTrue(BotOfferManager.scrollStatRelevantToJob(attScroll, Job.BOWMAN));
        assertFalse(BotOfferManager.scrollStatRelevantToJob(attScroll, Job.MAGICIAN));
    }

    @Test
    void noStatsIsNeverRelevant() {
        assertFalse(BotOfferManager.scrollStatRelevantToJob(null, Job.WARRIOR));
        assertFalse(BotOfferManager.scrollStatRelevantToJob(Map.of(), Job.WARRIOR));
        assertFalse(BotOfferManager.scrollStatRelevantToJob(Map.of("HP", 100), Job.WARRIOR));
    }
}
