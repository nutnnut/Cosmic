package server.bots;

import org.junit.jupiter.api.Test;
import server.bots.BotMarketLedger.EventKind;
import server.bots.BotMarketLedger.MarketEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless multi-agent sim over BotMarketBook + BotMarketConsensus (no DB, no server) —
 * the system-level half of docs/bot/living-economy-design.md sec 14 S1. One good; every seller
 * keeps a standing stall ask (repriced under unsold pressure, undercutting visible competition);
 * a buyer takes the cheapest ask it can afford. Clearing = stall purchase at the posted ask.
 */
class BotMarketSimTest {

    private static final long KEY = BotMarketMath.priceKey(1082089, 0);
    private static final long T0 = 1_000_000_000_000L;
    private static final long ROUND_MS = 60L * 60 * 1000; // one market round per hour
    private static final double UNDERCUT = 0.02;

    /** Minimal design-faithful agent: cost-plus when ignorant, perception-led once informed,
     *  unsold-pressure repricing toward just-under-the-competition (sec 5). */
    private static final class Agent {
        final int id;
        final double cost;       // seller reservation (own farming cost)
        final double useValue;   // buyer WTP cap
        final BotMarketBook book;
        double lastAsk;
        int unsoldRounds;

        Agent(int id, double cost, double useValue, BotMarketBook.ConsensusSource src) {
            this.id = id;
            this.cost = cost;
            this.useValue = useValue;
            this.book = new BotMarketBook(id, 0.5, src);
        }

        double refreshAsk(long now, double bestCompetingAsk) {
            double perceived = book.perceivedPrice(KEY, now);
            double conf = book.privateConfidence(KEY, now);
            double margin = BotMarketMath.openingMargin(0.5, conf);
            double opening = perceived > 0 ? perceived * (1 + margin * 0.3) : cost * (1 + margin);
            if (lastAsk <= 0) {
                lastAsk = Math.max(cost, opening);
            } else {
                double evidence = BotMarketMath.undercutTarget(perceived > 0 ? perceived : opening,
                        bestCompetingAsk, UNDERCUT);
                double pressure = Math.min(1.0, unsoldRounds / 4.0);
                lastAsk = BotMarketMath.repriceAsk(lastAsk, evidence, conf, pressure, cost);
            }
            return lastAsk;
        }

        double wtp(long now) {
            double perceived = book.perceivedPrice(KEY, now);
            double base = perceived > 0 ? perceived * 1.05 : useValue;
            return Math.min(useValue, base);
        }
    }

    /**
     * Stall-market rounds: every seller's stall stands each round (ask repriced first, seeing the
     * cheapest OTHER stall from last round); one random buyer takes the cheapest affordable ask.
     */
    private static long runStallRounds(List<Agent> sellers, List<Agent> buyers,
                                       BotMarketConsensus consensus, int rounds, long startMs,
                                       Random rng, List<Double> clearingsOut) {
        long now = startMs;
        long eventId = rng.nextInt(1000) * 100_000L + 1;
        for (int r = 0; r < rounds; r++) {
            now += ROUND_MS;
            // reprice all stalls against the cheapest competitor visible from the previous round
            for (Agent s : sellers) {
                double bestOther = 0;
                for (Agent o : sellers) {
                    if (o != s && o.lastAsk > 0 && (bestOther == 0 || o.lastAsk < bestOther)) {
                        bestOther = o.lastAsk;
                    }
                }
                s.refreshAsk(now, bestOther);
            }
            Agent cheapest = null;
            for (Agent s : sellers) {
                if (cheapest == null || s.lastAsk < cheapest.lastAsk) {
                    cheapest = s;
                }
            }
            Agent buyer = buyers.get(rng.nextInt(buyers.size()));
            if (cheapest != null && cheapest.lastAsk <= buyer.wtp(now)) {
                double px = cheapest.lastAsk;
                MarketEvent sale = new MarketEvent(eventId++, now, EventKind.STALL_SALE,
                        1082089, 0, 1, Math.round(px), cheapest.id, buyer.id, 910000001);
                consensus.sweep(List.of(sale), now);
                cheapest.book.observe(KEY, px, BotMarketMath.W_TRADE, now);
                buyer.book.observe(KEY, px, BotMarketMath.W_TRADE, now);
                cheapest.unsoldRounds = 0;
                for (Agent s : sellers) {
                    if (s != cheapest) {
                        s.unsoldRounds++;
                    }
                }
                if (clearingsOut != null) {
                    clearingsOut.add(px);
                }
            } else {
                for (Agent s : sellers) {
                    s.unsoldRounds++;
                }
            }
        }
        return now;
    }

