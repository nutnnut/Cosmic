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
        // totalSlots = slots (fresh, nothing consumed yet) and no worn rival -> combat floor = stop-now,
        // so these legacy cases behave exactly as the single-pass planner did.
        return equipR(value, name, score, slots, slots, 0.0, betterAvailable, fallback, options);
    }

    /** Full candidate with explicit totalSlots + worn-rival floor (for the two-pass behaviours). */
    private static BotScrollPlanner.EquipCandidate equipR(
            DoubleUnaryOperator value, String name, double score, int slots, int totalSlots,
            double wornRivalValue, boolean betterAvailable, boolean fallback,
            BotScrollPlanner.ScrollOption... options) {
        return new BotScrollPlanner.EquipCandidate(
                1302000, name, score, slots, totalSlots, wornRivalValue,
                betterAvailable, false, fallback, List.of(options), value);
    }

    /** Candidate explicitly flagged as a spare out-classed by the worn copy (worn better, worn slots >=). */
    private static BotScrollPlanner.EquipCandidate dominatedByWornEquip(
            String name, double score, int slots, BotScrollPlanner.ScrollOption... options) {
        return new BotScrollPlanner.EquipCandidate(
                1302000, name, score, slots, slots, 0.0, true, true, false, List.of(options), LINEAR);
    }

    /** Run a planning call with the scroll-to-sell profit pass enabled (default off until economy lands). */
    private static <T> T withProfit(java.util.function.Supplier<T> body) {
        boolean prev = BotScrollPlanner.SCROLL_FOR_PROFIT_ENABLED;
        BotScrollPlanner.SCROLL_FOR_PROFIT_ENABLED = true;
        try {
            return body.get();
        } finally {
            BotScrollPlanner.SCROLL_FOR_PROFIT_ENABLED = prev;
        }
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
        // Out-classed for combat -> the self-combat pass refuses to invest in soon-benched gear. The
        // profit pass still considers it (you could scroll it to sell), but with the scroll's cost (20)
        // against the 0.9^slot decay the scroll-to-sell is net-negative too, so overall: no play.
        BotScrollPlanner.ScrollOption costly =
                new BotScrollPlanner.ScrollOption(2040000, "60% str", 0.60, 0.0, 50.0, 20.0);
        assertNull(BotScrollPlanner.planBest(List.of(
                equip("old glove", 100.0, 1, true, false, costly))));
    }

    @Test
    void selfCombatRefusesPieceThatCannotBeatWornRival() {
        // The glove bug: a bag glove (5 free slots) whose EXPECTED scrolled value can't reach the worn
        // glove (worth 1000) is NOT a combat upgrade. The self-combat pass refuses it; only the profit
        // pass (scroll-to-sell) considers it, so any plan returned must be profit-driven, never combat.
        BotScrollPlanner.ScrollPlan plan = withProfit(() -> BotScrollPlanner.planBest(List.of(
                equipR(LINEAR, "bag glove", 3.0, 5, 5, 1000.0, false, false,
                        scroll("70% att", 0.70, 10.0)))));
        assertNotNull(plan);
        assertTrue(plan.profitDriven(), "high worn rival must push the play out of the combat pass");
    }

    @Test
    void selfCombatProposesWhenExpectedValueClearsWornRival() {
        // Same scroll/shape, but the worn rival is weak (worth 20): the expected scrolled value clears
        // it, so this IS a combat upgrade and is proposed by the (priority) self-combat pass.
        BotScrollPlanner.ScrollPlan plan = BotScrollPlanner.planBest(List.of(
                equipR(LINEAR, "bag glove", 10.0, 3, 3, 20.0, false, false,
                        scroll("70% att", 0.70, 10.0))));
        assertNotNull(plan);
        assertFalse(plan.profitDriven(), "beating the worn rival is a combat play, not a profit play");
    }

    @Test
    void profitPassFiresOnlyWhenNoCombatPlayAndIsDecayed() {
        // Out-classed for combat (betterAvailable) so combat is empty; a free, high-odds scroll makes
        // scroll-to-sell net-positive even after the decay -> a profit-driven play is returned.
        BotScrollPlanner.ScrollPlan plan = withProfit(() -> BotScrollPlanner.planBest(List.of(
                equipR(LINEAR, "spare glove", 100.0, 2, 2, 0.0, true, false,
                        scroll("100% att", 1.00, 30.0)))));
        assertNotNull(plan);
        assertTrue(plan.profitDriven());
        // Decay: two 100% successes -> stat 160, but each consumed slot x0.9 -> 160*0.9^2 = 129.6.
        // achievable EV = 129.6; floor = sell-as-is = 100*0.9^0 = 100; gain = 29.6.
        assertEquals(129.6, plan.achievableValue(), 1e-6);
        assertEquals(29.6, plan.expectedValue(), 1e-6);
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

    // --- item 08, layer 1: a stat-dominated spare with MORE slots can still scroll into an upgrade ---

    @Test
    void dominatedLookingSpareWithMoreSlotsIsScrolledNotPreFiltered() {
        // Owner's example: worn 10DEX/6ATT/0slot (rival value ~120 here), spare 8DEX/0ATT/5slot.
        // The spare looks worse as-is (score 80) but has 5 free slots; under a convex (rarity) value
        // its scrolled potential clears the worn rival, so it IS proposed as a combat upgrade. It must
        // NOT be pre-skipped: betterAvailable=false (more slots => the worn does not dominate it).
        BotScrollPlanner.ScrollPlan plan = BotScrollPlanner.planBest(List.of(
                equipR(CONVEX, "spare cape", 8.0, 5, 5, 120.0, false, false,
                        scroll("60% dex", 0.60, 4.0))));
        assertNotNull(plan, "scrolled potential of a high-slot spare must be considered, not pre-filtered");
        assertFalse(plan.profitDriven(), "it beats the worn rival -> a combat upgrade");
    }

    @Test
    void neverScrollsASpareDominatedByTheWornCopy() {
        // Two spare weapons, each out-classed by the worn copy (better att + >= slots). Even with a
        // free, high-odds scroll the planner must NOT scroll them (combat: can't beat worn; profit:
        // burning scrolls on inferior dupes). Reproduces the 91/7-slot-worn vs 89/87 spares bug.
        assertNull(BotScrollPlanner.planBest(List.of(
                dominatedByWornEquip("spare nishada A", 89.0, 7, scroll("100% att", 1.00, 5.0)),
                dominatedByWornEquip("spare nishada B", 87.0, 7, scroll("100% att", 1.00, 5.0)))));
    }

    // --- item 08, layer 2: per-scroll opportunity-cost margin suppresses trivial-gain plays ---

    @Test
    void opportunityMarginSuppressesTinyNetGain() {
        // LINEAR, 1 slot: improvement = p*gain - cost. Here 1.0*11 - 10 = 1, below cost*margin
        // (10 * 0.2 = 2) -> not worth burning a reusable scroll for a trivial bump.
        assertNull(BotScrollPlanner.planBest(List.of(
                equip("glove", 100.0, 1, false, false,
                        new BotScrollPlanner.ScrollOption(2040000, "60% att", 1.00, 0.0, 11.0, 10.0)))));
    }

    @Test
    void opportunityMarginAllowsGainThatClearsTheMargin() {
        // Same scroll cost, bigger gain: improvement = 1.0*15 - 10 = 5 > cost*margin (2) -> proposed.
        BotScrollPlanner.ScrollPlan plan = BotScrollPlanner.planBest(List.of(
                equip("glove", 100.0, 1, false, false,
                        new BotScrollPlanner.ScrollOption(2040000, "60% att", 1.00, 0.0, 15.0, 10.0))));
        assertNotNull(plan);
        assertEquals(5.0, plan.expectedValue(), 1e-9);
    }
}
