package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import constants.id.NpcId;

public class StyleCommand extends Command {
    {
        setDescription("Open the style wizard to preview and change your hair, face, skin, and eye color.");
    }

    @Override
    public void execute(Client c, String[] params) {
        c.getAbstractPlayerInteraction().openNpc(NpcId.MAPLE_ADMINISTRATOR, "style_command");
    }
}
