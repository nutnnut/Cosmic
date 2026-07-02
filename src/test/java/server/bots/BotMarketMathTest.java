package server.bots;

import org.junit.jupiter.api.Test;
import server.bots.BotMarketMath.Belief;
import server.bots.BotMarketMath.PricePoint;
import server.bots.BotMarketMath.Sample;

import java.util.List;
import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Math-level coverage of the design's S1 sim assertions (docs/bot/living-economy-design.md
 * sec 14 S1 (a)-(g)). The full multi-agent sim lands with BotMarketBook/Consensus (S1c).
 */
class BotMarketMathTest {

    // (a) convergence: dispersed starts fed the same clearing stream end in a tight band.
    // Decay between observations is part of the loop by design - it bounds confidence, which
    // keeps the per-observation step fraction from vanishing and washes out bad priors
    // geometrically instead of linearly.
    @Test
    void dispersedBeliefsConvergeOnSharedEvidence() {
        double[] starts = {200_000, 700_000, 2_500_000};
        double clearing = 1_000_000;
        long gapMs = 1000, halfLifeMs = 1000;
        for (double start : starts) {
            Belief b = new Belief(start, 1.0);
            for (int i = 0; i < 12; i++) {
                b = BotMarketMath.decay(b, gapMs, halfLifeMs);
                b = BotMarketMath.updateBelief(b, clearing, BotMarketMath.W_TRADE);
            }
            assertEquals(clearing, b.estimate(), clearing * 0.10,
                    "start " + start + " should land within 10% of the clearing price");
        }
    }

    @Test
    void firstObservationDominatesEmptyBelief() {
        Belief b = BotMarketMath.updateBelief(Belief.NONE, 850_000, BotMarketMath.W_TRADE);
        assertEquals(850_000, b.estimate(), 1e-9);
    }

    // (b) glut: unsold pressure walks the ask down toward evidence but never below reservation
    @Test
    void repriceWalksDownUnderPressureAndFloorsAtReservation() {
        double ask = 1_500_000, evidence = 900_000, reservation = 1_000_000;
        double repriced = ask;
        for (int i = 0; i < 20; i++) {
            repriced = BotMarketMath.repriceAsk(repriced, evidence, 0.5, 1.0, reservation);
        }
        assertEquals(reservation, repriced, 1e-6, "walks toward evidence, stops at reservation");

        double oneStep = BotMarketMath.repriceAsk(1_500_000, 900_000, 0.5, 1.0, 0);
        assertTrue(oneStep < 1_500_000 && oneStep > 900_000, "single step is partial, not a snap");
    }

    // (c) negotiation floors: counters and accepts never breach reservation
    @Test
    void counterAndAcceptRespectReservation() {
        double counter = BotMarketMath.counterPrice(1_000_000, 600_000, 800_000, 0.5);
        assertTrue(counter >= 800_000, "counter never below reservation");
        assertTrue(counter < 1_000_000, "counter concedes something");
        assertEquals(1_000_000,
                BotMarketMath.counterPrice(1_000_000, 1_100_000, 800_000, 0.5), 1e-9,
                "an offer already above ask is not countered upward");
        assertTrue(BotMarketMath.acceptable(900_000, 800_000, 0.1));
        assertFalse(BotMarketMath.acceptable(850_000, 800_000, 0.1));
    }

    // (d) damping: informed beliefs move less per observation
    @Test
    void confidenceDampsSteps() {
        Belief fresh = new Belief(1_000_000, 1.0);
        Belief seasoned = new Belief(1_000_000, 20.0);
        double freshMove = Math.abs(
                BotMarketMath.updateBelief(fresh, 2_000_000, 1.0).estimate() - 1_000_000);
        double seasonedMove = Math.abs(
                BotMarketMath.updateBelief(seasoned, 2_000_000, 1.0).estimate() - 1_000_000);
        assertTrue(seasonedMove < freshMove / 5, "seasoned belief moves far less");
    }

    // (e) sparse stability + dispersion: empty books pin to consensus; noise disperses bots
    @Test
    void emptyBookRunsOnConsensusAndExperienceOvertakesIt() {
        double blendedEmpty = BotMarketMath.blendWithConsensus(Belief.NONE, 1_000_000, 50);
        assertEquals(1_000_000, blendedEmpty, 1e-9, "no experience -> consensus idea");

        Belief experienced = new Belief(700_000, 12.0);
        double blended = BotMarketMath.blendWithConsensus(experienced, 1_000_000, 50);
        assertTrue(Math.abs(blended - 700_000) < Math.abs(blended - 1_000_000),
                "own trades outweigh capped consensus prior");
    }

    @Test
    void perceptionNoiseIsStableSeededAndShrinksWithInformedness() {
        double n1 = BotMarketMath.perceptionNoise(101, 1082089 * 256L, 20_000, 0.2);
        double n1Again = BotMarketMath.perceptionNoise(101, 1082089 * 256L, 20_000, 0.2);
        double n2 = BotMarketMath.perceptionNoise(202, 1082089 * 256L, 20_000, 0.2);
        assertEquals(n1, n1Again, 1e-12, "same bot+key+day -> same idea (no jitter)");
        assertTrue(Math.abs(n1 - 1.0) <= BotMarketMath.NOISE_MAX_BAND + 1e-9);
        assertFalse(n1 == n2, "different bots hold different ideas");
        assertEquals(1.0, BotMarketMath.perceptionNoise(101, 1, 1, 1.0), 1e-12,
                "fully informed -> no noise");
    }

