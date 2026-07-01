package server.bots;

import client.BotClient;
import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records, per (bot, real-player) pair, how many times they've partied and the total time spent
 * together — the data foundation for a future "greet a known player as a friend" system (no
 * greeting/dialogue change yet; consumers read {@link #familiarity}).
 *
 * <p><b>Presence-diff, not event hooks.</b> A periodic {@link #sample} scans live bots' parties and
 * diffs the set of currently-together (bot, human) pairs against the previous sample. A newly-seen
 * pair bumps {@code party_count}; an ongoing pair accrues elapsed {@code total_ms}; a vanished pair
 * flushes its last slice and is dropped. Because it samples live membership, it self-heals on a kick,
 * disband, or logout — the next sample just sees the changed party and ends the session — with no
 * party-event plumbing to miss. Backed by {@code bot_player_familiarity}
 * (db/tables/030-bot-familiarity.sql); follows the {@link BotConfigService} DAO pattern.
 *
 * <p>ponytail: per-pair UPDATE each sample (cheap at realistic pair counts). If concurrent
 * bot+player parties ever number in the thousands, batch the accrual writes.
 */
public final class BotFamiliarityManager {
    private static final Logger log = LoggerFactory.getLogger(BotFamiliarityManager.class);
    private static final BotFamiliarityManager instance = new BotFamiliarityManager();

    public static BotFamiliarityManager getInstance() {
        return instance;
    }

    /** packed (botCharId, playerCharId) -> when this togetherness session last accrued (ms). */
    private final Map<Long, Long> sessionStart = new ConcurrentHashMap<>();

    private BotFamiliarityManager() {}

    static long key(int botCharId, int playerCharId) {
        return ((long) botCharId << 32) | (playerCharId & 0xFFFFFFFFL);
    }

    private static int botOf(long k) {
        return (int) (k >> 32);
    }

    private static int playerOf(long k) {
        return (int) k;
    }

    /** Recorded familiarity between a bot and a player. */
    public record Familiarity(int partyCount, long totalMs) {
        static final Familiarity NONE = new Familiarity(0, 0L);
    }

    /**
     * One presence-diff pass. Registered on a periodic timer (see {@link BotScheduler#start}); the
     * sample interval only bounds the accrual granularity, so ~30s is plenty.
     */
    void sample(long now) {
        try {
            Set<Long> live = new HashSet<>();
            for (BotEntry e : BotManager.getInstance().allEntries()) {
                Character bot = e.bot;
                if (bot == null || bot.getParty() == null) {
                    continue;
                }
                for (Character m : bot.getPartyMembersOnline()) {
                    if (m == null || m.getId() == bot.getId() || m.getClient() instanceof BotClient) {
                        continue;
                    }
                    long k = key(bot.getId(), m.getId());
                    live.add(k);
                    if (sessionStart.putIfAbsent(k, now) == null) {
                        incrementCount(bot.getId(), m.getId(), now); // new togetherness session
                    }
                }
            }
            for (Iterator<Map.Entry<Long, Long>> it = sessionStart.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Long, Long> en = it.next();
                long k = en.getKey();
                long delta = now - en.getValue();
                if (delta > 0) {
                    accrue(botOf(k), playerOf(k), delta, now);
                }
                if (live.contains(k)) {
                    en.setValue(now); // flush slice, keep running
                } else {
                    it.remove();       // session ended (kick/disband/logout) — already flushed
                }
            }
        } catch (RuntimeException ex) {
            log.warn("bot familiarity sample failed", ex);
        }
    }

    private void incrementCount(int botCharId, int playerCharId, long now) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO bot_player_familiarity "
                             + "(bot_char_id, player_char_id, party_count, total_ms, last_together_at) "
                             + "VALUES (?, ?, 1, 0, ?) "
                             + "ON DUPLICATE KEY UPDATE party_count = party_count + 1, "
                             + "last_together_at = VALUES(last_together_at)")) {
            ps.setInt(1, botCharId);
            ps.setInt(2, playerCharId);
            ps.setTimestamp(3, new Timestamp(now));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("familiarity count update failed", e);
        }
    }

    private void accrue(int botCharId, int playerCharId, long deltaMs, long now) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO bot_player_familiarity "
                             + "(bot_char_id, player_char_id, party_count, total_ms, last_together_at) "
                             + "VALUES (?, ?, 0, ?, ?) "
                             + "ON DUPLICATE KEY UPDATE total_ms = total_ms + VALUES(total_ms), "
                             + "last_together_at = VALUES(last_together_at)")) {
            ps.setInt(1, botCharId);
            ps.setInt(2, playerCharId);
            ps.setLong(3, deltaMs);
            ps.setTimestamp(4, new Timestamp(now));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("familiarity accrual failed", e);
        }
    }

    /** How well a bot knows a player (zeros if they've never partied). For the future greeting system. */
    public Familiarity familiarity(int botCharId, int playerCharId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT party_count, total_ms FROM bot_player_familiarity "
                             + "WHERE bot_char_id = ? AND player_char_id = ?")) {
            ps.setInt(1, botCharId);
            ps.setInt(2, playerCharId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Familiarity(rs.getInt("party_count"), rs.getLong("total_ms"));
                }
            }
        } catch (SQLException e) {
            log.warn("familiarity load failed", e);
        }
        return Familiarity.NONE;
    }
}
