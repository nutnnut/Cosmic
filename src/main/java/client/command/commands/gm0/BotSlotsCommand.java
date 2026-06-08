/*
    LumenMS — @botslots: opens the Bot Slots dialog (scripts/npc/bot_slots.js) via the Maple
    Administrator NPC. The dialog charges NX to expand every spawned bot's inventory tabs.
*/
package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import constants.id.NpcId;

public class BotSlotsCommand extends Command {
    {
        setDescription("Open the Bot Slots dialog to expand your bots' inventory with NX.");
    }

    @Override
    public void execute(Client c, String[] params) {
        c.getAbstractPlayerInteraction().openNpc(NpcId.MAPLE_ADMINISTRATOR, "bot_slots");
    }
}
