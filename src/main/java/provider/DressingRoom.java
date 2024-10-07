/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

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
package provider;

import constants.inventory.EquipStats;
import constants.inventory.EquipType;
import net.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import tools.Pair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static provider.wz.WZFiles.STRING;

public class DressingRoom {
    private static final Logger log = LoggerFactory.getLogger(Server.class);
    private static final DataProvider stringProvider = DataProviderFactory.getDataProvider(STRING);
    private static final ItemInformationProvider ii = ItemInformationProvider.getInstance();
    private static final Map<EquipType, List<EquipStats>> equipsByType = new HashMap<>(); // Type -> List of Item stats

    public static void load() {
        long start = System.currentTimeMillis();
        Data itemsData = stringProvider.getData("Eqp.img").getChildByPath("Eqp");
        List<Pair<Integer, String>> idToNamePairs = new ArrayList<>();
        for (Data eqpType : itemsData.getChildren()) {
            for (Data itemFolder : eqpType.getChildren()) {
                int itemId = Integer.parseInt(itemFolder.getName());
                Map<String, Integer> stats = ii.getEquipStats(itemId);
                if (stats == null) {
                    continue;
                }

                EquipType equipType = EquipType.getEquipTypeById(itemId);
                equipsByType.computeIfAbsent(equipType, k -> new ArrayList<>()).add(new EquipStats(itemId, stats));
            }
        }
        log.info(String.format("Loaded Dressing Room in %dms.", System.currentTimeMillis() - start));
    }

    public static List<EquipStats> getEquipsByType(EquipType type) {
        return equipsByType.getOrDefault(type, Collections.emptyList());
    }
}
