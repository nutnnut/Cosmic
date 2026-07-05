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

package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.command.Command;

/**
 * Toggle "hidden from bots": while set, this GM does not count as an observer inside
 * {@code MapleMap.isObservedByPlayer()}, so bots on their map stay in the unobserved (LOD1)
 * simulation. Lets the owner walk around and watch bots in their raw coarse state for debugging
 * without promoting maps to full fidelity. Distinct from {@code !hide} (which stays a real observer).
 */
public class HideBotCommand extends Command {
    {
        setDescription("Toggle being an observer for bot LOD (bots stay unobserved near you).");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        boolean now = !player.isHiddenFromBots();
        player.setHiddenFromBots(now);
        player.yellowMessage("You are " + (now ? "now hidden from bots (they will not treat you as an observer)."
                : "no longer hidden from bots."));
    }
}
