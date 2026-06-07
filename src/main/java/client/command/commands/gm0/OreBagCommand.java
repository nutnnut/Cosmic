package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import client.inventory.manipulator.KarmaManipulator;
import constants.inventory.ItemConstants;
import server.OreStorage;
import tools.PacketCreator;

import java.util.ArrayList;

public class OreBagCommand extends Command {
    {
        setDescription("Manages your ore bag. Usage: @orebag <open|store|on|off>");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        OreStorage oreStorage = player.getOreStorage();

        if (params.length < 1) {
            player.yellowMessage("Syntax: @orebag <open | store>");
            return;
        }

        switch (params[0]) {
            case "open":
                player.setUsingOreStorage(true);
                oreStorage.sendStorage(c, 1012009);
                c.sendPacket(PacketCreator.enableActions());
                break;
            case "store":
                for (InventoryType invType : new InventoryType[]{InventoryType.ETC, InventoryType.USE}) {
                    Inventory inv = player.getInventory(invType);
                    ArrayList<Item> toStore = new ArrayList<>(inv.list());
                    for (Item item : toStore) {
                        if (!ItemConstants.isOreBagAllowed(item.getItemId())) {
                            continue;
                        }
                        short qty = item.getQuantity();
                        inv.lockInventory();
                        try {
                            InventoryManipulator.removeFromSlot(c, invType, item.getPosition(), qty, false);
                            item = item.copy();
                        } finally {
                            inv.unlockInventory();
                        }
                        KarmaManipulator.toggleKarmaFlagToUntradeable(item);
                        item.setQuantity(qty);
                        oreStorage.storeMerge(item, c);
                        player.setUsedOreStorage();
                    }
                }
                player.yellowMessage("All maker materials and scrolls moved to ore bag.");
                break;
            case "on":
                player.setAutoOreStorage(true);
                player.yellowMessage("Ore bag auto-collect ON — maker materials and scrolls will go straight to your ore bag.");
                break;
            case "off":
                player.setAutoOreStorage(false);
                player.yellowMessage("Ore bag auto-collect OFF — maker materials and scrolls will go to your inventory.");
                break;
            default:
                player.yellowMessage("Syntax: @orebag <open | store | on | off>");
        }
    }
}
