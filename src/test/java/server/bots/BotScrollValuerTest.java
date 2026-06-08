package server.bots;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotScrollValuerTest {

    private static BotScrollValuer.ScrollSpec sc(double p, double gain, double cost) {
        return new BotScrollValuer.ScrollSpec(p, gain, cost);
    }

    @Test
    void valueAtOrBelowBaseIsJustBaseCost() {
        DoubleUnaryOperator v = BotScrollValuer.reproductionValue(
                0.0, 1, List.of(sc(0.5, 1.0, 100.0)), 1000.0);
        assertEquals(1000.0, v.applyAsDouble(0.0), 1e-9);
        assertEquals(1000.0, v.applyAsDouble(-5.0), 1e-9);
    }

    @Test
    void degenerateItemIsFlatAtBaseCost() {
        DoubleUnaryOperator noSlots = BotScrollValuer.reproductionValue(0.0, 0, List.of(sc(1.0, 1.0, 1.0)), 500.0);
        DoubleUnaryOperator noScrolls = BotScrollValuer.reproductionValue(0.0, 5, List.of(), 500.0);
        assertEquals(500.0, noSlots.applyAsDouble(10.0), 1e-9);
        assertEquals(500.0, noScrolls.applyAsDouble(10.0), 1e-9);
    }

    @Test
    void singleSlotFiftyPercentHasClosedFormCost() {
        // 1 slot, 50%/+1 scroll cost c=100, base cost B=1000.
        // Restart recurrence: D = c + 0.5*(B+D)  ->  D = 2c + B = 1200; value = B + D = 2B + 2c = 2200.
        DoubleUnaryOperator v = BotScrollValuer.reproductionValue(
                0.0, 1, List.of(sc(0.5, 1.0, 100.0)), 1000.0);
        assertEquals(2200.0, v.applyAsDouble(1.0), 1e-6);
    }

    @Test
    void curveIsConvexAboveBase() {
        // 2 slots, 50%/+1 scroll c=100, B=1000. Hand-solved restart fixed points:
        //   value(1) = 1000 + 1600/3 ~= 1533.33 ;  value(2) = 1000 + 3600 = 4600.
        DoubleUnaryOperator v = BotScrollValuer.reproductionValue(
                0.0, 2, List.of(sc(0.5, 1.0, 100.0)), 1000.0);
        double v0 = v.applyAsDouble(0.0), v1 = v.applyAsDouble(1.0), v2 = v.applyAsDouble(2.0);
        assertEquals(1000.0, v0, 1e-6);
        assertEquals(1533.333333, v1, 0.1);
        assertEquals(4600.0, v2, 0.1);
        // strictly convex: the second point of stat costs far more than the first
        assertTrue((v2 - v1) > (v1 - v0), "reproduction value must be convex above base");
    }

    @Test
    void valueIsNonDecreasing() {
        DoubleUnaryOperator v = BotScrollValuer.reproductionValue(
                5.0, 5, List.of(sc(0.6, 2.0, 550_000.0), sc(0.1, 3.0, 1_100_000.0)), 500_000.0);
        double prev = Double.NEGATIVE_INFINITY;
        for (double s = 0; s <= 20; s += 1.0) {
            double cur = v.applyAsDouble(s);
            assertTrue(cur >= prev - 1e-6, "value must be non-decreasing at score " + s);
            prev = cur;
        }
    }
}
