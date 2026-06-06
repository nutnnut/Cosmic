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

package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import config.YamlConfig;
import tools.PacketCreator;

public class SetNxDropRateCommand extends Command {
    {
        setDescription("Set the global NX-per-kill drop chance (percent).");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length < 1) {
            player.yellowMessage("Syntax: !setnxdroprate <percent 0-100>");
            return;
        }

        int rate;
        try {
            rate = Integer.parseInt(params[0]);
        } catch (NumberFormatException e) {
            player.yellowMessage("Syntax: !setnxdroprate <percent 0-100>");
            return;
        }

        rate = Math.max(0, Math.min(100, rate));
        YamlConfig.config.server.NX_KILL_DROP_RATE = rate;
        c.getWorldServer().broadcastPacket(PacketCreator.serverNotice(6, "[Rate] NX drop rate has been changed to " + rate + "% per kill."));
    }
}
