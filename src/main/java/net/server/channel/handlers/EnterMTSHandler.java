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
package net.server.channel.handlers;

import client.Character;
import client.Client;
import constants.id.MapId;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import server.maps.FieldLimit;
import server.maps.MiniDungeonInfo;
import tools.PacketCreator;

public final class EnterMTSHandler extends AbstractPacketHandler {

    @Override
    public void handlePacket(InPacket p, Client c) {
        Character chr = c.getPlayer();

        // The GUI "Trade" (MTS) button teleports the player to the Free Market.
        if (chr.getEventInstance() != null) {
            c.sendPacket(PacketCreator.serverNotice(5, "You can't enter the Free Market while registered on an event."));
            c.sendPacket(PacketCreator.enableActions());
            return;
        }

        if (MiniDungeonInfo.isDungeonMap(chr.getMapId())) {
            c.sendPacket(PacketCreator.serverNotice(5, "You can't enter the Free Market from inside a Mini-Dungeon."));
            c.sendPacket(PacketCreator.enableActions());
            return;
        }

        if (FieldLimit.CANNOTMIGRATE.check(chr.getMap().getFieldLimit())) {
            chr.dropMessage(1, "You can't do it here in this map.");
            c.sendPacket(PacketCreator.enableActions());
            return;
        }

        if (!chr.isAlive()) {
            c.sendPacket(PacketCreator.enableActions());
            return;
        }

        chr.changeMap(MapId.FM_ENTRANCE);
        c.sendPacket(PacketCreator.enableActions());
    }
}
