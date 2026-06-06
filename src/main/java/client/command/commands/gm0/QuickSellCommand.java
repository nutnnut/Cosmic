/*
    Ported from LumenMS. Lock-aware: @qs marks a pending quick-sell and asks the
    client which slots are locked; InventorySortHandler then calls doSell() once
    the client responds, skipping locked slots.
*/
package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import tools.PacketCreator;

import java.util.Collection;

public class QuickSellCommand extends Command {
    {
        setDescription("Sell items in a slot range. Usage: @qs <equip|use|setup|etc|cash> <startSlot> <endSlot>");
    }

    @Override
    public void execute(Client c, String[] params) {
        if (params.length < 3) {
            c.getPlayer().yellowMessage("Syntax: @qs <equip|use|setup|etc|cash> <startSlot> <endSlot>");
            return;
        }

        InventoryType type = parseTab(params[0]);
        if (type == null) {
            c.getPlayer().yellowMessage("Unknown tab. Use: equip, use, setup, etc, or cash.");
            return;
        }

        int startSlot, endSlot;
        try {
            startSlot = Integer.parseInt(params[1]);
            endSlot = Integer.parseInt(params[2]);
        } catch (NumberFormatException e) {
            c.getPlayer().yellowMessage("Syntax: @qs <equip|use|setup|etc|cash> <startSlot> <endSlot>");
            return;
        }

        if (startSlot < 1 || endSlot < startSlot) {
            c.getPlayer().yellowMessage("Slot range must be positive and start <= end.");
            return;
        }

        if (c.getPlayer().hasPendingQuickSell()) {
            c.getPlayer().yellowMessage("A sell is already pending.");
            return;
        }

        c.getPlayer().setPendingQuickSell(type, startSlot, endSlot);
        c.sendPacket(PacketCreator.requestLockedSlots((byte) type.getType()));
        // sell completes in InventorySortHandler once the client responds with locked slot data
    }

    public static void doSell(Client c, InventoryType type, int startSlot, int endSlot,
                              Collection<Integer> lockedSlots) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Inventory inv = c.getPlayer().getInventory(type);
        long totalMesos = 0;
        int itemsSold = 0;

        for (int slot = startSlot; slot <= endSlot; slot++) {
            Item item = inv.getItem((short) slot);
            if (item == null) {
                continue;
            }
            if (lockedSlots.contains(slot)) {
                continue;
            }
            if (item instanceof Equip equip && equip.isLocked()) {
                continue;
            }
            if (!(item instanceof Equip) && (item.getFlag() & ItemConstants.LOCK) != 0) {
                continue;
            }

            short qty = item.getQuantity();
            if (qty == (short) 0xFFFF) {
                qty = 1;
            }

            int price = ii.getPrice(item.getItemId(), qty);
            if (price <= 0) {
                continue;
            }

            InventoryManipulator.removeFromSlot(c, type, (short) slot, qty, false);
            totalMesos += price;
            itemsSold++;
        }

        if (itemsSold == 0) {
            c.getPlayer().dropMessage(5, "No sellable items found in that range.");
            return;
        }

        c.getPlayer().gainMeso((int) Math.min(totalMesos, Integer.MAX_VALUE), false);
        c.getPlayer().dropMessage(5, "Sold " + itemsSold + " item(s) for " + totalMesos + " mesos.");
    }

    private static InventoryType parseTab(String s) {
        return switch (s.toLowerCase()) {
            case "equip" -> InventoryType.EQUIP;
            case "use" -> InventoryType.USE;
            case "setup", "set-up" -> InventoryType.SETUP;
            case "etc", "other" -> InventoryType.ETC;
            case "cash" -> InventoryType.CASH;
            default -> null;
        };
    }
}
