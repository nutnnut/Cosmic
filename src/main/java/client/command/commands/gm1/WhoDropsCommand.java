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
package client.command.commands.gm1;

import client.Character;
import client.Client;
import client.command.Command;
import constants.id.NpcId;
import server.ItemInformationProvider;
import server.life.MonsterInformationProvider;
import tools.DatabaseConnection;
import tools.Pair;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;

public class WhoDropsCommand extends Command {
    {
        setDescription("Show what drops an item.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length < 1) {
            player.dropMessage(5, "Please do @whodrops <item name>");
            return;
        }

        if (!c.tryacquireClient()) {
            player.dropMessage(5, "Please wait a while for your request to be processed.");
            return;
        }

        try {
            String searchString = player.getLastCommandMessage();
            StringBuilder output = new StringBuilder();
            ArrayList<Pair<Integer, String>> idNameList = ItemInformationProvider.getInstance().getItemDataByName(searchString);
            if (idNameList.isEmpty()) {
                player.dropMessage(5, "The item you searched for doesn't exist.");
                return;
            }
            int count = 0;
            for (int i = 0; i < idNameList.size() && count < 10; i++) {
                Pair<Integer, String> data = idNameList.get(i);
                Integer itemId = data.getLeft();
                if (itemId < 1000000) { // hairs, faces, not actual items, will cause a crash
                    continue;
                }
                count++;
                output.append("#v").append(itemId).append("#"); // Item Icon
                output.append("#z").append(itemId).append("#"); // Item Name + Stats
                output.append(" - #b").append(itemId); // Item ID
                output.append("#k:\r\n");
                try (Connection con = DatabaseConnection.getConnection();
                     PreparedStatement ps = con.prepareStatement("SELECT dropperid FROM drop_data WHERE itemid = ? LIMIT 50")) {
                    ps.setInt(1, itemId);

                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String resultName = MonsterInformationProvider.getInstance().getMobNameFromId(rs.getInt("dropperid"));
                            if (resultName != null) {
                                output.append(resultName).append(", ");
                            }
                        }
                    }
                } catch (Exception e) {
                    player.dropMessage(6, "There was a problem retrieving the required data. Please try again.");
                    e.printStackTrace();
                    return;
                }
                output.append("\r\n\r\n");
            }
            output.insert(0, "Showing " + count + " of " + idNameList.size() + " results:\r\n\r\n");
            c.getAbstractPlayerInteraction().npcTalk(NpcId.MAPLE_ADMINISTRATOR, output.toString());
        } finally {
            c.releaseClient();
        }
    }
}
