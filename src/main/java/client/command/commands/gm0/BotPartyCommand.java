package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import server.bots.BotManager;

public class BotPartyCommand extends Command {
    {
        setDescription("Like @botme, but only when everyone else in the party is a bot - the group runs party autopilot.");
    }

    @Override
    public void execute(Client c, String[] params) {
        String error = BotManager.getInstance().takeOverAsBot(c, true);
        if (error != null && c.getPlayer() != null) {
            c.getPlayer().yellowMessage(error);
        }
    }
}
