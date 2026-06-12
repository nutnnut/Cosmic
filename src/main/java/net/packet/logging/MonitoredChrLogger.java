/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 ~ 2010 Patrick Huy <patrick.huy@frz.cc>
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License version 3
 as published by the Free Software Foundation. You may not use, modify
 or distribute this program under any other version of the
 GNU Affero General Public License.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.packet.logging;

import client.Character;
import client.Client;
import net.jcip.annotations.NotThreadSafe;
import net.opcodes.RecvOpcode;
import net.opcodes.SendOpcode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.HexTool;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Logs packets from monitored characters to a file.
 *
 * @author Alan (SharpAceX)
 */
@NotThreadSafe
public class MonitoredChrLogger {
    private static final Logger log = LoggerFactory.getLogger(MonitoredChrLogger.class);
    private static final Set<Integer> monitoredChrIds = new HashSet<>();

    /**
     * Toggle monitored status for a character id
     *
     * @return new status. true if the chrId is now monitored, otherwise false.
     */
    public static boolean toggleMonitored(int chrId) {
        if (monitoredChrIds.contains(chrId)) {
            monitoredChrIds.remove(chrId);
            return false;
        } else {
            monitoredChrIds.add(chrId);
            return true;
        }
    }

    public static Collection<Integer> getMonitoredChrIds() {
        return monitoredChrIds;
    }

    public static void logPacketIfMonitored(Client c, short packetId, byte[] packetContent) {
        Character chr = c.getPlayer();
        if (chr == null) {
            return;
        }
        if (!monitoredChrIds.contains(chr.getId())) {
            return;
        }
        RecvOpcode op = getOpcodeFromValue(Short.toUnsignedInt(packetId));
        if (isRecvBlocked(op)) {
            return;
        }

        String packet = packetContent.length > 0 ? HexTool.toHexString(packetContent) : "<empty>";
        log.info("{}-{} {}-{}", c.getAccountName(), chr.getName(), formatPacketId(packetId), packet);
    }

    static String formatPacketId(short packetId) {
        int unsignedPacketId = Short.toUnsignedInt(packetId);
        return "%d(0x%X)".formatted(unsignedPacketId, unsignedPacketId);
    }

    /**
     * Logs an outbound broadcast packet whose source is a monitored character.
     * Called from MapleMap.broadcastMessage so samples collected here are
     * broadcasts that the monitored player triggered (attacks, stance changes,
     * face expressions, buffs, etc.).
     */
    public static void logBroadcastIfMonitored(Character source, byte[] packetContent) {
        // Fast path: called from MapleMap.broadcastMessage on every map broadcast, so
        // short-circuit before hashing when no character is being monitored at all.
        if (monitoredChrIds.isEmpty()) {
            return;
        }
        if (source == null || packetContent == null || packetContent.length < 2) {
            return;
        }
        if (!monitoredChrIds.contains(source.getId())) {
            return;
        }
        int opcodeVal = (packetContent[0] & 0xFF) | ((packetContent[1] & 0xFF) << 8);
        SendOpcode op = getSendOpcodeFromValue(opcodeVal);
        if (isSendBlocked(op)) {
            return;
        }
        byte[] body = new byte[packetContent.length - 2];
        System.arraycopy(packetContent, 2, body, 0, body.length);
        String packet = body.length > 0 ? HexTool.toHexString(body) : "<empty>";
        String opStr = "%d(0x%X)".formatted(opcodeVal, opcodeVal);
        log.info("[OUT] {} {}-{}", source.getName(), opStr, packet);
    }

    private static boolean isRecvBlocked(RecvOpcode op) {
        if (op == null) {
            return false;
        }
        return switch (op) {
            case GENERAL_CHAT, TAKE_DAMAGE, MOVE_PET, MOVE_LIFE, NPC_ACTION, FACE_EXPRESSION -> true;
            default -> false;
        };
    }

    private static boolean isSendBlocked(SendOpcode op) {
        if (op == null) {
            return false;
        }
        return switch (op) {
            case MOVE_SUMMON, MOVE_PET, MOVE_MONSTER, SPAWN_PLAYER -> true;
            default -> false;
        };
    }

    private static RecvOpcode getOpcodeFromValue(int value) {
        return Arrays.stream(RecvOpcode.values())
                .filter(opcode -> value == opcode.getValue())
                .findAny()
                .orElse(null);
    }

    private static SendOpcode getSendOpcodeFromValue(int value) {
        return Arrays.stream(SendOpcode.values())
                .filter(opcode -> value == opcode.getValue())
                .findAny()
                .orElse(null);
    }
}
