package server.bots;

import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Per-bot persistent config store (scaffold for sub-feature 02-B).
 *
 * <p>Keyed by character id, backed by the {@code bot_config} table (see
 * {@code db/tables/026-bot-config.sql}). The single {@code config} TEXT column holds a forward-compat
 * blob (e.g. JSON) so future personality/behavior knobs (aggression, risk tolerance, chattiness,
 * jitter profile) can be added without a schema change. Follows the {@link BotOwnershipService}
 * DAO pattern (singleton, DatabaseConnection, ON DUPLICATE KEY upsert).
 *
 * <p>No personality knobs exist yet (no consumer): callers get the raw blob and defaults live in
 * {@code BotManager.cfg}. Load on spawn, save on change.
 */
public final class BotConfigService {
    private static final BotConfigService instance = new BotConfigService();

    public static BotConfigService getInstance() {
        return instance;
    }

    private BotConfigService() {
    }

    /** Returns the stored config blob for a bot, or {@code null} if no row exists (use code defaults). */
    public String load(int botCharId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT config FROM bot_config WHERE bot_char_id = ?")) {
            ps.setInt(1, botCharId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("config");
                }
            }
        } catch (SQLException e) {
            return null;
        }
        return null;
    }

    /** Upserts the config blob for a bot. */
    public void save(int botCharId, String config) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO bot_config (bot_char_id, config) VALUES (?, ?) "
                             + "ON DUPLICATE KEY UPDATE config = VALUES(config)")) {
            ps.setInt(1, botCharId);
            ps.setString(2, config);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save bot config", e);
        }
    }
}
