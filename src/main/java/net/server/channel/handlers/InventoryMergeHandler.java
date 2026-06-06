/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License as
 published by the Free Software Foundation version 3 as published by
 the Free Software Foundation. You may not use, modify or distribute
 this program under any other version of the GNU Affero General Public
 License.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.server.channel.handlers;

import java.util.HashSet;
import java.util.Set;

import client.Character;
import client.Client;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import config.YamlConfig;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import net.server.Server;
import server.ItemInformationProvider;
import tools.PacketCreator;

public final class InventoryMergeHandler extends AbstractPacketHandler {

    @Override
    public final void handlePacket(InPacket p, Client c) {
        Character chr = c.getPlayer();
        p.readInt();
        chr.getAutobanManager().setTimestamp(2, Server.getInstance().getCurrentTimestamp(), 4);

        if (!YamlConfig.config.server.USE_ITEM_SORT) {
            c.sendPacket(PacketCreator.enableActions());
            return;
        }

        byte invType = p.readByte();
        if (invType < 1 || invType > 5) {
            c.disconnect(false, false);
            return;
        }

        // LumenMS slot-lock: the client appends locked-slot data after invType.
        boolean isSlotLockRequest = p.available() > 0;
        if (isSlotLockRequest) {
            handleSlotLockMerge(p, c, invType);
            return;
        }

        InventoryType inventoryType = InventoryType.getByType(invType);
        Inventory inventory = c.getPlayer().getInventory(inventoryType);
        inventory.lockInventory();
        try {
            //------------------- RonanLana's SLOT MERGER -----------------

            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            Item srcItem, dstItem;

            for (short dst = 1; dst <= inventory.getSlotLimit(); dst++) {
                dstItem = inventory.getItem(dst);
                if (dstItem == null) {
                    continue;
                }

                for (short src = (short) (dst + 1); src <= inventory.getSlotLimit(); src++) {
                    srcItem = inventory.getItem(src);
                    if (srcItem == null) {
                        continue;
                    }

                    if (dstItem.getItemId() != srcItem.getItemId()) {
                        continue;
                    }
                    if (dstItem.getQuantity() == ii.getSlotMax(c, inventory.getItem(dst).getItemId())) {
                        break;
                    }

                    InventoryManipulator.move(c, inventoryType, src, dst);
                }
            }

            //------------------------------------------------------------

            inventory = c.getPlayer().getInventory(inventoryType);
            boolean sorted = false;

            while (!sorted) {
                short freeSlot = inventory.getNextFreeSlot();

                if (freeSlot != -1) {
                    short itemSlot = -1;
                    for (short i = (short) (freeSlot + 1); i <= inventory.getSlotLimit(); i = (short) (i + 1)) {
                        if (inventory.getItem(i) != null) {
                            itemSlot = i;
                            break;
                        }
                    }
                    if (itemSlot > 0) {
                        InventoryManipulator.move(c, inventoryType, itemSlot, freeSlot);
                    } else {
                        sorted = true;
                    }
                } else {
                    sorted = true;
                }
            }
        } finally {
            inventory.unlockInventory();
        }

        c.sendPacket(PacketCreator.finishedSort(inventoryType.getType()));
        c.sendPacket(PacketCreator.enableActions());
    }

    // LumenMS slot-lock: merge/sort while leaving locked slots untouched.
    private void handleSlotLockMerge(InPacket p, Client c, byte invType) {
        Set<Integer> lockedSlots = new HashSet<>();
        final int lockSize = Short.toUnsignedInt(p.readUnsignedByte());
        for (int i = 0; i < lockSize; i++) {
            lockedSlots.add(Short.toUnsignedInt(p.readUnsignedByte()));
        }

        InventoryType inventoryType = InventoryType.getByType(invType);
        Inventory inventory = c.getPlayer().getInventory(inventoryType);
        inventory.lockInventory();
        try {
            for (short i = 1; i <= inventory.getSlotLimit(); i++) {
                Item item = inventory.getItem(i);
                if (item instanceof Equip equip) {
                    equip.setLocked(lockedSlots.contains((int) i));
                }
            }

            if (inventoryType == InventoryType.EQUIP) {
                InventorySortHandler.persistEquipLockedState(c.getPlayer().getId(), lockedSlots);
            }

            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            Item srcItem, dstItem;

            // 1. Merge step (skip locked slots)
            for (short dst = 1; dst <= inventory.getSlotLimit(); dst++) {
                if (lockedSlots.contains((int) dst)) {
                    continue;
                }
                dstItem = inventory.getItem(dst);
                if (dstItem == null) {
                    continue;
                }

                for (short src = (short) (dst + 1); src <= inventory.getSlotLimit(); src++) {
                    if (lockedSlots.contains((int) src)) {
                        continue;
                    }
                    srcItem = inventory.getItem(src);
                    if (srcItem == null) {
                        continue;
                    }
                    if (dstItem.getItemId() != srcItem.getItemId()) {
                        continue;
                    }
                    if (dstItem.getQuantity() == ii.getSlotMax(c, inventory.getItem(dst).getItemId())) {
                        break;
                    }

                    InventoryManipulator.move(c, inventoryType, src, dst);
                }
            }

            // 2. Fill in blanks step (skip locked slots)
            inventory = c.getPlayer().getInventory(inventoryType);
            boolean sorted = false;

            while (!sorted) {
                short freeSlot = -1;
                for (short i = 1; i <= inventory.getSlotLimit(); i++) {
                    if (!lockedSlots.contains((int) i) && inventory.getItem(i) == null) {
                        freeSlot = i;
                        break;
                    }
                }

                if (freeSlot != -1) {
                    short itemSlot = -1;
                    for (short i = (short) (freeSlot + 1); i <= inventory.getSlotLimit(); i++) {
                        if (!lockedSlots.contains((int) i) && inventory.getItem(i) != null) {
                            itemSlot = i;
                            break;
                        }
                    }
                    if (itemSlot > 0) {
                        InventoryManipulator.move(c, inventoryType, itemSlot, freeSlot);
                    } else {
                        sorted = true;
                    }
                } else {
                    sorted = true;
                }
            }
        } finally {
            inventory.unlockInventory();
        }

        c.sendPacket(PacketCreator.finishedSort(inventoryType.getType()));
        c.sendPacket(PacketCreator.enableActions());
    }
}