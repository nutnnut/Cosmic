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
