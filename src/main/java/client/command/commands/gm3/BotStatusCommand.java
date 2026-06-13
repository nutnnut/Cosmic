package client.command.commands.gm3;

import client.Client;
import client.command.Command;
import server.bots.BotManager;

import java.util.List;

public class BotStatusCommand extends Command {
    {
        setDescription("List every bot on your map and what each is doing (private admin debug).");
    }

    @Override
    public void execute(Client c, String[] params) {
        if (c.getPlayer() == null) {
            return;
        }
        int mapId = c.getPlayer().getMapId();
        List<String> lines = BotManager.getInstance().mapBotStatusLines(mapId);
        if (lines.isEmpty()) {
            c.getPlayer().yellowMessage("No bots on this map (" + mapId + ").");
            return;
        }
        c.getPlayer().yellowMessage("Bots on map " + mapId + " (" + lines.size() + "):");
        for (String line : lines) {
            c.getPlayer().yellowMessage("  " + line);
        }
    }
}
