package server.bots;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import server.bots.BotMarketGrammar.Kind;
import server.bots.BotMarketGrammar.Offer;
import server.bots.BotMarketShoutBus.Shout;

/** Behaviour of the map-scoped shout bus: scoping, self-exclusion, TTL expiry, re-shout replace,
 *  speaker drop. Time is injected via the {@code now} params so no clock is needed (design 8.4). */
class BotMarketShoutBusTest {

    private static final int MAP = 910000000;
    private final BotMarketShoutBus bus = BotMarketShoutBus.getInstance();

    private static Offer sell(int itemId, int price) {
        return new Offer(Kind.SELL, itemId, 1, price);
    }

    @BeforeEach
    void wipe() {
        bus.clear();
    }

    @Test
    void publishThenActiveReturnsIt() {
        bus.publish(MAP, 100, sell(1082002, 1_000_000), 0L);
        List<Shout> live = bus.active(MAP, 999, 1_000L);
        assertEquals(1, live.size());
        assertEquals(100, live.get(0).speakerId());
        assertEquals(1082002, live.get(0).offer().itemId());
    }

    @Test
    void activeExcludesListenersOwnShouts() {
        bus.publish(MAP, 100, sell(1082002, 1_000_000), 0L);
        bus.publish(MAP, 200, sell(1332006, 300_000), 0L);
        List<Shout> forHundred = bus.active(MAP, 100, 1_000L);
        assertEquals(1, forHundred.size());
        assertEquals(200, forHundred.get(0).speakerId());
    }

    @Test
    void shoutsAreMapScoped() {
        bus.publish(MAP, 100, sell(1082002, 1_000_000), 0L);
        assertTrue(bus.active(MAP + 1, 999, 1_000L).isEmpty());
    }

    @Test
    void expiresAfterTtl() {
        bus.publish(MAP, 100, sell(1082002, 1_000_000), 0L);
        long afterTtl = BotMarketShoutBus.shoutTtlMs + 1;
        assertTrue(bus.active(MAP, 999, afterTtl).isEmpty());
    }

    @Test
    void reShoutReplacesSameSpeakerSameItem() {
        bus.publish(MAP, 100, sell(1082002, 1_000_000), 0L);
        bus.publish(MAP, 100, sell(1082002, 800_000), 1_000L); // same speaker + item, new price
        List<Shout> live = bus.active(MAP, 999, 2_000L);
        assertEquals(1, live.size());
        assertEquals(800_000, live.get(0).offer().priceMeso());
    }

    @Test
    void dropSpeakerRemovesTheirShouts() {
        bus.publish(MAP, 100, sell(1082002, 1_000_000), 0L);
        bus.publish(MAP, 200, sell(1332006, 300_000), 0L);
        bus.dropSpeaker(100);
        List<Shout> live = bus.active(MAP, 999, 1_000L);
        assertEquals(1, live.size());
        assertEquals(200, live.get(0).speakerId());
    }
}
