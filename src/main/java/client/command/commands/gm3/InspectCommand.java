package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import server.bots.BotManager;

/*
    !inspect <name>  -> append that logged-in character (bot or real player) to the GM's bot-equip
                        (F8) window so its inventory is visible. One target at a time (replaces).
    !inspect         -> release the current inspect target.
    Pure window curation: no follow/formation side effect.
*/
public class InspectCommand extends Command {
    {
        setDescription("Inspect a character's inventory in the F8 window. Usage: !inspect [name]");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        BotManager bm = BotManager.getInstance();

        if (params.length < 1 || params[0].isEmpty()) {
            bm.setInspectTarget(player.getId(), 0);
            player.yellowMessage("Inspect target released.");
            return;
        }

        String name = player.getLastCommandMessage().trim();
        Character target = c.getWorldServer().getPlayerStorage().getCharacterByName(name);
        if (target == null) {
            player.yellowMessage("Character '" + name + "' is not online.");
            return;
        }

        bm.setInspectTarget(player.getId(), target.getId());
        player.yellowMessage("Inspecting '" + target.getName() + "' - open the F8 window to view their inventory.");
    }
}
