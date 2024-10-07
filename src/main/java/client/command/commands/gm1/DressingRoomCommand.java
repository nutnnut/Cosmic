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
import client.command.CommandContext;
import constants.id.NpcId;
import constants.inventory.EquipStats;
import constants.inventory.EquipType;
import provider.DressingRoom;
import server.ItemInformationProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class DressingRoomCommand extends Command {
    {
        setDescription("Find equipments with conditions");
    }

    @Override
    public void execute(Client c, String[] params, CommandContext ctx) {
        Character player = c.getPlayer();
        if (params.length < 2) {
            player.dropMessage(5, "Please do !dress <type> <job> <optional req.level>");
            return;
        }

        EquipType equipType = EquipType.getEquipTypeByString(params[0]);
        if (equipType == EquipType.UNDEFINED) {
            player.dropMessage(5, "Unknown EquipType");
            player.dropMessage(5, "Possible EquipTypes are:");
            List<String> unusedTypes = Arrays.asList("UNDEFINED", "FACE", "HAIR");

            for (EquipType type : EquipType.values()) {
                if (type.name().startsWith("PET") || unusedTypes.contains(type.name())) {
                    continue;
                }
                player.dropMessage(5, type.name());
            }
            return;
        }
        String param1 = params[1].toLowerCase();
        int job = 0; // Default job / Beginner

        if (param1.equals("1") || param1.startsWith("w")) {
            job = 1;
        } else if (param1.equals("2") || param1.startsWith("m")) {
            job = 2;
        } else if (param1.equals("3") || param1.startsWith("b")) {
            job = 3;
        } else if (param1.equals("4") || param1.startsWith("t")) {
            job = 4;
        } else if (param1.equals("5") || param1.startsWith("p")) {
            job = 5;
        }

        int forLevel = 0;
        try {
            forLevel = Integer.parseInt(params[2]);
        } catch (Exception ignored) {
        }

        if (c.tryacquireClient()) {
            try {
                String output = "";
                int count = 0;
                List<EquipStats> equips = DressingRoom.getEquipsByType(equipType);
                ItemInformationProvider ii = ItemInformationProvider.getInstance();

                List<EquipStats> result = new ArrayList<>();
                for (EquipStats equip : equips) {
                    if (ii.isCash(equip.getItemId())) {
                        continue;
                    }
                    Map<String, Integer> stats = equip.getStats();
                    int reqJob = stats.get("reqJob");
                    // Filter by job
                    if (reqJob == 0 || (reqJob & (1 << (job - 1))) != 0) { // Check if bit at job-th position is 1

                        // Filter by level
                        int reqLevel = stats.getOrDefault("reqLevel", 0);
                        if (forLevel == 0 || reqLevel <= forLevel) {
                            result.add(equip);
                        }
                    }
                }

                result.sort(Comparator.comparingInt((EquipStats equip) ->
//                                equip.getStats().getOrDefault("reqLevel", 0)) // Default to 0 if reqLevel is null
//                        .thenComparingInt(equip ->
                                equip.getStats().getOrDefault("PAD", 0) * 3
                                        + equip.getStats().getOrDefault("MAD", 0)
                                        + equip.getStats().getOrDefault("STR", 0)
                                        + equip.getStats().getOrDefault("DEX", 0)
                                        + equip.getStats().getOrDefault("INT", 0)
                                        + equip.getStats().getOrDefault("LUK", 0)
                                        + equip.getStats().getOrDefault("tuc", 0) * 5
                        ).reversed());

                for (EquipStats equip : result) {
                    if (count >= 300) { // limit to reduce spam
                        break;
                    }
                    int itemId = equip.getItemId();

                    output += "#L" + itemId + "#"; // Dialog Selector
                    output += "#v" + itemId + "#"; // Item Icon
                    output += "#z" + itemId + "#"; // Item Name + Stats
                    output += " - #b" + itemId + "\r\n"; // Item ID
                    count++;
                }
                if (count <= 0) {
                    player.dropMessage(5, "The item you searched for doesn't exist.");
                    return;
                }

                output += "#k" + count + " Results Found\r\n_";

                c.getAbstractPlayerInteraction().npcTalk(NpcId.MAPLE_ADMINISTRATOR, output);
            } finally {
                c.releaseClient();
            }
        } else {
            player.dropMessage(5, "Please wait a while for your request to be processed.");
        }
    }
}
