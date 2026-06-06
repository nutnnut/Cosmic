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

import client.Character;
import client.Client;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.ModifyInventory;
import config.YamlConfig;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import net.server.Server;
import server.ItemInformationProvider;
import tools.PacketCreator;
import tools.DatabaseConnection;
import client.command.commands.gm0.QuickSellCommand;
import constants.inventory.ItemConstants;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * @author BubblesDev
 * @author Ronan
 */

class PairedQuicksort {
    private int i = 0;
    private int j = 0;
    private final ArrayList<Integer> intersect;
    ItemInformationProvider ii = ItemInformationProvider.getInstance();

    private void PartitionByItemId(int Esq, int Dir, ArrayList<Item> A) {
        Item x, w;

        i = Esq;
        j = Dir;

        x = A.get((i + j) / 2);
        do {
            while (x.getItemId() > A.get(i).getItemId()) {
                i++;
            }
            while (x.getItemId() < A.get(j).getItemId()) {
                j--;
            }

            if (i <= j) {
                w = A.get(i);
                A.set(i, A.get(j));
                A.set(j, w);

                i++;
                j--;
            }
        } while (i <= j);
    }

    private int getWatkForProjectile(Item item) {
        return ii.getWatkForProjectile(item.getItemId());
    }

    private void PartitionByProjectileAtk(int Esq, int Dir, ArrayList<Item> A) {
        Item x, w;

        i = Esq;
        j = Dir;

        x = A.get((i + j) / 2);
        do {
            int watk = getWatkForProjectile(x);
            while (watk < getWatkForProjectile(A.get(i))) {
                i++;
            }
            while (watk > getWatkForProjectile(A.get(j))) {
                j--;
            }

            if (i <= j) {
                w = A.get(i);
                A.set(i, A.get(j));
                A.set(j, w);

                i++;
                j--;
            }
        } while (i <= j);
    }

    private void PartitionByName(int Esq, int Dir, ArrayList<Item> A) {
        Item x, w;

        i = Esq;
        j = Dir;

        x = A.get((i + j) / 2);
        do {
            while (ii.getName(x.getItemId()).compareTo(ii.getName(A.get(i).getItemId())) > 0) {
                i++;
            }
            while (ii.getName(x.getItemId()).compareTo(ii.getName(A.get(j).getItemId())) < 0) {
                j--;
            }

            if (i <= j) {
                w = A.get(i);
                A.set(i, A.get(j));
                A.set(j, w);

                i++;
                j--;
            }
        } while (i <= j);
    }

    private void PartitionByQuantity(int Esq, int Dir, ArrayList<Item> A) {
        Item x, w;

        i = Esq;
        j = Dir;

        x = A.get((i + j) / 2);
        do {
            while (x.getQuantity() > A.get(i).getQuantity()) {
                i++;
            }
            while (x.getQuantity() < A.get(j).getQuantity()) {
                j--;
            }

            if (i <= j) {
                w = A.get(i);
                A.set(i, A.get(j));
                A.set(j, w);

                i++;
                j--;
            }
        } while (i <= j);
    }

    private void PartitionByLevel(int Esq, int Dir, ArrayList<Item> A) {
        Equip x, w;

        i = Esq;
        j = Dir;

        x = (Equip) (A.get((i + j) / 2));

        do {

            while (x.getLevel() > ((Equip) A.get(i)).getLevel()) {
                i++;
            }
            while (x.getLevel() < ((Equip) A.get(j)).getLevel()) {
                j--;
            }

            if (i <= j) {
                w = (Equip) A.get(i);
                A.set(i, A.get(j));
                A.set(j, w);

                i++;
                j--;
            }
        } while (i <= j);
    }

    void MapleQuicksort(int Esq, int Dir, ArrayList<Item> A, int sort) {
        switch (sort) {
            case 3:
                PartitionByLevel(Esq, Dir, A);
                break;

            case 2:
                PartitionByName(Esq, Dir, A);
                break;

            case 1:
                PartitionByQuantity(Esq, Dir, A);
                break;

            default:
                PartitionByItemId(Esq, Dir, A);
        }


        if (Esq < j) {
            MapleQuicksort(Esq, j, A, sort);
        }
        if (i < Dir) {
            MapleQuicksort(i, Dir, A, sort);
        }
    }

    private static int getItemSubtype(Item it) {
        return it.getItemId() / 10000;
    }