    // (f) outlier robustness: median barely moves, and the consensus move is volume-damped
    @Test
    void outlierTradeBarelyMovesLiquidConsensus() {
        List<PricePoint> window = List.of(
                new PricePoint(1_000_000, 1), new PricePoint(980_000, 1),
                new PricePoint(1_050_000, 1), new PricePoint(1_020_000, 1),
                new PricePoint(1, 1)); // gift-price outlier
        double median = BotMarketMath.weightedMedian(window);
        assertTrue(median >= 980_000 && median <= 1_050_000, "median ignores the outlier");

        Belief liquid = new Belief(1_000_000, 40);
        Belief moved = BotMarketMath.moveConsensus(liquid, median, 5);
        assertEquals(1_000_000, moved.estimate(), 1_000_000 * 0.02,
                "volume-damped: liquid consensus glides");
    }

    // (g) cross-key coherence: structure prices the never-traded 11-STR from the traded 10-STR
    @Test
    void unobservedBandQuotesAboveObservedViaCalibratedConvexCurve() {
        DoubleUnaryOperator convexRepro = band -> 100_000 * Math.pow(1.6, band); // stand-in curve
        double factor = BotMarketMath.curveCalibration(
                List.of(new Sample(10, 1_000_000, BotMarketMath.W_TRADE)), convexRepro);
        double q10 = BotMarketMath.quoteFromCurve(factor, convexRepro, 10);
        double q11 = BotMarketMath.quoteFromCurve(factor, convexRepro, 11);
        assertEquals(1_000_000, q10, 1, "calibration pins the observed band");
        assertTrue(q11 > 1_000_000, "11-STR band quotes ABOVE the traded 10-STR price");
        assertEquals(1_600_000, q11, 1, "exactly the curve's marginal cost above it");
    }

    @Test
    void impliedStatPricePricesNeverTradedComparable() {
        double beta = BotMarketMath.impliedStatPrice(
                List.of(new Sample(10, 1_000_000, 1)));
        assertEquals(100_000, beta, 1e-6);
        assertEquals(1_100_000, 11 * beta, 1e-6, "11-score substitute quotes ~1.1m, never 300k");
    }

    @Test
    void structuralQuoteLadderPrefersStrongerRelations() {
        double anchorOnly = BotMarketMath.structuralQuote(300_000, 0, 0);
        assertEquals(300_000, anchorOnly, 1e-9);
        double withComparable = BotMarketMath.structuralQuote(300_000, 1_050_000, 0);
        assertTrue(withComparable > 650_000, "comparable outweighs anchor");
        double withCurve = BotMarketMath.structuralQuote(300_000, 1_050_000, 1_600_000);
        assertTrue(withCurve > withComparable, "curve dominates the ladder");
    }

    // supporting machinery

    @Test
    void decayAndHalfLifeAreSelfNormalizing() {
        Belief b = new Belief(1_000_000, 8.0);
        Belief halved = BotMarketMath.decay(b, 1000, 1000);
        assertEquals(4.0, halved.confidence(), 1e-9);
        assertEquals(1_000_000, halved.estimate(), 1e-9, "decay touches confidence, not estimate");

        assertEquals(BotMarketMath.HALF_LIFE_CAP_MS, BotMarketMath.halfLifeMs(0),
                "no history -> longest memory");
        assertEquals(BotMarketMath.HALF_LIFE_FLOOR_MS, BotMarketMath.halfLifeMs(1000),
                "hyper-liquid key clamps to the floor");
        long day = 24L * 60 * 60 * 1000;
        assertEquals(3 * day, BotMarketMath.halfLifeMs(day), "mid-range: a few gaps");
    }

    @Test
    void medianGapAndBandingAndKeyPacking() {
        assertEquals(0, BotMarketMath.medianInterEventGapMs(List.of(5L)));
        assertEquals(10, BotMarketMath.medianInterEventGapMs(List.of(0L, 10L, 20L, 25L)));

        assertEquals(0, BotMarketMath.qualityBand(0, 10));
        assertEquals(3, BotMarketMath.qualityBand(31, 10));
        assertEquals(0, BotMarketMath.qualityBand(50, 0), "no scroll gain -> single band");

        assertEquals(1082089L * 256 + 3, BotMarketMath.priceKey(1082089, 3));
        assertEquals(1082089L * 256 + 255, BotMarketMath.priceKey(1082089, 999), "band clamps");
    }

    @Test
    void openingMarginFollowsStanceAndIgnorance() {
        double firmIgnorant = BotMarketMath.openingMargin(1.0, 0);
        double firmInformed = BotMarketMath.openingMargin(1.0, 20);
        double softInformed = BotMarketMath.openingMargin(0.0, 20);
        assertTrue(firmIgnorant > firmInformed, "low confidence widens the margin");
        assertTrue(firmInformed > softInformed, "firm stance asks more");
        assertTrue(softInformed > 0, "even pushovers keep a sliver of margin");
    }
}
