package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import server.bots.BotAdminOps;
import server.bots.BotManager;
import server.bots.BotOwnershipService;
import server.bots.BotScheduler;
import server.bots.ManagedBotService;

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
        setDescription("Living-server bot population: status / on / off / <multiplier> / list / sweep / clear / add <name> / remove <name> / crew <id|none> <name...> / wipe [confirm].");
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
            default -> {
                Double mult = parseMultiplier(verb);
                if (mult != null) {
                    scheduler.setMultiplier(mult);
                    player.yellowMessage("Bot population multiplier set to " + mult + "x"
                            + (mult == 0.0 ? " (no bots will be scheduled)." : "."));
                    print(player, scheduler.statusLines());
                } else {
                    print(player, scheduler.statusLines());
                }
            }
        }
    }

    /** Parse a bare numeric arg (e.g. "0", "1", "3", "2.5") as a non-negative multiplier; null if not numeric. */
    private static Double parseMultiplier(String s) {
        try {
            double v = Double.parseDouble(s);
            return v >= 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
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

    /**
     * Permanently delete EVERY managed bot. Bare {@code @botpop wipe} previews the roster (level low->high);
     * {@code @botpop wipe confirm} executes via {@link BotAdminOps#wipeManagedBots()} (shared SSOT with the
     * web admin menu). Real/shared accounts are skipped there. Repopulate fresh Lv1 with
     * {@code @spawnbot generate confirm}.
     */
    private static void wipe(Character player, String[] params) {
        List<BotAdminOps.BotRow> roster = BotAdminOps.roster();
        if (roster.isEmpty()) {
            player.yellowMessage("No managed bots exist.");
            return;
        }
        boolean confirm = params.length >= 2 && params[1].equalsIgnoreCase("confirm");
        if (!confirm) {
            player.yellowMessage("Managed bots to wipe (" + roster.size() + "), Lv low->high:");
            for (BotAdminOps.BotRow r : roster) {
                player.yellowMessage("  " + BotAdminOps.describe(r));
            }
            player.yellowMessage("This permanently DELETES them (chars + inventory + bot accounts).");
            player.yellowMessage("Run: @botpop wipe confirm");
            return;
        }
        BotAdminOps.WipeResult res = BotAdminOps.wipeManagedBots();
        for (String line : res.lines()) {
            player.yellowMessage("  " + line);
        }
        player.yellowMessage("Wiped " + res.wiped() + " managed bot(s)"
                + (res.skipped() > 0 ? " (" + res.skipped() + " skipped)" : "") + ".");
        player.yellowMessage("Repopulate fresh Lv1 with: @spawnbot generate confirm");
    }

    private static void print(Character player, List<String> lines) {
        for (String line : lines) {
            player.yellowMessage("  " + line);
        }
    }
}
