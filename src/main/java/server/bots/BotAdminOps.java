package server.bots;

import client.Character;
import client.CharacterDeletionService;
import client.Job;
import net.server.Server;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * SSOT for destructive managed-bot admin ops shared by the {@code @botpop} GM command and the
 * {@code /api/settings} web admin menu. Today: the population wipe (delete every managed bot).
 *
 * <p>Scope guard preserved: a bot on an account holding OTHER characters is a shared/real account a
 * char was {@code @botpop add}-ed to — it is skipped, never deleted.
 */
public final class BotAdminOps {
    private BotAdminOps() {}

    /** One managed-bot row for the wipe preview/summary. */
    public record BotRow(int cid, String name, int level, int jobId) {}

    /** Result of a wipe: counts plus human-readable lines (preview roster or per-skip reasons). */
    public record WipeResult(int wiped, int skipped, List<String> lines) {}

    /** The managed-bot roster, level low->high — for the {@code @botpop wipe} preview (no deletion). */
    public static List<BotRow> roster() {
        List<ManagedBotService.ManagedBot> managed = ManagedBotService.getInstance().loadAll();
        List<Integer> ids = new ArrayList<>();
        for (ManagedBotService.ManagedBot mb : managed) {
            ids.add(mb.botCharId());
        }
        List<BotRow> roster = loadRoster(ids);
        roster.sort(Comparator.comparingInt(BotRow::level));
        return roster;
    }

    /**
     * Permanently delete EVERY managed bot via the SSOT deletion path ({@link CharacterDeletionService}),
     * removing each now-empty bot account. Real/shared accounts are skipped. Returns counts + per-skip lines.
     */
    public static WipeResult wipeManagedBots() {
        List<BotRow> roster = roster();
        List<String> lines = new ArrayList<>();
        BotManager bm = BotManager.getInstance();
        int wiped = 0, skipped = 0;
        for (BotRow r : roster) {
            // Scope guard: a generated bot has its own single-char account. An account with other
            // characters is a shared/real account a char was @botpop-added to — never delete that char.
            Integer accId = accountIdOf(r.cid()); // read BEFORE delete removes the characters row
            if (accId == null || charCountOnAccount(accId) != 1) {
                skipped++;
                lines.add("skip " + r.name() + ": account has other characters (not a dedicated bot account)");
                continue;
            }
            bm.removeBotByCharId(r.cid()); // stop the bot AI tick before deleting underneath it
            Character online = findOnline(r.cid());
            if (online != null && online.getClient() != null) {
                online.getClient().disconnect(false, false); // leave the world (and final-save) before the DB delete
            }
            // Delete as the bot's OWN account: validateCharacterOwnership requires senderAccId to own the char.
            CharacterDeletionService.Result res = CharacterDeletionService.deleteCharacter(r.cid(), accId);
            if (!res.isSuccess()) {
                skipped++;
                lines.add("skip " + r.name() + ": " + res.getCommandMessage());
                continue;
            }
            deleteAccountIfEmpty(accId); // single-char account is now empty -> remove it
            wiped++;
        }
        return new WipeResult(wiped, skipped, lines);
    }

    /** "Lv12 Name (BANDIT)" line for a roster row. */
    public static String describe(BotRow r) {
        return "Lv" + r.level() + "  " + r.name() + "  (" + Job.getById(r.jobId()) + ")";
    }

    private static Character findOnline(int cid) {
        for (var world : Server.getInstance().getWorlds()) {
            Character online = world.getPlayerStorage().getCharacterById(cid);
            if (online != null) {
                return online;
            }
        }
        return null;
    }

    /** Batch-load name/level/job for the managed-bot ids; falls back to bare ids if the lookup fails. */
    private static List<BotRow> loadRoster(List<Integer> ids) {
        List<BotRow> out = new ArrayList<>();
        if (ids.isEmpty()) {
            return out;
        }
        String placeholders = "?,".repeat(ids.size());
        placeholders = placeholders.substring(0, placeholders.length() - 1);
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT id, name, level, job FROM characters WHERE id IN (" + placeholders + ")")) {
            for (int i = 0; i < ids.size(); i++) {
                ps.setInt(i + 1, ids.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new BotRow(rs.getInt("id"), rs.getString("name"), rs.getInt("level"), rs.getInt("job")));
                }
            }
        } catch (SQLException e) {
            for (int id : ids) {
                out.add(new BotRow(id, "cid" + id, 0, 0));
            }
        }
        return out;
    }

    private static Integer accountIdOf(int cid) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT accountid FROM characters WHERE id = ?")) {
            ps.setInt(1, cid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    /** Number of characters on the account; -1 if the lookup fails (treated as unsafe by callers). */
    private static int charCountOnAccount(int accId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) AS n FROM characters WHERE accountid = ?")) {
            ps.setInt(1, accId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("n") : 0;
            }
        } catch (SQLException e) {
            return -1;
        }
    }

    /** Delete the account only if it has no characters left — guards against nuking a shared/real account. */
    private static void deleteAccountIfEmpty(int accId) {
        if (charCountOnAccount(accId) != 0) {
            return; // leaving a non-empty account row alone; harmless if the count lookup failed
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement del = con.prepareStatement("DELETE FROM accounts WHERE id = ?")) {
            del.setInt(1, accId);
            del.executeUpdate();
        } catch (SQLException ignored) {
            // leaving an empty account row is harmless; the char/inventory cleanup already succeeded
        }
    }
}
