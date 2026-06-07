package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import server.bots.BotManager;

import java.util.List;

public class BotInfoCommand extends Command {
    {
        setDescription("Show live status (level/job/HP/MP/map/action) of your spawned bots.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        String name = params.length > 0 ? String.join(" ", params) : null;

        List<String> lines = BotManager.getInstance().describeBots(player.getId(), name);
        if (lines.isEmpty()) {
            player.yellowMessage(name == null
                    ? "You have no spawned bots."
                    : "No spawned bot matching \"" + name + "\".");
            return;
        }

        player.dropMessage(6, "Bot status (" + lines.size() + "):");
        for (String line : lines) {
            player.dropMessage(6, "  " + line);
        }
    }
}
