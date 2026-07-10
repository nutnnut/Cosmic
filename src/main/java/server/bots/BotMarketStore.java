package server.bots;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistence for the two belief layers (tables {@code bot_market_belief} /
 * {@code bot_market_consensus}, docs/bot/economy.md). Same DAO pattern as
 * {@link ManagedBotService}. Prices survive restarts; no ledger replay needed at boot.
 */
public final class BotMarketStore {
    private static final Logger log = LoggerFactory.getLogger(BotMarketStore.class);
    private static final BotMarketStore instance = new BotMarketStore();

    /** Private-book LRU bound per bot (resource clamp, not price policy — design sec 4). */
    static final int BELIEFS_PER_BOT_CAP = 512;

    public static BotMarketStore getInstance() {
        return instance;
    }

    private BotMarketStore() {
    }

    /** One persisted private-belief row. */
    public record StoredBelief(long priceKey, long estimate, double confidence, int obs, long lastSeenMs) {
    }

    /** One persisted consensus row; {@code volume} is the decayed damping mass. */
    public record StoredConsensus(long priceKey, long consensus, double volume, long updatedAtMs) {
    }

    // ------------------------------------------------------------------ private books

    /** Most recent {@link #BELIEFS_PER_BOT_CAP} beliefs of a bot, freshest first (LRU applied here). */
    public Map<Long, StoredBelief> loadBeliefs(int botCharId) {
        Map<Long, StoredBelief> out = new LinkedHashMap<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT price_key, estimate, confidence, obs, last_seen FROM bot_market_belief"
                             + " WHERE bot_char_id = ? ORDER BY last_seen DESC LIMIT " + BELIEFS_PER_BOT_CAP)) {
            ps.setInt(1, botCharId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    StoredBelief b = new StoredBelief(
                            rs.getLong("price_key"),
                            rs.getLong("estimate"),
                            rs.getFloat("confidence"),
                            rs.getInt("obs"),
                            rs.getTimestamp("last_seen").getTime());
                    out.put(b.priceKey(), b);
                }
            }
        } catch (SQLException e) {
            log.warn("bot_market_belief load failed for {}: {}", botCharId, e.toString());
        }
        return out;
    }

    /** Batch upsert of a bot's dirty beliefs. */
    public void saveBeliefs(int botCharId, Collection<StoredBelief> beliefs) {
        if (beliefs.isEmpty()) {
            return;
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO bot_market_belief (bot_char_id, price_key, estimate, confidence, obs, last_seen)"
                             + " VALUES (?, ?, ?, ?, ?, ?)"
                             + " ON DUPLICATE KEY UPDATE estimate = VALUES(estimate), confidence = VALUES(confidence),"
                             + " obs = VALUES(obs), last_seen = VALUES(last_seen)")) {
            for (StoredBelief b : beliefs) {
                ps.setInt(1, botCharId);
                ps.setLong(2, b.priceKey());
                ps.setLong(3, b.estimate());
                ps.setDouble(4, b.confidence());
                ps.setInt(5, b.obs());
                ps.setTimestamp(6, new Timestamp(b.lastSeenMs()));
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            log.warn("bot_market_belief save failed for {}: {}", botCharId, e.toString());
        }
    }

    // ------------------------------------------------------------------ consensus

    /** All consensus rows (the sweep holds them in memory; table stays small: one per active key). */
    public Map<Long, StoredConsensus> loadConsensus() {
        Map<Long, StoredConsensus> out = new LinkedHashMap<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT price_key, consensus, volume, updated_at FROM bot_market_consensus");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                StoredConsensus c = new StoredConsensus(
                        rs.getLong("price_key"),
                        rs.getLong("consensus"),
                        rs.getFloat("volume"),
                        rs.getTimestamp("updated_at").getTime());
                out.put(c.priceKey(), c);
            }
        } catch (SQLException e) {
            log.warn("bot_market_consensus load failed: {}", e.toString());
        }
        return out;
    }

    /** Batch upsert of consensus rows the sweep moved. */
    public void saveConsensus(Collection<StoredConsensus> rows) {
        if (rows.isEmpty()) {
            return;
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO bot_market_consensus (price_key, consensus, volume, updated_at)"
                             + " VALUES (?, ?, ?, ?)"
                             + " ON DUPLICATE KEY UPDATE consensus = VALUES(consensus), volume = VALUES(volume),"
                             + " updated_at = VALUES(updated_at)")) {
            for (StoredConsensus c : rows) {
                ps.setLong(1, c.priceKey());
                ps.setLong(2, c.consensus());
                ps.setDouble(3, c.volume());
                ps.setTimestamp(4, new Timestamp(c.updatedAtMs()));
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            log.warn("bot_market_consensus save failed: {}", e.toString());
        }
    }
}