    private int[] BinarySearchElement(ArrayList<Item> A, int rangeId) {
        int st = 0, en = A.size() - 1;

        int mid = -1, idx = -1;
        while (en >= st) {
            idx = (st + en) / 2;
            mid = getItemSubtype(A.get(idx));

            if (mid == rangeId) {
                break;
            } else if (mid < rangeId) {
                st = idx + 1;
            } else {
                en = idx - 1;
            }
        }

        if (en < st) {
            return null;
        }

        st = idx - 1;
        en = idx + 1;
        while (st >= 0 && getItemSubtype(A.get(st)) == rangeId) {
            st -= 1;
        }
        st += 1;

        while (en < A.size() && getItemSubtype(A.get(en)) == rangeId) {
            en += 1;
        }
        en -= 1;

        return new int[]{st, en};
    }

    public void reverseSortSublist(ArrayList<Item> A, int[] range) {
        if (range != null) {
            PartitionByProjectileAtk(range[0], range[1], A);
        }
    }

    public PairedQuicksort(ArrayList<Item> A, int primarySort, int secondarySort) {
        intersect = new ArrayList<>();

        if (A.size() > 0) {
            MapleQuicksort(0, A.size() - 1, A, primarySort);

            if (A.get(0).getInventoryType().equals(InventoryType.USE)) {   // thanks KDA & Vcoc for suggesting stronger projectiles coming before weaker ones
                reverseSortSublist(A, BinarySearchElement(A, 206));  // arrows
                reverseSortSublist(A, BinarySearchElement(A, 207));  // stars
                reverseSortSublist(A, BinarySearchElement(A, 233));  // bullets
            }
        }

        intersect.add(0);
        for (int ind = 1; ind < A.size(); ind++) {
            if (A.get(ind - 1).getItemId() != A.get(ind).getItemId()) {
                intersect.add(ind);
            }
        }
        intersect.add(A.size());

        for (int ind = 0; ind < intersect.size() - 1; ind++) {
            if (intersect.get(ind + 1) > intersect.get(ind)) {
                MapleQuicksort(intersect.get(ind), intersect.get(ind + 1) - 1, A, secondarySort);
            }
        }
    }
}

public final class InventorySortHandler extends AbstractPacketHandler {
    @Override
    public final void handlePacket(InPacket p, Client c) {
        Character chr = c.getPlayer();
        p.readInt();
        chr.getAutobanManager().setTimestamp(3, Server.getInstance().getCurrentTimestamp(), 4);

        byte invType = p.readByte();
        if (invType < 1 || invType > 5) {
            c.disconnect(false, false);
            return;
        }

        // LumenMS slot-lock: the client appends locked-slot data after invType.
        if (p.available() > 0) {
            handleSlotLockSort(p, c, chr, invType);
            return;
        }

        if (!YamlConfig.config.server.USE_ITEM_SORT) {
            c.sendPacket(PacketCreator.enableActions());
            return;
        }

        ArrayList<Item> itemarray = new ArrayList<>();
        List<ModifyInventory> mods = new ArrayList<>();

        Inventory inventory = chr.getInventory(InventoryType.getByType(invType));
        inventory.lockInventory();
        try {
            for (short i = 1; i <= inventory.getSlotLimit(); i++) {
                Item item = inventory.getItem(i);
                if (item != null) {
                    itemarray.add(item.copy());
                }
            }

            for (Item item : itemarray) {
                inventory.removeSlot(item.getPosition());
                mods.add(new ModifyInventory(3, item));
            }

            int invTypeCriteria = (InventoryType.getByType(invType) == InventoryType.EQUIP) ? 3 : 1;
            int sortCriteria = (YamlConfig.config.server.USE_ITEM_SORT_BY_NAME == true) ? 2 : 0;
            PairedQuicksort pq = new PairedQuicksort(itemarray, sortCriteria, invTypeCriteria);

            for (Item item : itemarray) {
                inventory.addItem(item);
                mods.add(new ModifyInventory(0, item.copy()));//to prevent crashes
            }
            itemarray.clear();
        } finally {
            inventory.unlockInventory();
        }

        c.sendPacket(PacketCreator.modifyInventory(true, mods));
        c.sendPacket(PacketCreator.finishedSort2(invType));
        c.sendPacket(PacketCreator.enableActions());
    }

