/*
    Android/bot equip window — server side of the Kaentake client CP_BotEquip /
    LP_BotEquip protocol (see ANDROID_EQUIP_SERVER_HANDOFF + tooltip handoff).

    The window shows/edits a player's OWNED BOTS (real Characters in
    server.bots.BotManager). The client only REQUESTS; the server validates and
    is the source of truth, replying with a fresh RESP_SNAPSHOT.

    Wire (client -> server, RecvOpcode.BOT_EQUIP):
      byte action  0=OPEN 1=MOVE 2=LIST 3=GIVE 4=TAKE
      OPEN : byte botIndex
      MOVE : byte botIndex, short type, short oldPos, short newPos, short count
      LIST : (nothing)
      GIVE : byte botIndex, short srcType, short srcPos, short dstPos   (player->bot)
      TAKE : byte botIndex, short srcType, short srcPos, short dstPlayerPos (bot->player)
    Replies (PacketCreator.botEquipSnapshot / botEquipList) via SendOpcode.BOT_EQUIP.
*/
package net.server.channel.handlers;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import server.ItemInformationProvider;
import server.bots.BotManager;
import config.YamlConfig;
import tools.PacketCreator;

import java.util.List;

public final class BotEquipHandler extends AbstractPacketHandler {

    private static final int REQ_OPEN = 0, REQ_MOVE = 1, REQ_LIST = 2, REQ_GIVE = 3, REQ_TAKE = 4, REQ_SORT = 5;

    @Override
    public void handlePacket(InPacket p, Client c) {
        Character player = c.getPlayer();
        if (player == null) {
            return;
        }
        int action = p.readByte();

        if (action == REQ_LIST) {
            int count = BotManager.getInstance().spawnedBotCount(player.getId());
            c.sendPacket(PacketCreator.botEquipList(count));
            return;
        }

        int botIndex = p.readByte();
        Character bot = resolveBot(player, botIndex);
        if (bot == null) {
            return;   // not an owned bot — ignore (treat all client input as hostile)
        }

        try {
            switch (action) {
                case REQ_OPEN:
                    break;   // just snapshot, below
                case REQ_MOVE: {
                    int type = p.readShort();
                    int oldPos = p.readShort();
                    int newPos = p.readShort();
                    p.readShort();   // count (always 1)
                    applyBotMove(bot, type, oldPos, newPos);
                    break;
                }
                case REQ_GIVE: {
                    int srcType = p.readShort();
                    int srcPos = p.readShort();
                    p.readShort();   // dstPos (auto)
                    giveToBot(c, player, bot, srcType, srcPos);
                    break;
                }
                case REQ_TAKE: {
                    int srcType = p.readShort();
                    int srcPos = p.readShort();
                    p.readShort();   // dstPlayerPos (auto)
                    takeFromBot(c, player, bot, srcType, srcPos);
                    break;
                }
                case REQ_SORT: {
                    int tab = p.readShort();
                    sortAndMerge(bot, tab);
                    break;
                }
                default:
                    return;
            }
        } catch (Exception e) {
            // never let a malformed request crash the channel packet thread
        }

        c.sendPacket(PacketCreator.botEquipSnapshot(botIndex, bot));
    }

    // botIndex 1..5 -> the player's Nth owned bot (stable spawn order), or null.
    private static Character resolveBot(Character player, int botIndex) {
        if (botIndex < 1) {
            return null;
        }
        List<Character> bots = BotManager.getInstance().getOwnedBotCharacters(player.getId());
        if (botIndex > bots.size()) {
            return null;
        }
        return bots.get(botIndex - 1);
    }

    // Bot-internal equip / unequip / reorder. Routes through the canonical
    // InventoryManipulator targeting the BOT's own client, so the server's normal
    // equip validation (job/level/gender/stats) applies and the bot's look update
    // is broadcast automatically.
    private static void applyBotMove(Character bot, int type, int oldPos, int newPos) {
        Client bc = bot.getClient();
        if (bc == null) {
            return;
        }
        if (oldPos < 0 && newPos > 0) {                 // unequip: worn -> bag
            InventoryManipulator.unequip(bc, (short) oldPos, (short) newPos);
        } else if (oldPos > 0 && newPos < 0) {          // equip: bag -> worn
            InventoryManipulator.equip(bc, (short) oldPos, (short) newPos);
        } else if (oldPos > 0 && newPos > 0) {          // reorder within a tab
            InventoryType it = InventoryType.getByType((byte) type);
            if (it != null) {
                InventoryManipulator.move(bc, it, (short) oldPos, (short) newPos);
            }
        }
        // worn -> worn (both negative) is not exposed by the window; ignore.
    }

