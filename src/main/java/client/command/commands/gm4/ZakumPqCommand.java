/*
    This file is part of the HeavenMS MapleStory Server, commands OdinMS-based
    Copyleft (L) 2016 - 2019 RonanLana

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import server.quest.ZakumPrequest;

import java.util.ArrayList;
import java.util.List;

public class ZakumPqCommand extends Command {
    {
        setDescription("Complete the Zakum prequest for yourself and your party.");
    }

    static List<Character> targets(Character player) {
        List<Character> targets = new ArrayList<>();
        targets.add(player);

        if (player.getParty() != null) {
            for (Character member : player.getPartyMembersOnline()) {
                if (member != null && member != player) {
                    targets.add(member);
                }
            }
        }

        return targets;
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        List<Character> members = targets(player);
        int ticketFailures = 0;

        for (Character member : members) {
            if (!ZakumPrequest.complete(member)) {
                ticketFailures++;
            }
        }

        player.dropMessage(5, "Zakum prequest completed for " + (members.size() - ticketFailures)
                + "/" + members.size() + " party member(s).");
        if (ticketFailures > 0) {
            player.dropMessage(5, ticketFailures + " member(s) could not receive Eyes of Fire because their ETC inventory is full.");
        }
    }
}
