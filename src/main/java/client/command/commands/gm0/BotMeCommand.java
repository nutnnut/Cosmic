package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import server.bots.BotManager;

public class BotMeCommand extends Command {
    {
        setDescription("Log out and let this character keep playing as an autopilot bot.");
    }

    @Override
    public void execute(Client c, String[] params) {
        String error = BotManager.getInstance().takeOverAsBot(c, false);
        if (error != null && c.getPlayer() != null) {
            c.getPlayer().yellowMessage(error);
        }
    }
}