    // PLAYER -> BOT transfer. Add a copy to the bot first; only remove from the
    // player if the add succeeded (so a full bot never loses the player's item).
    private static void giveToBot(Client c, Character player, Character bot, int srcType, int srcPos) {
        Client bc = bot.getClient();
        if (bc == null || srcType < 1 || srcType > 5 || srcPos < 1) {
            return;
        }
        InventoryType type = InventoryType.getByType((byte) srcType);
        if (type == null) {
            return;
        }
        Inventory inv = player.getInventory(type);
        Item item = inv.getItem((short) srcPos);
        if (item == null) {
            return;
        }
        // Cash items carry cash-shop SN/ownership semantics; never move them between a player and a bot
        // (taking a bot's worn cash cosmetic back would mint a free copy). Blocked in both directions.
        if (ItemInformationProvider.getInstance().isCash(item.getItemId())) {
            return;
        }
        // All-or-nothing: only proceed if the bot can hold the FULL quantity. addFromDrop does a PARTIAL
        // add for stackables and returns false for the leftover; without this guard we'd add part to the
        // bot but skip the source removal (false) -> the player keeps the whole stack = duplication.
        if (!InventoryManipulator.checkSpace(bc, item.getItemId(), item.getQuantity(), item.getOwner())) {
            return;
        }
        if (InventoryManipulator.addFromDrop(bc, item.copy(), false)) {
            InventoryManipulator.removeFromSlot(c, type, (short) srcPos, item.getQuantity(), false);
        }
    }

    // BOT -> PLAYER transfer (reverse of give). srcPos < 0 = a worn slot
    // (EQUIPPED); otherwise the bot inventory tab srcType.
    private static void takeFromBot(Client c, Character player, Character bot, int srcType, int srcPos) {
        Client bc = bot.getClient();
        if (bc == null) {
            return;
        }
        boolean worn = srcPos < 0;
        InventoryType type = worn ? InventoryType.EQUIPPED : InventoryType.getByType((byte) srcType);
        if (type == null) {
            return;
        }
        Inventory inv = bot.getInventory(type);
        Item item = inv.getItem((short) srcPos);
        if (item == null) {
            return;
        }
        if (ItemInformationProvider.getInstance().isCash(item.getItemId())) {
            return;   // see giveToBot: cash items don't transfer via the bot window
        }
        // All-or-nothing (see giveToBot): avoid the partial-add duplication on a full player inventory.
        if (!InventoryManipulator.checkSpace(c, item.getItemId(), item.getQuantity(), item.getOwner())) {
            return;
        }
        if (InventoryManipulator.addFromDrop(c, item.copy(), false)) {
            InventoryManipulator.removeFromSlot(bc, type, (short) srcPos, item.getQuantity(), false);
            if (worn) {
                bot.equipChanged();   // refresh the bot's on-screen look after losing worn gear
            }
        }
    }

    // Sort + merge one of the bot's inventory tabs — RonanLana's merge-stacks then
    // compact-blanks (the same logic as InventoryMergeHandler's non-slot-lock path),
    // targeting the bot's own client/inventory.
    private static void sortAndMerge(Character bot, int tabId) {
        if (!YamlConfig.config.server.USE_ITEM_SORT || tabId < 1 || tabId > 5) {
            return;
        }
        Client bc = bot.getClient();
        InventoryType type = InventoryType.getByType((byte) tabId);
        if (bc == null || type == null) {
            return;
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Inventory inv = bot.getInventory(type);
        inv.lockInventory();
        try {
            // 1. merge identical stacks up to slotMax
            for (short dst = 1; dst <= inv.getSlotLimit(); dst++) {
                Item dstItem = inv.getItem(dst);
                if (dstItem == null) {
                    continue;
                }
                for (short src = (short) (dst + 1); src <= inv.getSlotLimit(); src++) {
                    Item srcItem = inv.getItem(src);
                    if (srcItem == null || dstItem.getItemId() != srcItem.getItemId()) {
                        continue;
                    }
                    if (dstItem.getQuantity() == ii.getSlotMax(bc, inv.getItem(dst).getItemId())) {
                        break;
                    }
                    InventoryManipulator.move(bc, type, src, dst);
                }
            }
            // 2. compact: pull items down into free slots
            inv = bot.getInventory(type);
            boolean sorted = false;
            while (!sorted) {
                short freeSlot = inv.getNextFreeSlot();
                if (freeSlot != -1) {
                    short itemSlot = -1;
                    for (short i = (short) (freeSlot + 1); i <= inv.getSlotLimit(); i++) {
                        if (inv.getItem(i) != null) {
                            itemSlot = i;
                            break;
                        }
                    }
                    if (itemSlot > 0) {
                        InventoryManipulator.move(bc, type, itemSlot, freeSlot);
                    } else {
                        sorted = true;
                    }
                } else {
                    sorted = true;
                }
            }
        } finally {
            inv.unlockInventory();
        }
    }
}
