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

    // (g2) market evidence can BEND the curve, not just scale it: two clearings with a shallower
    // worst-vs-best spread than the structural prior compress the min-max difference.
    @Test
    void calibratedCurveBendsSpreadToMarketEvidence() {
        DoubleUnaryOperator convex = band -> 100_000 * Math.pow(1.6, band); // structural spread 5->10 ~= 10.5x
        DoubleUnaryOperator single = BotMarketMath.calibratedCurve(
                List.of(new Sample(10, 1_000_000, 1)), convex);
        assertEquals(1_000_000, single.applyAsDouble(10), 5, "one observation still just scales (pins the band)");
        assertEquals(1_600_000, single.applyAsDouble(11), 20, "and follows the structural shape elsewhere");

        // Market pays only 2x more at band 10 than band 5 (a far shallower spread than 10.5x).
        DoubleUnaryOperator bent = BotMarketMath.calibratedCurve(
                List.of(new Sample(5, 1_000_000, 1), new Sample(10, 2_000_000, 1)), convex);
        assertEquals(1_000_000, bent.applyAsDouble(5), 5_000, "fits the low band");
        assertEquals(2_000_000, bent.applyAsDouble(10), 10_000, "fits the high band");
        double spread = bent.applyAsDouble(10) / bent.applyAsDouble(5);
        assertTrue(spread < 3.0, "spread bent down toward the market's 2x, well under the structural 10.5x");
        assertTrue(bent.applyAsDouble(7) > bent.applyAsDouble(5)
                && bent.applyAsDouble(7) < bent.applyAsDouble(10), "unobserved band interpolates monotonically");
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

    // ask base: market belief overrides the reproduction anchor downward; salvage floors it
    @Test
    void askBaseLetsConfidentBeliefOverrideAnchorDownward() {
        // No belief -> the anchor stands (cost-plus prior).
        assertEquals(100_000, BotMarketMath.askBase(0, 0, 100_000, 10_000), 1e-9);
        // Salvage is the hard reservation floor.
        assertEquals(500_000, BotMarketMath.askBase(0, 0, 100_000, 500_000), 1e-9,
                "never below salvage");
        // The fix: a confident belief far below a huge anchor pulls the base DOWN toward the belief,
        // instead of max(belief, anchor) pinning it at the anchor.
        double believed = BotMarketMath.askBase(2_000_000, 50, 50_000_000, 10_000);
        assertTrue(believed < 5_000_000, "confident 2m belief prices near 2m, not the 50m anchor");
        assertTrue(believed > 2_000_000, "the fading anchor prior keeps it a touch above belief");
        // More evidence converges the base closer to the belief.
        double lessSure = BotMarketMath.askBase(2_000_000, 10, 50_000_000, 0);
        double moreSure = BotMarketMath.askBase(2_000_000, 100, 50_000_000, 0);
        assertTrue(moreSure < lessSure, "more clearing evidence -> closer to the belief");
        // With no anchor, belief stands alone (no phantom downward drag).
        assertEquals(1_000_000, BotMarketMath.askBase(1_000_000, 5, 0, 0), 1e-9);
        assertEquals(0, BotMarketMath.askBase(0, 0, 0, 0), 1e-9, "nothing to go on");
    }

    @Test
    void reproSanityCapBoundsTheAnchorToSalvageTier() {
        assertEquals(50_000_000, BotMarketMath.reproSanityCap(50_000), 1e-9,
                "cap = salvage x REPRO_CAP_OVER_SALVAGE");
        assertEquals(BotMarketMath.REPRO_CAP_OVER_SALVAGE, BotMarketMath.reproSanityCap(0), 1e-9,
                "zero salvage still yields a finite floor cap");
        // A billion-meso reproduction anchor is clamped to the item's tier ceiling.
        double cappedAnchor = Math.min(2_000_000_000.0, BotMarketMath.reproSanityCap(50_000));
        assertEquals(50_000_000, cappedAnchor, 1e-9, "the 2.1b runaway is erased");
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
    void undercutTargetCrossesBelowCompetitionButNotAboveOwnValue() {
        assertEquals(980_000 * 0.98, BotMarketMath.undercutTarget(1_300_000, 980_000, 0.02), 1e-6,
                "steps just under the cheapest visible competitor");
        assertEquals(1_300_000, BotMarketMath.undercutTarget(1_300_000, 5_000_000, 0.02), 1e-6,
                "a silly competitor doesn't drag the target up");
        assertEquals(1_300_000, BotMarketMath.undercutTarget(1_300_000, 0, 0.02), 1e-6,
                "no visible competition -> own perception");
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

    @Test
    void humanizeAskLeavesSmallPricesExactAndRoundsBigOnes() {
        assertEquals(4_321, BotMarketMath.humanizeAsk(4_321, 7),
                "below the floor a price is spoken verbatim");
        // A big raw ask is quantized to a nearby human number of the SAME magnitude, never 0.
        long h = BotMarketMath.humanizeAsk(5_825_734, 7);
        assertTrue(h >= 5_500_000 && h <= 6_000_000,
                "a 5.8M ask rounds to a nearby millions figure, magnitude preserved");
    }

    @Test
    void humanizeAskIsDeterministicPerBotButVariesAcrossBots() {
        // Same bot -> same style every time (a coherent shop). Enough distinct bots must exercise
        // more than one style (the point of per-bot styles).
        assertEquals(BotMarketMath.humanizeAsk(5_825_734, 42),
                BotMarketMath.humanizeAsk(5_825_734, 42), "one bot prices the same item identically");
        java.util.Set<Long> shapes = new java.util.HashSet<>();
        for (int bot = 0; bot < 32; bot++) {
            shapes.add(BotMarketMath.humanizeAsk(5_825_734, bot));
        }
        assertTrue(shapes.size() >= 3, "the population uses several rounding styles");
    }

    @Test
    void humanizeAskCoversTheOwnersExampleStyles() {
        // Sweep bots until we have seen every style land on the owner's 5.8M example.
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int bot = 0; bot < 96; bot++) {
            seen.add(BotMarketMath.humanizeAsk(5_825_734, bot));
        }
        assertTrue(seen.contains(5_800_000L), "2 significant figures");
        assertTrue(seen.contains(5_799_999L), "charm 9s");
        assertTrue(seen.contains(5_799_000L), "charm with a .000 tail");
        assertTrue(seen.contains(6_000_000L), "nice half");
        assertTrue(seen.contains(5_750_000L), "nice quarter");
        assertTrue(seen.contains(5_555_555L), "repeated digits");
    }

    @Test
    void humanizeRepeatStyleSnapsToNearestRepdigitLandmark() {
        // The repeated-digit style: 120k rounds to the pure repdigit, 150k crosses the ~20%
        // midpoint to the d-then-5s landmark. Each is unique to the repeat style, so its presence
        // in the population sweep confirms both the style and its snap boundary.
        java.util.Set<Long> at120 = new java.util.HashSet<>();
        java.util.Set<Long> at150 = new java.util.HashSet<>();
        for (int bot = 0; bot < 96; bot++) {
            at120.add(BotMarketMath.humanizeAsk(120_000, bot));
            at150.add(BotMarketMath.humanizeAsk(150_000, bot));
        }
        assertTrue(at120.contains(111_111L), "120k snaps to the pure repdigit");
        assertTrue(at150.contains(155_555L), "150k crosses to the d-then-5s landmark");
    }

    @Test
    void humanizeAskRoundAlwaysLandsOnACleanThousand() {
        // Shout prices must read as k/m — never a charm-9s or repeated-digit tail. Every bot's style
        // must land on a round thousand (mesoShort renders k/m) across a range of raw asks.
        long[] raws = {123_456, 5_825_734, 999_999, 1_499_000, 47_500_000, 250_001};
        for (int bot = 0; bot < 96; bot++) {
            for (long raw : raws) {
                long h = BotMarketMath.humanizeAskRound(raw, bot);
                assertEquals(0, h % 1000, "humanizeAskRound must be a round thousand: " + raw + " -> " + h);
                assertTrue(h > 0, "positive price");
            }
        }
    }

    // buyer-side mirrors

    @Test
    void humanizeBidRoundNeverExceedsInputAndAlwaysLandsOnACleanThousand() {
        // A spoken bid must never exceed the buyer's computed bid, so every style floors.
        long[] raws = {123_456, 5_825_734, 999_999, 1_499_000, 47_500_000, 250_001, 100_000, 2_000_000_000L};
        for (int bot = 0; bot < 96; bot++) {
            for (long raw : raws) {
                long h = BotMarketMath.humanizeBidRound(raw, bot);
                assertEquals(0, h % 1000, "humanizeBidRound must be a round thousand: " + raw + " -> " + h);
                assertTrue(h <= raw, "humanizeBidRound must never exceed the input: " + raw + " -> " + h);
                assertTrue(h >= 1000, "positive, at-least-a-thousand price");
            }
        }
    }

    @Test
    void humanizeBidRoundIsDeterministicPerBot() {
        assertEquals(BotMarketMath.humanizeBidRound(5_825_734, 42),
                BotMarketMath.humanizeBidRound(5_825_734, 42), "one bot bids the same item identically");
        java.util.Set<Long> shapes = new java.util.HashSet<>();
        for (int bot = 0; bot < 32; bot++) {
            shapes.add(BotMarketMath.humanizeBidRound(5_825_734, bot));
        }
        assertTrue(shapes.size() >= 2, "the population uses more than one rounding style");
    }

    @Test
    void humanizeBidRoundFloorsSubFloorInputToNearestThousand() {
        assertEquals(55_000, BotMarketMath.humanizeBidRound(55_400, 7));
    }

    @Test
    void counterBidConcedesUpwardWithinCeiling() {
        double counter = BotMarketMath.counterBid(600_000, 1_000_000, 800_000, 0.5);
        assertTrue(counter <= 800_000, "counter never above ceiling");
        assertTrue(counter > 600_000, "counter concedes something");

        // firmness 1.0 barely moves.
        double barelyMoves = BotMarketMath.counterBid(600_000, 1_000_000, 800_000, 1.0);
        assertEquals(600_000, barelyMoves, 1e-9, "firmness 1.0 stays put");

        // firmness 0.0 meets min(partnerAsk, ceiling).
        double meetsCap = BotMarketMath.counterBid(600_000, 700_000, 800_000, 0.0);
        assertEquals(700_000, meetsCap, 1e-9, "firmness 0 meets the partner ask when it's under ceiling");
        double meetsCeiling = BotMarketMath.counterBid(600_000, 1_000_000, 800_000, 0.0);
        assertEquals(800_000, meetsCeiling, 1e-9, "firmness 0 meets the ceiling when the ask exceeds it");

        // cap at/below currentBid returns currentBid unchanged.
        assertEquals(600_000, BotMarketMath.counterBid(600_000, 500_000, 800_000, 0.5), 1e-9,
                "a partner ask already below the bid is not countered downward");

        // never exceeds ceiling even at firmness 0.
        assertTrue(BotMarketMath.counterBid(100_000, 10_000_000, 800_000, 0.0) <= 800_000);
    }

    @Test
    void acceptableAskRespectsCeilingAndSlack() {
        assertTrue(BotMarketMath.acceptableAsk(800_000, 800_000, 0), "ask at ceiling accepted with no slack");
        assertFalse(BotMarketMath.acceptableAsk(800_001, 800_000, 0), "ask above ceiling rejected with no slack");
        // slack 0.1 -> effective ceiling = 800_000 * 0.9 = 720_000.
        assertTrue(BotMarketMath.acceptableAsk(720_000, 800_000, 0.1), "ask at the slacked ceiling accepted");
        assertFalse(BotMarketMath.acceptableAsk(720_001, 800_000, 0.1), "ask past the slacked ceiling rejected");
    }
}
