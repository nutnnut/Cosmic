/*
    LumenMS — @leaf: opens the Maple Leaf Shop (scripts/npc/maple_leaf_shop.js) via the Maple
    Administrator NPC. The shop sells Maple-branded weapons and accessories for Maple Leaves.
*/
package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import constants.id.NpcId;

public class LeafCommand extends Command {
    {
        setDescription("Open the Maple Leaf Shop (buy Maple gear with Maple Leaves).");
    }

    @Override
    public void execute(Client c, String[] params) {
        c.getAbstractPlayerInteraction().openNpc(NpcId.MAPLE_ADMINISTRATOR, "maple_leaf_shop");
    }
}
