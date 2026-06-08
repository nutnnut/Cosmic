package server.bots;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotScrollPlannerTest {

    /** Identity value: stat-score is its own value. Linear ⇒ the DP reduces to greedy. */
    private static final DoubleUnaryOperator LINEAR = x -> x;
    /** Strictly convex value: a higher stat-score is worth disproportionately more (rarer). */
    private static final DoubleUnaryOperator CONVEX = x -> x * x;

    /** Regular slot-consuming scroll (no boom, no cost). */
    private static BotScrollPlanner.ScrollOption scroll(String name, double success, double gain) {
        return new BotScrollPlanner.ScrollOption(2040000, name, success, 0.0, gain, 0.0);
    }

    /** Destroy-capable scroll (e.g. chaos): a failed roll can boom the item. */
    private static BotScrollPlanner.ScrollOption boomScroll(String name, double success, double boom, double gain) {
        return new BotScrollPlanner.ScrollOption(2049000, name, success, boom, gain, 0.0);
    }

    private static BotScrollPlanner.EquipCandidate equip(
            String name, double score, int slots, boolean betterAvailable,
            boolean fallback, BotScrollPlanner.ScrollOption... options) {
        return equipV(LINEAR, name, score, slots, betterAvailable, fallback, options);
    }

    private static BotScrollPlanner.EquipCandidate equipV(
            DoubleUnaryOperator value, String name, double score, int slots, boolean betterAvailable,
            boolean fallback, BotScrollPlanner.ScrollOption... options) {
        return new BotScrollPlanner.EquipCandidate(
                1302000, name, score, slots, betterAvailable, fallback, List.of(options), value);
    }

    @Test
    void picksPositiveWorthNonBoomScroll() {
        BotScrollPlanner.ScrollPlan plan = BotScrollPlanner.planBest(List.of(
                equip("work glove", 100.0, 1, false, false,
                        scroll("60% str", 0.60, 30.0))));

        assertNotNull(plan);
        assertEquals("60% str", plan.scroll().scrollName());
        assertFalse(plan.usesBoomCapableScroll());
        // 1 slot, linear: improvement = p*gain = 0.6 * 30 = 18
        assertEquals(18.0, plan.expectedValue(), 1e-9);
    }

    @Test
    void skipsEquipWithBetterReplacementAvailable() {
        // Great scroll, but the slot is already out-classed -> don't invest in soon-benched gear.
        assertNull(BotScrollPlanner.planBest(List.of(
                equip("old glove", 100.0, 1, true, false,
                        scroll("60% str", 0.60, 50.0)))));
    }

    @Test
    void skipsEquipWithNoFreeSlots() {
        assertNull(BotScrollPlanner.planBest(List.of(
                equip("maxed glove", 100.0, 0, false, false,
                        scroll("60% str", 0.60, 30.0)))));
    }

    @Test
    void skipsZeroGainScroll() {
        assertNull(BotScrollPlanner.planBest(List.of(
                equip("glove", 100.0, 1, false, false,
                        scroll("0 gain", 1.00, 0.0)))));
    }

    @Test
    void looksAheadOverTheWholeSlotBudget() {
        // 2 free slots, one 50%/+20 scroll: the DP values BOTH slots, not just the next one.
        BotScrollPlanner.ScrollPlan plan = BotScrollPlanner.planBest(List.of(
                equip("2-slot cape", 100.0, 2, false, false,
                        scroll("50% att", 0.50, 20.0))));

        assertNotNull(plan);
        // improvement = 2 * p*gain = 2 * 0.5 * 20 = 20 (one slot would be 10)
        assertEquals(20.0, plan.expectedValue(), 1e-9);
    }

    @Test
    void linearValueReducesToGreedyAcrossCandidates() {
        BotScrollPlanner.ScrollPlan plan = BotScrollPlanner.planBest(List.of(
                equip("glove", 100.0, 1, false, false,
                        scroll("60% str", 0.60, 30.0)),    // improvement 18
                equip("hat", 100.0, 1, false, false,
                        scroll("100% int", 1.00, 25.0)))); // improvement 25

        assertNotNull(plan);
        assertEquals("hat", plan.equip().equipName());
        assertEquals(25.0, plan.expectedValue(), 1e-9);
    }

    @Test
    void convexValueSnowballsTheAlreadyStrongerPiece() {
        // Identical scroll on both pieces. Under LINEAR the improvement ties (both 0.5*10=5) and the
        // first piece wins. Under a convex (rarity) value, advancing the already-strong piece is worth
        // far more, so the DP prefers it -> the "snowball the winner" behaviour.
        assertEquals("weak", BotScrollPlanner.planBest(List.of(
                equipV(LINEAR, "weak", 10.0, 1, false, false, scroll("50% att", 0.50, 10.0)),
                equipV(LINEAR, "strong", 100.0, 1, false, false, scroll("50% att", 0.50, 10.0))))
                .equip().equipName());

        assertEquals("strong", BotScrollPlanner.planBest(List.of(
                equipV(CONVEX, "weak", 10.0, 1, false, false, scroll("50% att", 0.50, 10.0)),
                equipV(CONVEX, "strong", 100.0, 1, false, false, scroll("50% att", 0.50, 10.0))))
                .equip().equipName());
    }

    @Test
    void scrollCostCanMakeAPlayNotWorthIt() {
        // p*gain = 0.5*20 = 10, but the scroll itself is worth 12 in farm-value -> net negative.
        BotScrollPlanner.ScrollOption pricey =
                new BotScrollPlanner.ScrollOption(2040000, "rare 50% att", 0.50, 0.0, 20.0, 12.0);
        assertNull(BotScrollPlanner.planBest(List.of(
                equip("glove", 100.0, 1, false, false, pricey))));
    }

    @Test
    void rejectsBoomScrollWithoutFallback() {
        // Strongly positive worth, but no backup gear -> a boom would be catastrophic, so refuse.
        assertNull(BotScrollPlanner.planBest(List.of(
                equip("only weapon", 100.0, 1, false, false,
                        boomScroll("chaos", 0.50, 0.50, 400.0)))));
    }

    @Test
    void allowsBoomScrollWithFallbackAndPositiveEv() {
        BotScrollPlanner.ScrollPlan plan = BotScrollPlanner.planBest(List.of(
                equip("weapon", 100.0, 1, false, true,
                        boomScroll("chaos", 0.50, 0.50, 400.0))));

        assertNotNull(plan);
        assertTrue(plan.usesBoomCapableScroll());
        // ev = 0.5*(100+400) + 0.5*0.5*100 = 250 + 25 = 275; improvement over stop 100 = 175
        assertEquals(175.0, plan.expectedValue(), 1e-9);
        assertTrue(plan.proposal().contains("boom"));
    }

    @Test
    void rejectsEvNegativeBoomScroll() {
        // Has a fallback, but the destruction risk outweighs the gain:
        // ev = 0.5*(100+30) + 0.5*0.5*100 = 65 + 25 = 90 < stop 100 -> rejected.
        assertNull(BotScrollPlanner.planBest(List.of(
                equip("weapon", 100.0, 1, false, true,
                        boomScroll("chaos", 0.50, 0.50, 30.0)))));
    }
}
