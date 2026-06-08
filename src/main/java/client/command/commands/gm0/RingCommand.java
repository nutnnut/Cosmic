/*
    LumenMS — @ring: opens the Quest Ring dialog (scripts/npc/quest_ring.js) via the Maple
    Administrator NPC. Ensures the player owns the bound quest ring first (granting one if needed),
    then syncs its stats. The dialog explains the Quest Ring and shows Soul Vessel info.
*/
package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.id.ItemId;
import constants.id.NpcId;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;

public class RingCommand extends Command {
    {
        setDescription("Open the Quest Ring dialog (and get a quest ring if you don't have one).");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();

        // Safety net — login normally grants the ring, but re-grant on demand if it's missing.
        if (player.getInventory(InventoryType.EQUIPPED).findById(ItemId.QUEST_RING) == null
                && player.getInventory(InventoryType.EQUIP).findById(ItemId.QUEST_RING) == null) {
            Item ring = ItemInformationProvider.getInstance().getEquipById(ItemId.QUEST_RING);
            ring.setFlag((short) (ring.getFlag() | ItemConstants.UNTRADEABLE | ItemConstants.LOCK));
            InventoryManipulator.addFromDrop(c, ring, false);
        }
        player.applyQuestRingBoost();

        c.getAbstractPlayerInteraction().openNpc(NpcId.MAPLE_ADMINISTRATOR, "quest_ring");
    }
}
