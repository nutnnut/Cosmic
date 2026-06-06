package client.command.commands.gm2;

import client.Client;
import client.command.Command;
import constants.id.NpcId;

public class JobsCommand extends Command {
    {
        setDescription("Open the job selection dialog.");
    }

    @Override
    public void execute(Client c, String[] params) {
        c.getAbstractPlayerInteraction().openNpc(NpcId.MAPLE_ADMINISTRATOR, "jobs");
    }
}
