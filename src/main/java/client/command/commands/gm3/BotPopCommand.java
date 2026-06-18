package client.command.commands.gm3;

import client.Character;
import client.CharacterDeletionService;
import client.Client;
import client.Job;
import client.command.Command;
import server.bots.BotManager;
import server.bots.BotOwnershipService;
import server.bots.BotScheduler;
import server.bots.ManagedBotService;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Drive/inspect the living-server bot population scheduler (default OFF).
 * Usage: {@code @botpop [status|on|off|list|sweep|add <name>|remove <name>|crew <id|none> <name...>]}
 * (no arg = status).
 *
 * <p>{@code add}/{@code remove} mark an EXISTING character as a managed (schedulable) bot or unmark it
 * — the only way besides {@code @spawnbot generate} to put a character in the population. GM-explicit,
 * so it doesn't violate the "never auto-schedule a real player" rule.
 *
 * <p>{@code crew <id> <name...>} assigns managed bots to a persistent crew (group): the scheduler logs
 * a crew in together and parties them, and crewmates share gear/ammo/supplies like an owned party.
 * {@code crew none <name...>} clears the assignment (back to soloist / dynamic party-up).
 */
public class BotPopCommand extends Command {
    {
        setDescription("Living-server bot population: status / on / off / list / sweep / clear / add <name> / remove <name> / crew <id|none> <name...> / wipe [confirm].");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (player == null) {
            return;
        }
        BotScheduler scheduler = BotScheduler.getInstance();
        String verb = params.length >= 1 ? params[0].toLowerCase() : "status";
        switch (verb) {
            case "on" -> {
                scheduler.setEnabled(true);
                player.yellowMessage("Bot population scheduler ENABLED.");
                print(player, scheduler.statusLines());
            }
            case "off" -> {
                scheduler.setEnabled(false);
                player.yellowMessage("Bot population scheduler DISABLED.");
            }
            case "list" -> {
                List<String> lines = scheduler.listLines();
                if (lines.isEmpty()) {
                    player.yellowMessage("No managed bots. Create some with: @spawnbot generate confirm");
                } else {
                    player.yellowMessage("Managed bots (" + lines.size() + "):");
                    print(player, lines);
                }
            }
            case "sweep" -> {
                scheduler.sweepNow();
                player.yellowMessage("Forced a population sweep.");
                print(player, scheduler.statusLines());
            }
            case "add" -> manage(player, params, true);
            case "remove" -> manage(player, params, false);
            case "crew" -> crew(player, params);
            case "clear" -> {
                int n = BotManager.getInstance().disconnectAllBots();
                player.yellowMessage("Disconnected " + n + " online bot(s).");
                if (BotManager.cfg.POPULATION_SCHED_ENABLED) {
                    player.yellowMessage("Scheduler still ON - the next sweep respawns managed bots. "
                            + "Run @botpop off first to keep them out.");
                }
            }
            case "wipe" -> wipe(player, params);
            default -> print(player, scheduler.statusLines());
        }
    }

    /** Mark/unmark an existing character (by name) as a managed, schedulable bot. */
    private static void manage(Character player, String[] params, boolean add) {
        if (params.length < 2) {
            player.yellowMessage("Usage: @botpop " + (add ? "add" : "remove") + " <character name>");
            return;
        }
        String name = params[1];
        BotOwnershipService.ResolvedCharacter target =
                BotOwnershipService.getInstance().resolveCharacterByName(name);
        if (target == null) {
            player.yellowMessage("No character named '" + name + "' exists.");
            return;
        }
        ManagedBotService svc = ManagedBotService.getInstance();
        if (add) {
            svc.insert(target.id(), null);
            player.yellowMessage("'" + name + "' is now a managed bot - the scheduler will log it in/out "
                    + "on its personality's schedule (enable with @botpop on).");
        } else {
            svc.remove(target.id());
            player.yellowMessage("'" + name + "' is no longer a managed bot (removed from the population).");
        }
    }

