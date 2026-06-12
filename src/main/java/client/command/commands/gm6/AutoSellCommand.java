package client.command.commands.gm6;

import client.Client;
import client.command.Command;
import server.bots.BotManager;

import java.util.List;

public class AutoSellCommand extends Command {
    {
        setDescription("Debug: list what the bot sell-trash pipeline would sell from this character; 'confirm' sells it all instantly.");
    }

    @Override
    public void execute(Client c, String[] params) {
        if (c.getPlayer() == null) {
            return;
        }
        boolean confirm = params.length > 0 && params[0].equalsIgnoreCase("confirm");
        List<String> lines = confirm
                ? BotManager.getInstance().autoSellConfirm(c)
                : BotManager.getInstance().autoSellPreview(c.getPlayer());
        for (String line : lines) {
            c.getPlayer().yellowMessage(line);
        }
    }
}
