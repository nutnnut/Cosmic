package server.bots;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import server.bots.BotMarketGrammar.Offer;

/**
 * Map-scoped, in-memory feed of live market shouts (design sec 8.4). Deliberately NOT an order book:
 * no resting depth, no matching engine, no persistence — just a short-lived bulletin bots poll on
 * their AI tick and match socially. Players publish from packet threads and bots poll from tick
 * threads, so every touch of a map's feed is synchronized on that list. Entries lapse after a few
 * minutes and are swept lazily on the next publish/poll for that map.
 */
public final class BotMarketShoutBus {

    /** A live shout: who said it, what they offered, when it lapses. */
    public record Shout(int speakerId, Offer offer, long expiresAt) {}

    /** How long a shout stays live (minutes — design 8.4). Package-visible so tests can shorten it. */
    static long shoutTtlMs = 3 * 60_000L;
    /** Bulletin bound per map: oldest shout drops when a map fills up. */
    private static final int MAX_PER_MAP = 32;

    private static final BotMarketShoutBus INSTANCE = new BotMarketShoutBus();

    public static BotMarketShoutBus getInstance() {
        return INSTANCE;
    }

    private final Map<Integer, List<Shout>> byMap = new ConcurrentHashMap<>();

    private BotMarketShoutBus() {}

    /**
     * Post an offer to a map's feed, replacing any prior live shout from the same speaker for the
     * same item + kind (a re-shout updates rather than stacks) and dropping expired entries.
     */
    public void publish(int mapId, int speakerId, Offer offer, long now) {
        List<Shout> feed = byMap.computeIfAbsent(mapId, k -> new ArrayList<>());
        synchronized (feed) {
            feed.removeIf(s -> s.expiresAt() <= now
                    || (s.speakerId() == speakerId
                        && s.offer().kind() == offer.kind()
                        && s.offer().itemId() == offer.itemId()));
            if (feed.size() >= MAX_PER_MAP) {
                feed.remove(0); // bounded bulletin — oldest out
            }
            feed.add(new Shout(speakerId, offer, now + shoutTtlMs));
        }
    }

    /** Live (unexpired) shouts on a map, excluding the listener's own; sweeps expired lazily. */
    public List<Shout> active(int mapId, int listenerId, long now) {
        List<Shout> feed = byMap.get(mapId);
        if (feed == null) {
            return List.of();
        }
        List<Shout> out = new ArrayList<>();
        synchronized (feed) {
            for (Iterator<Shout> it = feed.iterator(); it.hasNext(); ) {
                Shout s = it.next();
                if (s.expiresAt() <= now) {
                    it.remove();
                } else if (s.speakerId() != listenerId) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** Test/debug: drop a speaker's shouts everywhere (e.g. on logout/despawn). */
    public void dropSpeaker(int speakerId) {
        for (List<Shout> feed : byMap.values()) {
            synchronized (feed) {
                feed.removeIf(s -> s.speakerId() == speakerId);
            }
        }
    }

    /** Test hook: wipe all feeds. */
    void clear() {
        byMap.clear();
    }
}