    /** Assign managed bots to a persistent crew (group id), or clear with "none". */
    private static void crew(Character player, String[] params) {
        if (params.length < 3) {
            player.yellowMessage("Usage: @botpop crew <id|none> <name> [name2 ...]");
            return;
        }
        Integer groupId;
        if (params[1].equalsIgnoreCase("none") || params[1].equalsIgnoreCase("clear")) {
            groupId = null;
        } else {
            try {
                groupId = Integer.parseInt(params[1]);
            } catch (NumberFormatException e) {
                player.yellowMessage("Crew id must be a number (or 'none' to clear). Got: " + params[1]);
                return;
            }
        }
        ManagedBotService svc = ManagedBotService.getInstance();
        int done = 0;
        for (int i = 2; i < params.length; i++) {
            String name = params[i];
            BotOwnershipService.ResolvedCharacter target =
                    BotOwnershipService.getInstance().resolveCharacterByName(name);
            if (target == null) {
                player.yellowMessage("  skip '" + name + "': no such character.");
                continue;
            }
            if (!svc.isManaged(target.id())) {
                player.yellowMessage("  skip '" + name + "': not a managed bot (@botpop add it first).");
                continue;
            }
            svc.setGroup(target.id(), groupId);
            done++;
        }
        player.yellowMessage(groupId == null
                ? "Cleared crew on " + done + " bot(s)."
                : "Assigned " + done + " bot(s) to crew " + groupId + ".");
    }

    private record BotRow(int cid, String name, int level, int jobId) {}

    /**
     * Permanently delete EVERY managed bot (the {@code managed_bot} set only — real players are never
     * touched unless a GM explicitly @botpop-added them). Bare {@code @botpop wipe} previews the roster
     * (name, level, job; level high->low); {@code @botpop wipe confirm} executes. Each bot is stopped,
     * logged out of the world, then deleted via the SSOT path ({@link CharacterDeletionService}, which
     * clears inventory/equips/pets/rings and all per-char rows the FK cascade misses), and its now-empty
     * bot account is removed. A bot marked managed on an account that holds OTHER characters is skipped
     * entirely (shared/real account, not a dedicated bot account). Repopulate fresh Lv1 with
     * {@code @spawnbot generate confirm}.
     */
    private static void wipe(Character player, String[] params) {
        List<ManagedBotService.ManagedBot> managed = ManagedBotService.getInstance().loadAll();
        if (managed.isEmpty()) {
            player.yellowMessage("No managed bots exist.");
            return;
        }
        List<Integer> ids = new ArrayList<>();
        for (ManagedBotService.ManagedBot mb : managed) {
            ids.add(mb.botCharId());
        }
        List<BotRow> roster = loadRoster(ids);
        roster.sort(Comparator.comparingInt(BotRow::level).reversed());

        boolean confirm = params.length >= 2 && params[1].equalsIgnoreCase("confirm");
        if (!confirm) {
            player.yellowMessage("Managed bots to wipe (" + roster.size() + "), Lv high->low:");
            for (BotRow r : roster) {
                player.yellowMessage("  Lv" + r.level() + "  " + r.name() + "  (" + Job.getById(r.jobId()) + ")");
            }
            player.yellowMessage("This permanently DELETES them (chars + inventory + bot accounts).");
            player.yellowMessage("Run: @botpop wipe confirm");
            return;
        }

        BotManager bm = BotManager.getInstance();
        int wiped = 0, failed = 0;
        for (BotRow r : roster) {
            // Scope guard: a generated bot has its own single-char account. An account with other
            // characters is a shared/real account a char was @botpop-added to — never delete that char.
            Integer accId = accountIdOf(r.cid()); // read BEFORE delete removes the characters row
            if (accId == null || charCountOnAccount(accId) != 1) {
                failed++;
                player.yellowMessage("  skip " + r.name() + ": account has other characters (not a dedicated bot account)");
                continue;
            }
            bm.removeBotByCharId(r.cid()); // stop the bot AI tick before deleting underneath it
            Character online = player.getWorldServer().getPlayerStorage().getCharacterById(r.cid());
            if (online != null && online.getClient() != null) {
                online.getClient().disconnect(false, false); // leave the world (and final-save) before the DB delete
            }
            // Delete as the bot's OWN account: validateCharacterOwnership requires senderAccId to own the char.
            CharacterDeletionService.Result res = CharacterDeletionService.deleteCharacter(r.cid(), accId);
            if (!res.isSuccess()) {
                failed++;
                player.yellowMessage("  skip " + r.name() + ": " + res.getCommandMessage());
                continue;
            }
            deleteAccountIfEmpty(accId); // single-char account is now empty -> remove it
            wiped++;
        }
        player.yellowMessage("Wiped " + wiped + " managed bot(s)" + (failed > 0 ? " (" + failed + " skipped)" : "") + ".");
        player.yellowMessage("Repopulate fresh Lv1 with: @spawnbot generate confirm");
    }

    /** Batch-load name/level/job for the managed-bot ids; falls back to bare ids if the lookup fails. */
    private static List<BotRow> loadRoster(List<Integer> ids) {
        List<BotRow> out = new ArrayList<>();
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

    private static void print(Character player, List<String> lines) {
        for (String line : lines) {
            player.yellowMessage("  " + line);
        }
    }
}
