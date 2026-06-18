package server.bots;

import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Registry of SERVER-GENERATED, schedulable bots — the "living server" population (table
 * {@code managed_bot}, see {@code db/tables/027-managed-bot.sql}). Mirrors the {@link BotConfigService}
 * / {@link BotOwnershipService} DAO pattern (singleton, {@link DatabaseConnection}, prepared statements).
 *
 * <p><b>Safety SSOT:</b> the population scheduler ({@code BotScheduler}) may auto log in/out, retire,
 * and replace <em>only</em> characters that have a row here. Rows are created exclusively by the
 * bot-generation path ({@code @spawnbot generate} / the auto-generator) — a real player's character
 * ({@code @registerbot}, {@code @botme}) never gets one, so it can never be auto-scheduled.
 *
 * <p>Personality lives separately in {@code bot_config} ({@link BotConfigService}); this table is the
 * lean, queryable index the scheduler scans.
 */
public final class ManagedBotService {
    private static final ManagedBotService instance = new ManagedBotService();

    public static ManagedBotService getInstance() {
        return instance;
    }

    private ManagedBotService() {
    }

    /** One managed-bot registry row. {@code groupId} is null for soloists; {@code retired} = career ended;
     *  {@code createdAtMs} is the career start (epoch millis) used for turnover age. */
    public record ManagedBot(int botCharId, Integer groupId, boolean enabled, boolean retired, long createdAtMs) {
        /** Schedulable = a generated bot that is enabled and hasn't retired. */
        public boolean schedulable() {
            return enabled && !retired;
        }

        /** Career age in whole days as of {@code now} (epoch millis). */
        public long ageDays(long now) {
            return Math.max(0, (now - createdAtMs) / 86_400_000L);
        }
    }

    /** True iff this character is a server-generated managed bot (the gate the scheduler must honor). */
    public boolean isManaged(int botCharId) {
        return get(botCharId) != null;
    }

    public ManagedBot get(int botCharId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT group_id, enabled, retired_at, created_at FROM managed_bot WHERE bot_char_id = ?")) {
            ps.setInt(1, botCharId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return readRow(botCharId, rs);
                }
            }
        } catch (SQLException e) {
            return null;
        }
        return null;
    }

    /** All registered managed bots (the scheduler filters to {@link ManagedBot#schedulable()} itself). */
    public List<ManagedBot> loadAll() {
        List<ManagedBot> out = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT bot_char_id, group_id, enabled, retired_at, created_at FROM managed_bot");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(readRow(rs.getInt("bot_char_id"), rs));
            }
        } catch (SQLException e) {
            return out;
        }
        return out;
    }

    private static ManagedBot readRow(int botCharId, ResultSet rs) throws SQLException {
        int gid = rs.getInt("group_id");
        Integer groupId = rs.wasNull() ? null : gid;
        boolean enabled = rs.getInt("enabled") != 0;
        boolean retired = rs.getTimestamp("retired_at") != null;
        java.sql.Timestamp created = rs.getTimestamp("created_at");
        long createdAtMs = created != null ? created.getTime() : 0L;
        return new ManagedBot(botCharId, groupId, enabled, retired, createdAtMs);
    }

    /** Marks a freshly-generated character as a managed bot. Idempotent (keeps the existing row). */
    public void insert(int botCharId, Integer groupId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO managed_bot (bot_char_id, group_id) VALUES (?, ?) "
                             + "ON DUPLICATE KEY UPDATE group_id = VALUES(group_id)")) {
            ps.setInt(1, botCharId);
            if (groupId == null) {
                ps.setNull(2, java.sql.Types.INTEGER);
            } else {
                ps.setInt(2, groupId);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to register managed bot " + botCharId, e);
        }
    }

    /** Assigns (or clears, with {@code null}) the persistent-crew group of a managed bot. Crews log in
     *  together and share items like an owned party — see {@code BotScheduler} / the crew share cohort. */
    public void setGroup(int botCharId, Integer groupId) {
        update("UPDATE managed_bot SET group_id = ? WHERE bot_char_id = ?", ps -> {
            if (groupId == null) {
                ps.setNull(1, java.sql.Types.INTEGER);
            } else {
                ps.setInt(1, groupId);
            }
            ps.setInt(2, botCharId);
        });
    }

    /** Char ids of the non-retired members of a crew (group), for co-spawn + the crew share cohort. */
    public List<Integer> crewMembers(int groupId) {
        List<Integer> out = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT bot_char_id FROM managed_bot WHERE group_id = ? AND retired_at IS NULL")) {
            ps.setInt(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getInt("bot_char_id"));
                }
            }
        } catch (SQLException e) {
            return out;
        }
        return out;
    }

    public void setEnabled(int botCharId, boolean enabled) {
        update("UPDATE managed_bot SET enabled = ? WHERE bot_char_id = ?", ps -> {
            ps.setInt(1, enabled ? 1 : 0);
            ps.setInt(2, botCharId);
        });
    }

    /** Ends the bot's career ("left") — kept as a record but no longer scheduled. */
    public void retire(int botCharId) {
        update("UPDATE managed_bot SET retired_at = CURRENT_TIMESTAMP WHERE bot_char_id = ?",
                ps -> ps.setInt(1, botCharId));
    }

    /** Drops the managed-bot row entirely (un-manage): the character stops being scheduled and is no
     *  longer in the population. Unlike {@link #retire} this leaves no record — used by the admin
     *  add/remove command, not career turnover. */
    public void remove(int botCharId) {
        update("DELETE FROM managed_bot WHERE bot_char_id = ?", ps -> ps.setInt(1, botCharId));
    }

    /** Records that the scheduler just brought this bot online. */
    public void touchOnline(int botCharId) {
        update("UPDATE managed_bot SET last_online_at = CURRENT_TIMESTAMP WHERE bot_char_id = ?",
                ps -> ps.setInt(1, botCharId));
    }

    private interface StatementBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private void update(String sql, StatementBinder binder) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed managed_bot update: " + sql, e);
        }
    }
}
