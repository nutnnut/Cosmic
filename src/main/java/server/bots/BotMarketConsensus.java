package server.bots;

import server.bots.BotMarketLedger.EventKind;
import server.bots.BotMarketLedger.MarketEvent;
import server.bots.BotMarketMath.Belief;
import server.bots.BotMarketMath.PricePoint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;

/**
 * Layer 1 of the belief model (docs/bot/economy.md): the shared, damped,
 * robust per-item price statistic — the market's ambient "everyone roughly knows what this
 * costs". Computed by a periodic sweep over the ledger's recent window; never a price setter,
 * never read raw by bot decisions (only through BotMarketBook's noisy perception).
 *
 * <p>The sweep core ({@link #sweep(List, long)}) is pure over an event list for testability; the
 * production singleton glues it to {@link BotMarketLedger} + {@link BotMarketStore}. Scheduling
 * the sweep on a timer lands with the first execution slice (S2) — nothing flows until then.
 */
public final class BotMarketConsensus implements BotMarketBook.ConsensusSource {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BotMarketConsensus.class);

    /** Sweep input window (structural bound: how far back "recent" reaches). */
    static final long WINDOW_MS = 7L * 24 * 60 * 60 * 1000;

    /** Minutes-scale sweep cadence (design sec 4 layer 1) — off the hot path, cheap when idle. */
    static final long SWEEP_INTERVAL_MS = 180_000;

    /** Register the periodic ledger sweep (called once from Server boot, BotScheduler-style). */
    public static void startSweeping() {
        server.TimerManager.getInstance().register(() -> {
            try {
                getInstance().sweepFromLedger(System.currentTimeMillis());
            } catch (RuntimeException e) {
                log.warn("market consensus sweep failed: {}", e.toString());
            }
        }, SWEEP_INTERVAL_MS);
    }

    private static final BotMarketConsensus instance =
            new BotMarketConsensus(BotMarketLedger.getInstance(), BotMarketStore.getInstance());

    static BotMarketConsensus getInstance() {
        return instance;
    }

    private final BotMarketLedger ledger;
    private final BotMarketStore store;

    private final Map<Long, Belief> byKey = new ConcurrentHashMap<>();
    private final Map<Long, Long> updatedAtMs = new ConcurrentHashMap<>();
    private volatile boolean loaded;
    private volatile long lastSweepMs;

    private record WeightedEvidence(double median, double totalWeight, long halfLifeMs) {}

    /** Package ctor for tests (pass null ledger/store and drive {@link #sweep} directly). */
    BotMarketConsensus(BotMarketLedger ledger, BotMarketStore store) {
        this.ledger = ledger;
        this.store = store;
    }

    // ------------------------------------------------------------------ ConsensusSource

    @Override
    public double consensus(long priceKey) {
        ensureLoaded();
        Belief b = byKey.get(priceKey);
        return b == null ? 0 : b.estimate();
    }

    @Override
    public double volume(long priceKey) {
        ensureLoaded();
        Belief b = byKey.get(priceKey);
        return b == null ? 0 : b.confidence();
    }

    // ------------------------------------------------------------------ sweep

    /** Production sweep: pull the recent ledger window, fold it in, persist moved rows. */
    void sweepFromLedger(long nowMs) {
        ensureLoaded();
        long since = Math.max(nowMs - WINDOW_MS, lastSweepMs - 60_000); // small overlap, no gaps
        List<MarketEvent> window = ledger.recentEvents(since, false);
        List<Long> moved = sweep(window, nowMs);
        lastSweepMs = nowMs;
        if (!moved.isEmpty() && store != null) {
            List<BotMarketStore.StoredConsensus> rows = new ArrayList<>(moved.size());
            for (long key : moved) {
                Belief b = byKey.get(key);
                if (b != null) {
                    rows.add(new BotMarketStore.StoredConsensus(
                            key, Math.round(b.estimate()), b.confidence(), nowMs));
                }
            }
            store.saveConsensus(rows);
        }
    }

    /**
     * Pure sweep core: fold a window of events into the per-key statistics. Per key —
     * decay the standing volume for elapsed silence (half-life from the key's own event
     * spacing), build the recency-weighted median of realized clearing prices, then take one
     * volume-damped step toward it. Exposed unsold supply and unusually fast sales then apply
     * directional pressure to an established price. Listing and shout asks remain ledger/audit data,
     * never shared price evidence: letting an advertisement seed consensus creates a self-reinforcing
     * rumor loop. Returns the keys that moved.
     */
    List<Long> sweep(List<MarketEvent> window, long nowMs) {
        Map<Long, List<MarketEvent>> clearingByKey = new HashMap<>();
        Map<Long, List<MarketEvent>> unsoldByKey = new HashMap<>();
        Map<Long, List<MarketEvent>> fastSaleByKey = new HashMap<>();
        for (MarketEvent e : window) {
            if (e.unitPrice() <= 0) {
                continue;
            }
            if (e.kind().isClearing()) {
                clearingByKey.computeIfAbsent(e.priceKey(), k -> new ArrayList<>()).add(e);
            } else if (e.kind() == EventKind.UNSOLD) {
                unsoldByKey.computeIfAbsent(e.priceKey(), k -> new ArrayList<>()).add(e);
            } else if (e.kind() == EventKind.SOLD_FAST) {
                fastSaleByKey.computeIfAbsent(e.priceKey(), k -> new ArrayList<>()).add(e);
            }
        }

        List<Long> moved = new ArrayList<>();
        for (Map.Entry<Long, List<MarketEvent>> entry : clearingByKey.entrySet()) {
            long key = entry.getKey();
            List<MarketEvent> evidence = entry.getValue();
            Belief current = byKey.getOrDefault(key, Belief.NONE);

            WeightedEvidence aggregate = weightedEvidence(evidence, nowMs,
                    MarketEvent::unitPrice, ignored -> BotMarketMath.W_TRADE);
            if (aggregate.median() <= 0 || aggregate.totalWeight() <= 0) {
                continue;
            }

            long updatedAt = updatedAtMs.getOrDefault(key, 0L);
            if (!current.isEmpty() && updatedAt > 0) {
                current = BotMarketMath.decay(current, Math.max(0, nowMs - updatedAt),
                        aggregate.halfLifeMs());
            }
            Belief next = BotMarketMath.moveConsensus(current, aggregate.median(), aggregate.totalWeight());
            byKey.put(key, next);
            updatedAtMs.put(key, nowMs);
            moved.add(key);
        }
        applyDirectionalOutcomes(unsoldByKey, nowMs, false, moved);
        applyDirectionalOutcomes(fastSaleByKey, nowMs, true, moved);
        return moved;
    }

    /** Fold exposed-supply/demand outcomes after clearings. They are directional bounds, not trades:
     *  an unsold repriced ask may only lower an established consensus, while a quick sale may only
     *  raise it. Multiple outcomes increase {@code totalWeight}, making larger pressure move farther. */
    private void applyDirectionalOutcomes(Map<Long, List<MarketEvent>> byOutcomeKey, long nowMs,
                                          boolean upward, List<Long> moved) {
        for (Map.Entry<Long, List<MarketEvent>> entry : byOutcomeKey.entrySet()) {
            long key = entry.getKey();
            Belief current = byKey.getOrDefault(key, Belief.NONE);
            if (current.isEmpty()) {
                continue; // a directional bound cannot invent a price for a market that never cleared
            }

            WeightedEvidence aggregate = weightedEvidence(entry.getValue(), nowMs,
                    e -> upward ? e.unitPrice() * (1.0 + BotMarketMath.FAST_SALE_PROBE_UP)
                            : e.unitPrice(),
                    e -> BotMarketMath.W_OUTCOME * BotMarketMath.outcomeQuantityWeight(e.qty()));
            double target = aggregate.median();
            double boundary = current.estimate() * (upward
                    ? 1.0 + BotMarketMath.OUTCOME_DEADBAND
                    : 1.0 - BotMarketMath.OUTCOME_DEADBAND);
            if (target <= 0 || aggregate.totalWeight() <= 0
                    || (upward ? target <= boundary : target >= boundary)) {
                continue;
            }

            long updatedAt = updatedAtMs.getOrDefault(key, 0L);
            if (updatedAt > 0) {
                current = BotMarketMath.decay(current, Math.max(0, nowMs - updatedAt),
                        aggregate.halfLifeMs());
            }
            byKey.put(key, BotMarketMath.moveConsensus(current, target, aggregate.totalWeight()));
            updatedAtMs.put(key, nowMs);
            if (!moved.contains(key)) {
                moved.add(key);
            }
        }
    }

    private static WeightedEvidence weightedEvidence(List<MarketEvent> evidence, long nowMs,
                                                      ToDoubleFunction<MarketEvent> signal,
                                                      ToDoubleFunction<MarketEvent> sourceWeight) {
        List<Long> stamps = new ArrayList<>(evidence.size());
        for (MarketEvent e : evidence) {
            stamps.add(e.atMs());
        }
        stamps.sort(Long::compare);
        long halfLife = BotMarketMath.halfLifeMs(BotMarketMath.medianInterEventGapMs(stamps));

        List<PricePoint> points = new ArrayList<>(evidence.size());
        double totalWeight = 0;
        for (MarketEvent e : evidence) {
            double recency = Math.pow(0.5, (double) Math.max(0, nowMs - e.atMs()) / halfLife);
            double weight = sourceWeight.applyAsDouble(e) * recency;
            points.add(new PricePoint(signal.applyAsDouble(e), weight));
            totalWeight += weight;
        }
        return new WeightedEvidence(BotMarketMath.weightedMedian(points), totalWeight, halfLife);
    }

    /** Full in-memory + persisted reset (test-server reseed endpoint). Marks the store loaded so
     *  nothing re-reads the just-cleared table, and advances the sweep cursor so a pre-reset tape
     *  window can never be re-folded. */
    void resetAll(long nowMs) {
        byKey.clear();
        updatedAtMs.clear();
        loaded = true;
        lastSweepMs = nowMs;
        if (store != null) {
            store.clearConsensus();
        }
    }

    private void ensureLoaded() {
        if (loaded || store == null) {
            loaded = true;
            return;
        }
        synchronized (this) {
            if (loaded) {
                return;
            }
            for (BotMarketStore.StoredConsensus c : store.loadConsensus().values()) {
                byKey.put(c.priceKey(), new Belief(c.consensus(), c.volume()));
                updatedAtMs.put(c.priceKey(), c.updatedAtMs());
            }
            loaded = true;
        }
    }
}
