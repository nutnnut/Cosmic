package server.bots;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BotMarketBook is touched off the owning bot's own tick thread in practice: HiredMerchant.buy
 * observes the SELLER's book from whatever thread processes the buyer's purchase (BotManager
 * .notifyStallSale), and BotFreeMarketManager's FM listing plan reads a book from
 * BotGrindAdvisor.DECIDE_POOL while the owning bot's tick thread may concurrently observe/flush the
 * same book. This exercises that concurrent access no longer throws or silently corrupts state.
 */
class BotMarketBookTest {

    private static final long KEY = BotMarketMath.priceKey(1082089, 0);

    private static final BotMarketBook.ConsensusSource NO_CONSENSUS = new BotMarketBook.ConsensusSource() {
        public double consensus(long priceKey) {
            return 0;
        }

        public double volume(long priceKey) {
            return 0;
        }
    };

    @Test
    void asksAnchorToConsensusThenRegainPrivateInfluenceAsOwnClearingsAccrue() {
        final double C = 16_000_000, V = 200; // a solid clearing consensus (Dragon Toenail band 0)
        BotMarketBook.ConsensusSource consensus = new BotMarketBook.ConsensusSource() {
            public double consensus(long k) { return C; }
            public double volume(long k) { return V; }
        };
        BotMarketBook book = new BotMarketBook(7, 1.0, consensus); // fully informed -> no perception noise
        long now = 1_000_000_000L;
        double hugeAnchor = 500_000_000, salvage = 180_000;

        // No private belief: the ask base anchors to the 16m consensus, NOT the 500m reproduction
        // anchor (perceivedConfidence carries the full consensus mass, so askBase trusts the price).
        double price = book.perceivedPrice(KEY, now);
        double conf = book.perceivedConfidence(KEY, now);
        assertEquals(C, price, 1, "no private belief -> perceived price is the consensus");
        assertEquals(V, conf, 1e-9, "confidence includes the full consensus mass");
        double baseNoBelief = BotMarketMath.askBase(price, conf, hugeAnchor, salvage);
        assertTrue(baseNoBelief < 20_000_000, "ask anchors near the 16m consensus, not the 500m anchor");

        // The bot logs its OWN clearings above consensus: its private belief regains influence.
        for (int i = 0; i < 20; i++) {
            book.observe(KEY, 30_000_000, BotMarketMath.W_TRADE, now);
        }
        double base2 = BotMarketMath.askBase(
                book.perceivedPrice(KEY, now), book.perceivedConfidence(KEY, now), hugeAnchor, salvage);
        assertTrue(base2 > baseNoBelief, "own clearings pull the bot's ask toward its individual view");
        assertTrue(base2 < 30_000_000, "but it stays blended with the market consensus");
    }

    @Test
    void concurrentObserveAndReadNeverThrowsOrCorruptsTheBook() throws InterruptedException {
        BotMarketBook book = new BotMarketBook(1, 0.5, NO_CONSENSUS);
        int threads = 8;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        long now = System.currentTimeMillis();
                        book.observe(KEY, 1_000_000, BotMarketMath.W_ASK, now); // writer (e.g. notifyStallSale)
                        book.perceivedPrice(KEY, now);                          // reader (e.g. DECIDE_POOL plan)
                        book.privateConfidence(KEY, now);
                        book.drainDirty();                                     // flusher (maybeFlush)
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "the race should finish promptly");
        assertEquals(0, failures.get(), "no exception from racing observe/read/drain on one key");
        assertEquals(1, book.trackedKeys(), "one key stays one key under the race - no map corruption");
        assertTrue(book.privateConfidence(KEY, System.currentTimeMillis()) > 0,
                "observations actually landed, none silently lost to the race");
    }
}
