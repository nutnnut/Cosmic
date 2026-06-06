/*
    LumenMS — opens the Soul Vessel rebirth-ring dialog (scripts/npc/soul_vessel.js)
    via the Maple Administrator NPC, matching the wire-up used for @rebirth.
*/
package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import constants.id.NpcId;

public class VesselCommand extends Command {
    {
        setDescription("Open the Soul Vessel dialog: view your rebirth ring engravings and stats.");
    }

    @Override
    public void execute(Client c, String[] params) {
        c.getAbstractPlayerInteraction().openNpc(NpcId.MAPLE_ADMINISTRATOR, "soul_vessel");
    }
}
