package net.server.channel.handlers;

import client.Character;
import client.Client;
import constants.id.ItemId;
import constants.id.NpcId;
import constants.inventory.ItemConstants;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import scripting.npc.NPCScriptManager;

public final class GachaponTicketHandler extends AbstractPacketHandler {
    @Override
    public void handlePacket(InPacket p, Client c) {
        Character player = c.getPlayer();
        if (player == null || !c.isLoggedIn() || !player.isLoggedinWorld()) {
            return;
        }

        p.readShort(); // slot position — server validates by count, not position

        if (player.getInventory(ItemConstants.getInventoryType(ItemId.LOCAL_GACHAPON_TICKET))
                .countById(ItemId.LOCAL_GACHAPON_TICKET) < 1) {
            player.dropMessage(1, "You don't have a Gachapon ticket.");
            return;
        }

        // Start the location-selection NPC script. The NPC ID passed here is
        // used as the script's cm.getNpc() value; the script overrides the
        // actual loot-table NPC via cm.doGachaponAt(selectedNpcId).
        NPCScriptManager.getInstance().start(c, NpcId.GACHAPON_HENESYS, "gachaponFromInventory", null);
    }
}
