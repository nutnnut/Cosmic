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
    // volatile + atomic publish in load(): the Dressing Room is loaded on a background thread post-
    // startup (it's a non-core cosmetic feature), so readers must never see a half-built map. They
    // get an empty map until the build completes and assigns it in one go.
    private static volatile Map<EquipType, List<EquipStats>> equipsByType = Collections.emptyMap();

    public static void load() {
        long start = System.currentTimeMillis();
        Data itemsData = stringProvider.getData("Eqp.img").getChildByPath("Eqp");
        Map<EquipType, List<EquipStats>> built = new HashMap<>();
        for (Data eqpType : itemsData.getChildren()) {
            for (Data itemFolder : eqpType.getChildren()) {
                int itemId = Integer.parseInt(itemFolder.getName());
                Map<String, Integer> stats = ii.getEquipStats(itemId);
                if (stats == null) {
                    continue;
                }

                EquipType equipType = EquipType.getEquipTypeById(itemId);
                built.computeIfAbsent(equipType, k -> new ArrayList<>()).add(new EquipStats(itemId, stats));
            }
        }
        equipsByType = built; // single atomic publish — readers flip from empty to fully-loaded
        log.info(String.format("Loaded Dressing Room in %dms.", System.currentTimeMillis() - start));
    }

    public static List<EquipStats> getEquipsByType(EquipType type) {
        return equipsByType.getOrDefault(type, Collections.emptyList());
    }
}
