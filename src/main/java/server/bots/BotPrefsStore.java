package server.bots;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Tiny persisted per-bot preference store. The table self-creates on first use (this fork
 * has no migration pipeline — bot_owners was created the same way), so toggles survive
 * relog and server restarts on any machine without a manual DB step.
 */
final class BotPrefsStore {
    private static final Logger log = LoggerFactory.getLogger(BotPrefsStore.class);
    private static volatile boolean tableReady;

    private BotPrefsStore() {
    }

    static void saveSelfScroll(int botCharId, boolean enabled) {
        try (Connection con = DatabaseConnection.getConnection()) {
            ensureTable(con);
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO bot_prefs (bot_char_id, self_scroll) VALUES (?, ?) "
                            + "ON DUPLICATE KEY UPDATE self_scroll = VALUES(self_scroll)")) {
                ps.setInt(1, botCharId);
                ps.setInt(2, enabled ? 1 : 0);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("Couldn't persist self_scroll for bot {}", botCharId, e);
        }
    }

    static boolean loadSelfScroll(int botCharId) {
        try (Connection con = DatabaseConnection.getConnection()) {
            ensureTable(con);
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT self_scroll FROM bot_prefs WHERE bot_char_id = ?")) {
                ps.setInt(1, botCharId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getInt(1) != 0;
                }
            }
        } catch (SQLException e) {
            log.warn("Couldn't load prefs for bot {}", botCharId, e);
            return false;
        }
    }

    private static void ensureTable(Connection con) throws SQLException {
        if (tableReady) {
            return;
        }
        try (PreparedStatement ps = con.prepareStatement(
                "CREATE TABLE IF NOT EXISTS bot_prefs ("
                        + "bot_char_id INT NOT NULL PRIMARY KEY, "
                        + "self_scroll TINYINT NOT NULL DEFAULT 0)")) {
            ps.executeUpdate();
        }
        tableReady = true;
    }
}