    // (a)+(d): dispersed costs/priors converge to a stable trading band inside [cost, use-value]
    @Test
    void marketConvergesToStableBand() {
        BotMarketConsensus consensus = new BotMarketConsensus(null, null);
        List<Agent> sellers = new ArrayList<>();
        List<Agent> buyers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            sellers.add(new Agent(100 + i, 600_000 + i * 100_000, 0, consensus));
            buyers.add(new Agent(200 + i, 0, 1_400_000 + i * 100_000, consensus));
        }
        List<Double> clearings = new ArrayList<>();
        long end = runStallRounds(sellers, buyers, consensus, 60, T0, new Random(42), clearings);

        assertTrue(clearings.size() > 20, "market actually trades: " + clearings.size());
        double c = consensus.consensus(KEY);
        assertTrue(c >= 600_000 && c <= 1_500_000, "consensus lands inside the economic band: " + c);

        // late clearings are tighter than early ones (no sustained oscillation)
        double earlySpread = spread(clearings.subList(0, 8));
        double lateSpread = spread(clearings.subList(clearings.size() - 8, clearings.size()));
        assertTrue(lateSpread <= earlySpread,
                "clearing spread does not widen: early " + earlySpread + " late " + lateSpread);

        // every participant's working idea orbits the consensus (noise band + margin slack)
        for (Agent a : sellers) {
            double p = a.book.perceivedPrice(KEY, end);
            assertTrue(Math.abs(p - c) / c < 0.35, "seller " + a.id + " perceives " + p + " vs " + c);
        }
    }

    // (e): a sparse 3-agent market stays pinned to consensus instead of random-walking
    @Test
    void sparseMarketStaysStable() {
        BotMarketConsensus consensus = new BotMarketConsensus(null, null);
        List<Agent> sellers = List.of(new Agent(1, 800_000, 0, consensus));
        List<Agent> buyers = List.of(
                new Agent(2, 0, 1_300_000, consensus),
                new Agent(3, 0, 1_200_000, consensus));

        List<Double> clearings = new ArrayList<>();
        long end = runStallRounds(sellers, buyers, consensus, 20, T0, new Random(7), clearings);
        double c = consensus.consensus(KEY);
        assertTrue(c > 0, "even a sparse market forms a consensus");

        for (Agent a : buyers) {
            double p = a.book.perceivedPrice(KEY, end);
            assertTrue(Math.abs(p - c) / c < BotMarketMath.NOISE_MAX_BAND + 0.10,
                    "sparse-market perception stays banded: " + p + " vs " + c);
        }
        for (double px : clearings) {
            assertTrue(px >= 800_000, "no clearing below the seller's cost floor");
        }
    }

    // (f): one gift-price sale barely moves an established consensus
    @Test
    void outlierSaleBarelyMovesEstablishedConsensus() {
        BotMarketConsensus consensus = new BotMarketConsensus(null, null);
        List<Agent> sellers = List.of(new Agent(1, 700_000, 0, consensus));
        List<Agent> buyers = List.of(new Agent(2, 0, 1_400_000, consensus));
        long end = runStallRounds(sellers, buyers, consensus, 40, T0, new Random(11), null);

        double before = consensus.consensus(KEY);
        assertTrue(before > 0);
        MarketEvent gift = new MarketEvent(999_999, end + ROUND_MS, EventKind.TRADE,
                1082089, 0, 1, 1, 1, 2, null);
        consensus.sweep(List.of(gift), end + ROUND_MS);
        double after = consensus.consensus(KEY);
        assertTrue(Math.abs(after - before) / before < 0.15,
                "gift-price outlier is damped: " + before + " -> " + after);
    }

    // (b, sag half): cheap supply entering undercuts and drags clearings + consensus down,
    // but never below the entrants' own cost floor. (The recovery half needs S4 supply steering.)
    @Test
    void cheapSupplyDragsPricesDownButNotBelowCost() {
        BotMarketConsensus consensus = new BotMarketConsensus(null, null);
        List<Agent> sellers = new ArrayList<>(List.of(new Agent(1, 900_000, 0, consensus)));
        List<Agent> buyers = List.of(new Agent(2, 0, 1_500_000, consensus),
                new Agent(3, 0, 1_400_000, consensus));
        long mid = runStallRounds(sellers, buyers, consensus, 30, T0, new Random(5), null);
        double established = consensus.consensus(KEY);
        assertTrue(established > 0);

        // glut: three cheap producers open stalls alongside the incumbent
        for (int i = 0; i < 3; i++) {
            sellers.add(new Agent(10 + i, 420_000, 0, consensus));
        }
        List<Double> glutClearings = new ArrayList<>();
        runStallRounds(sellers, buyers, consensus, 60, mid, new Random(13), glutClearings);

        double afterGlut = consensus.consensus(KEY);
        assertTrue(afterGlut < established,
                "glut sags the price: " + established + " -> " + afterGlut);
        for (double px : glutClearings) {
            assertTrue(px >= 420_000, "never durably below the cheap producers' cost");
        }
    }

    private static double spread(List<Double> xs) {
        double min = Double.MAX_VALUE, max = 0;
        for (double x : xs) {
            min = Math.min(min, x);
            max = Math.max(max, x);
        }
        return max - min;
    }
}