    // LumenMS slot-lock: client reports which slots are locked; we persist the lock state,
    // then either run a pending quick-sell (skipping locked slots) or sort around the locks.
    private void handleSlotLockSort(InPacket p, Client c, Character chr, byte invType) {
        List<Integer> lockedSlots = new ArrayList<>();
        final int lockSize = Short.toUnsignedInt(p.readUnsignedByte());
        for (int i = 0; i < lockSize; i++) {
            lockedSlots.add(Short.toUnsignedInt(p.readUnsignedByte()));
        }

        ArrayList<Item> itemarray = new ArrayList<>();
        List<ModifyInventory> mods = new ArrayList<>();

        InventoryType invTypeEnum = InventoryType.getByType(invType);
        Inventory inventory = chr.getInventory(invTypeEnum);
        Character.PendingQuickSell pqs;
        inventory.lockInventory();
        try {
            if (invTypeEnum == InventoryType.EQUIP) {
                for (short i = 1; i <= inventory.getSlotLimit(); i++) {
                    Item item = inventory.getItem(i);
                    if (item instanceof Equip equip) {
                        equip.setLocked(lockedSlots.contains((int) i));
                    }
                }
                persistEquipLockedState(chr.getId(), lockedSlots);
            } else {
                for (short i = 1; i <= inventory.getSlotLimit(); i++) {
                    Item item = inventory.getItem(i);
                    if (item != null) {
                        short flag = item.getFlag();
                        if (lockedSlots.contains((int) i)) {
                            flag |= ItemConstants.LOCK;
                        } else {
                            flag &= ~ItemConstants.LOCK;
                        }
                        item.setFlag(flag);
                    }
                }
                persistNonEquipLockedState(chr.getId(), invType, lockedSlots);
            }

            pqs = chr.pollPendingQuickSell();
            if (pqs == null) {
                for (short i = 1; i <= inventory.getSlotLimit(); i++) {
                    if (lockedSlots.contains((int) i)) {
                        continue;
                    }
                    Item item = inventory.getItem(i);
                    if (item != null) {
                        itemarray.add(item.copy());
                    }
                }

                for (Item item : itemarray) {
                    inventory.removeSlot(item.getPosition());
                    mods.add(new ModifyInventory(3, item));
                }

                int invTypeCriteria = (invTypeEnum == InventoryType.EQUIP) ? 3 : 1;
                int sortCriteria = (YamlConfig.config.server.USE_ITEM_SORT_BY_NAME == true) ? 2 : 0;
                new PairedQuicksort(itemarray, sortCriteria, invTypeCriteria);

                // Place items sequentially while skipping locked / occupied slots.
                int slotCursor = 1;
                for (Item item : itemarray) {
                    while (slotCursor <= inventory.getSlotLimit()
                            && (lockedSlots.contains(slotCursor)
                                || inventory.getItem((short) slotCursor) != null)) {
                        slotCursor++;
                    }
                    if (slotCursor > inventory.getSlotLimit()) {
                        break;
                    }
                    item.setPosition((short) slotCursor);
                    inventory.addItemFromDB(item);
                    mods.add(new ModifyInventory(0, item.copy()));
                    slotCursor++;
                }
                itemarray.clear();
            }
        } finally {
            inventory.unlockInventory();
        }

        if (pqs != null) {
            QuickSellCommand.doSell(c, pqs.type(), pqs.startSlot(), pqs.endSlot(), lockedSlots);
            return;
        }

        c.sendPacket(PacketCreator.modifyInventory(true, mods));
        c.sendPacket(PacketCreator.finishedSort2(invType));
        c.sendPacket(PacketCreator.enableActions());
    }

    static void persistEquipLockedState(int characterId, Collection<Integer> lockedSlots) {
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE inventoryequipment ie INNER JOIN inventoryitems ii ON ie.inventoryitemid = ii.inventoryitemid " +
                    "SET ie.locked = 0 WHERE ii.characterid = ? AND ii.inventorytype = ?")) {
                ps.setInt(1, characterId);
                ps.setInt(2, InventoryType.EQUIP.getType());
                ps.executeUpdate();
            }
            if (!lockedSlots.isEmpty()) {
                try (PreparedStatement ps = con.prepareStatement(
                        "UPDATE inventoryequipment ie INNER JOIN inventoryitems ii ON ie.inventoryitemid = ii.inventoryitemid " +
                        "SET ie.locked = 1 WHERE ii.characterid = ? AND ii.inventorytype = ? AND ii.position = ?")) {
                    for (int slot : lockedSlots) {
                        ps.setInt(1, characterId);
                        ps.setInt(2, InventoryType.EQUIP.getType());
                        ps.setInt(3, slot);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
        } catch (SQLException ignored) {
        }
    }

    static void persistNonEquipLockedState(int characterId, byte invType, Collection<Integer> lockedSlots) {
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE inventoryitems SET flag = flag & ~1 " +
                    "WHERE characterid = ? AND inventorytype = ? AND type = 1")) {
                ps.setInt(1, characterId);
                ps.setInt(2, invType);
                ps.executeUpdate();
            }
            if (!lockedSlots.isEmpty()) {
                try (PreparedStatement ps = con.prepareStatement(
                        "UPDATE inventoryitems SET flag = flag | 1 " +
                        "WHERE characterid = ? AND inventorytype = ? AND type = 1 AND position = ?")) {
                    for (int slot : lockedSlots) {
                        ps.setInt(1, characterId);
                        ps.setInt(2, invType);
                        ps.setInt(3, slot);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
        } catch (SQLException ignored) {
        }
    }
}
