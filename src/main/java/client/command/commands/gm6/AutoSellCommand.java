package client.command.commands.gm6;

import client.Client;
import client.command.Command;
import server.bots.BotManager;

import java.util.ArrayList;
import java.util.List;

public class AutoSellCommand extends Command {
    {
        setDescription("Debug: list what the bot sell-trash pipeline would sell from this character + lay the bag out by sell pressure (SSOT with !inspectsell); 'confirm' sells it all instantly.");
    }

    @Override
    public void execute(Client c, String[] params) {
        if (c.getPlayer() == null) {
            return;
        }
        BotManager bm = BotManager.getInstance();
        boolean confirm = params.length > 0 && params[0].equalsIgnoreCase("confirm");
        List<String> lines = new ArrayList<>(confirm
                ? bm.autoSellConfirm(c)
                : bm.autoSellPreview(c.getPlayer()));
        if (!confirm) {
            // Preview also physically lays the bag out by sell pressure, same SSOT as !inspectsell.
            lines.addAll(bm.inspectSellArrange(c.getPlayer()));
        }
        for (String line : lines) {
            c.getPlayer().yellowMessage(line);
        }
    }
}
