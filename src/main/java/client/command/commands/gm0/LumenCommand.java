package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import service.MilestoneRewardService;

public class LumenCommand extends Command {
    {
        setDescription("Claim available level milestone rewards.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character chr = c.getPlayer();

        if (params.length >= 1 && params[0].equals("weapon")) {
            if (params.length < 2) {
                chr.yellowMessage("Syntax: @lumen weapon <type> [reverse]");
                return;
            }
            boolean reverse = false;
            for (int i = 2; i < params.length; i++) {
                if (params[i].equals("reverse")) {
                    reverse = true;
                }
            }
            MilestoneRewardService.claimWeapon(chr, params[1], reverse);
            return;
        }

        MilestoneRewardService.claimAvailable(chr);
    }
}
