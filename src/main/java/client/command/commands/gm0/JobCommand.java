/*
    This file is part of the HeavenMS MapleStory Server, commands OdinMS-based
    Copyleft (L) 2016 - 2019 RonanLana

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

/*
   @Author: Arthur L - Refactored command content into modules
*/
package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.Job;
import client.command.Command;
import constants.id.NpcId;

public class JobCommand extends Command {
    {
        setDescription("Advance to your next job (GMs: @job <id> [IGN] to set a job directly).");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();

        // Players (and GMs with no args): open the Maple Administrator advancement dialog. It only
        // advances if the level requirement for the next job is met (see scripts/npc/9010000.js).
        if (params.length == 0 || !player.isGM()) {
            c.getAbstractPlayerInteraction().openNpc(NpcId.MAPLE_ADMINISTRATOR, "9010000");
            return;
        }

        // GM-only direct-set utility: @job <jobid> [IGN]
        if (params.length == 1) {
            int jobid = Integer.parseInt(params[0]);
            if (jobid < 0 || jobid >= 2200) {
                player.message("Jobid " + jobid + " is not available.");
                return;
            }

            player.changeJob(Job.getById(jobid));
            player.equipChanged();
        } else if (params.length == 2) {
            Character victim = c.getWorldServer().getPlayerStorage().getCharacterByName(params[0]);

            if (victim != null) {
                int jobid = Integer.parseInt(params[1]);
                if (jobid < 0 || jobid >= 2200) {
                    player.message("Jobid " + jobid + " is not available.");
                    return;
                }

                victim.changeJob(Job.getById(jobid));
                player.equipChanged();
            } else {
                player.message("Player '" + params[0] + "' could not be found.");
            }
        } else {
            player.message("Syntax: !job <job id> <opt: IGN of another person>");
        }
    }
}
