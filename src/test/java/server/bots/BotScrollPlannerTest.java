package server.bots;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotScrollPlannerTest {

    /** Regular slot-consuming scroll (no boom). */
    private static BotScrollPlanner.ScrollOption scroll(String name, double success, double gain) {
        return new BotScrollPlanner.ScrollOption(2040000, name, success, 0.0, gain);
    }

    /** Destroy-capable scroll (e.g. chaos): a failed roll can boom the item. */
    private static BotScrollPlanner.ScrollOption boomScroll(String name, double success, double boom, double gain) {
        return new BotScrollPlanner.ScrollOption(2049000, name, success, boom, gain);
    }

    private static BotScrollPlanner.EquipCandidate equip(
            String name, double currentValue, boolean betterAvailable, double slotReserve,
            boolean fallback, BotScrollPlanner.ScrollOption... options) {
        return new BotScrollPlanner.EquipCandidate(
                1302000, name, currentValue, betterAvailable, slotReserve, fallback, List.of(options));
    }

    @Test
    void picksPositiveWorthNonBoomScroll() {
        BotScrollPlanner.ScrollPlan plan = BotScrollPlanner.planBest(List.of(
                equip("work glove", 100.0, false, 0.0, false,
                        scroll("60% str", 0.60, 30.0))));

        assertNotNull(plan);
        assertEquals("60% str", plan.scroll().scrollName());
        assertFalse(plan.usesBoomCapableScroll());
        // worth = 0.6 * 30 = 18; slotReserve 0 -> net 18
        assertEquals(18.0, plan.expectedValue(), 1e-9);
    }

    @Test
    void skipsEquipWithBetterReplacementAvailable() {
        // Great scroll, but the slot is already out-classed -> don't invest in soon-benched gear.
        assertNull(BotScrollPlanner.planBest(List.of(
                equip("old glove", 100.0, true, 0.0, false,
                        scroll("60% str", 0.60, 50.0)))));
    }

    @Test
    void skipsScrollBelowSlotOpportunityCost() {
        // worth = 0.6 * 30 = 18, but the slot is worth keeping for something better (reserve 20).
        assertNull(BotScrollPlanner.planBest(List.of(
                equip("glove", 100.0, false, 20.0, false,
                        scroll("60% str", 0.60, 30.0)))));
    }

    @Test
    void spendsSlotWhenWorthBeatsReserve() {
        // Same reserve, but a stronger scroll clears it: worth = 1.0 * 25 = 25 > 20 -> net 5.
        BotScrollPlanner.ScrollPlan plan = BotScrollPlanner.planBest(List.of(
                equip("glove", 100.0, false, 20.0, false,
                        scroll("100% str", 1.00, 25.0))));

        assertNotNull(plan);
        assertEquals(5.0, plan.expectedValue(), 1e-9);
    }

    @Test
    void skipsZeroGainScroll() {
        assertNull(BotScrollPlanner.planBest(List.of(
                equip("glove", 100.0, false, 0.0, false,
                        scroll("0 gain", 1.00, 0.0)))));
    }

    @Test
    void rejectsBoomScrollWithoutFallback() {
        // Strongly positive worth, but no backup gear -> a boom would be catastrophic, so refuse.
        assertNull(BotScrollPlanner.planBest(List.of(
                equip("only weapon", 100.0, false, 0.0, false,
                        boomScroll("chaos", 0.50, 0.50, 400.0)))));
    }

    @Test
    void allowsBoomScrollWithFallbackAndStrongWorth() {
        BotScrollPlanner.ScrollPlan plan = BotScrollPlanner.planBest(List.of(
                equip("weapon", 100.0, false, 0.0, true,
                        boomScroll("chaos", 0.50, 0.50, 400.0))));

        assertNotNull(plan);
        assertTrue(plan.usesBoomCapableScroll());
        // worth = 0.5*400 - 0.5*(0.5*100) = 200 - 25 = 175; boomLoss = 25; 175 >= 1.0*25 -> allowed
        assertEquals(175.0, plan.expectedValue(), 1e-9);
        assertTrue(plan.proposal().contains("boom"));
    }

    @Test
    void rejectsBoomScrollThatIsNotStronglyPositive() {
        // Positive worth but not strongly so relative to the boom loss -> refuse even with a fallback.
        // worth = 0.5*60 - 0.5*(0.5*100) = 30 - 25 = 5; boomLoss = 25; 5 < 1.0*25 -> rejected.
        assertNull(BotScrollPlanner.planBest(List.of(
                equip("weapon", 100.0, false, 0.0, true,
                        boomScroll("chaos", 0.50, 0.50, 60.0)))));
    }

    @Test
    void picksHighestNetAcrossCandidates() {
        BotScrollPlanner.ScrollPlan plan = BotScrollPlanner.planBest(List.of(
                equip("glove", 100.0, false, 0.0, false,
                        scroll("60% str", 0.60, 30.0)),    // worth 18
                equip("hat", 100.0, false, 0.0, false,
                        scroll("100% int", 1.00, 25.0)))); // worth 25

        assertNotNull(plan);
        assertEquals("hat", plan.equip().equipName());
        assertEquals(25.0, plan.expectedValue(), 1e-9);
    }
}
