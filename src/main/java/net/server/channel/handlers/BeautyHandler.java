package net.server.channel.handlers;

import client.Character;
import client.Client;
import client.SkinColor;
import client.Stat;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import server.beauty.BeautyData;
import server.beauty.BeautyPackets;
import server.beauty.BeautyStorage;

import java.util.List;

public final class BeautyHandler extends AbstractPacketHandler {
    private static final int ACTION_REQUEST = 0;
    private static final int ACTION_SAVE = 1;
    private static final int ACTION_APPLY = 2;
    private static final int ACTION_DELETE = 3;
    private static final int ACTION_UNLOCK = 4;

    private static final int TYPE_HAIR = 0;
    private static final int TYPE_FACE = 1;
    private static final int TYPE_SKIN = 2;
    private static final int SLOT_COUNT = 6;
    private static final int BEAUTY_ITEM_ID = 5920000;

    @Override
    public void handlePacket(InPacket p, Client c) {
        Character chr = c.getPlayer();
        if (chr == null) {
            return;
        }
        int action = p.readByte();
        switch (action) {
            case ACTION_REQUEST -> sendData(c, chr);
            case ACTION_SAVE -> {
                int slot = p.readByte();
                int type = p.readByte();
                handleSave(c, chr, slot, type);
            }
            case ACTION_APPLY -> {
                int slot = p.readByte();
                int type = p.readByte();
                handleApply(c, chr, slot, type);
            }
            case ACTION_DELETE -> {
                int slot = p.readByte();
                int type = p.readByte();
                handleDelete(c, chr, slot, type);
            }
            case ACTION_UNLOCK -> {
                int itemPos = p.readShort();
                handleUnlock(c, chr, itemPos);
            }
            default -> {
            }
        }
    }

    private void sendData(Client c, Character chr) {
        int unlocked = BeautyStorage.getUnlockedSlots(chr.getId());
        List<BeautyData> rows = BeautyStorage.loadAll(chr.getId());
        c.sendPacket(BeautyPackets.beautyData(unlocked, rows));
    }

    private void handleUnlock(Client c, Character chr, int itemPos) {
        int unlocked = BeautyStorage.getUnlockedSlots(chr.getId());
        if (unlocked < SLOT_COUNT) {
            Item item = chr.getInventory(InventoryType.CASH).getItem((short) itemPos);
            if (item != null && item.getItemId() == BEAUTY_ITEM_ID) {
                InventoryManipulator.removeFromSlot(c, InventoryType.CASH, (short) itemPos, (short) 1, false);
                BeautyStorage.setUnlockedSlots(chr.getId(), unlocked + 1);
            }
        }
        sendData(c, chr);
    }

    private void handleSave(Client c, Character chr, int slot, int type) {
        if (!isValid(slot, type)) {
            return;
        }
        if (slot >= BeautyStorage.getUnlockedSlots(chr.getId())) {
            return;
        }
        Inventory equipped = chr.getInventory(InventoryType.EQUIPPED);
        BeautyData data = new BeautyData(
                chr.getId(), slot, type,
                chr.getGender(), chr.getSkinColor().getId(), chr.getHair(), chr.getFace(),
                equippedId(equipped, -1), equippedId(equipped, -5), equippedId(equipped, -6),
                equippedId(equipped, -7), equippedId(equipped, -11), equippedId(equipped, -111));
        BeautyStorage.save(data);
        sendData(c, chr);
    }

    private void handleApply(Client c, Character chr, int slot, int type) {
        if (!isValid(slot, type)) {
            return;
        }
        BeautyData match = null;
        for (BeautyData d : BeautyStorage.loadAll(chr.getId())) {
            if (d.slot() == slot && d.type() == type) {
                match = d;
                break;
            }
        }
        if (match == null) {
            return;
        }
        if (type == TYPE_HAIR) {
            chr.setHair(match.hair());
            chr.updateSingleStat(Stat.HAIR, match.hair());
        } else if (type == TYPE_FACE) {
            chr.setFace(match.face());
            chr.updateSingleStat(Stat.FACE, match.face());
        } else {
            SkinColor sc = SkinColor.getById(match.skin());
            if (sc != null) {
                chr.setSkinColor(sc);
                chr.updateSingleStat(Stat.SKIN, match.skin());
            }
        }
        chr.equipChanged();
        sendData(c, chr);
    }

    private void handleDelete(Client c, Character chr, int slot, int type) {
        if (!isValid(slot, type)) {
            return;
        }
        BeautyStorage.delete(chr.getId(), slot, type);
        sendData(c, chr);
    }

    private static boolean isValid(int slot, int type) {
        return slot >= 0 && slot < SLOT_COUNT
                && (type == TYPE_HAIR || type == TYPE_FACE || type == TYPE_SKIN);
    }

    private static int equippedId(Inventory equipped, int slot) {
        Item item = equipped.getItem((short) slot);
        return item == null ? 0 : item.getItemId();
    }
}
