package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import server.bots.BotOwnershipService;
import server.bots.BotScheduler;
import server.bots.ManagedBotService;

import java.util.List;

/**
 * Drive/inspect the living-server bot population scheduler (default OFF).
 * Usage: {@code @botpop [status|on|off|list|sweep|add <name>|remove <name>]} (no arg = status).
 *
 * <p>{@code add}/{@code remove} mark an EXISTING character as a managed (schedulable) bot or unmark it
 * — the only way besides {@code @spawnbot generate} to put a character in the population. GM-explicit,
 * so it doesn't violate the "never auto-schedule a real player" rule.
 */
public class BotPopCommand extends Command {
    {
        setDescription("Living-server bot population: status / on / off / list / sweep / add <name> / remove <name>.");
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

    private static void print(Character player, List<String> lines) {
        for (String line : lines) {
            player.yellowMessage("  " + line);
        }
    }
}
