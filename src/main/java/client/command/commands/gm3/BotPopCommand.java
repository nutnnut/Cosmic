package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import server.bots.BotScheduler;

import java.util.List;

/**
 * Drive/inspect the living-server bot population scheduler (default OFF).
 * Usage: {@code @botpop [status|on|off|list|sweep]} (no arg = status).
 */
public class BotPopCommand extends Command {
    {
        setDescription("Living-server bot population: status / on / off / list / sweep.");
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
            default -> print(player, scheduler.statusLines());
        }
    }

    private static void print(Character player, List<String> lines) {
        for (String line : lines) {
            player.yellowMessage("  " + line);
        }
    }
}
