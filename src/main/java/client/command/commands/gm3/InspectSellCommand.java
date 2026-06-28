package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import server.bots.BotManager;

/*
    !inspectsell -> rearrange the current !inspect target's bag so each tab reads
                    what the bot sell-trash pipeline would sell first, then ONE empty
                    divider slot, then the keeps. Reopen the F8 window to view.
    Admin debug; frees the divider slot by NPC-selling one already-doomed item when
    a tab is full (illegal sale allowed here).
*/
public class InspectSellCommand extends Command {
    {
        setDescription("Debug: order the !inspect target's bag as sells | gap | keeps. Usage: !inspectsell");
    }

    @Override
    public void execute(Client c, String[] params) {
        BotManager bm = BotManager.getInstance();
        Character target = bm.getInspectTarget(c.getPlayer().getId());
        if (target == null) {
            c.getPlayer().yellowMessage("No inspect target set. Use !inspect <name> first.");
            return;
        }
        for (String line : bm.inspectSellArrange(target)) {
            c.getPlayer().yellowMessage(line);
        }
        c.getPlayer().yellowMessage("Reopen the F8 window to view the new order.");
    }
}
