package server.beauty;

import net.opcodes.SendOpcode;
import net.packet.OutPacket;
import net.packet.Packet;

import java.util.List;

public class BeautyPackets {
    private static final int SLOT_COUNT = 6;
    private static final int TYPE_HAIR = 0;
    private static final int TYPE_FACE = 1;
    private static final int TYPE_SKIN = 2;
    private static final int RESP_OPEN = 0;
    private static final int RESP_DATA = 1;

    public static Packet beautyOpen() {
        OutPacket p = OutPacket.create(SendOpcode.BEAUTY_RESULT);
        p.writeByte(RESP_OPEN);
        return p;
    }

    public static Packet beautyData(int unlockedSlots, List<BeautyData> rows) {
        int[] hairId = new int[SLOT_COUNT];
        int[] faceId = new int[SLOT_COUNT];
        int[] skinId = new int[SLOT_COUNT];
        boolean[] hairSaved = new boolean[SLOT_COUNT];
        boolean[] faceSaved = new boolean[SLOT_COUNT];
        boolean[] skinSaved = new boolean[SLOT_COUNT];

        for (BeautyData d : rows) {
            if (d.slot() < 0 || d.slot() >= SLOT_COUNT) {
                continue;
            }
            if (d.type() == TYPE_HAIR) {
                hairId[d.slot()] = d.hair();
                hairSaved[d.slot()] = true;
            } else if (d.type() == TYPE_FACE) {
                faceId[d.slot()] = d.face();
                faceSaved[d.slot()] = true;
            } else if (d.type() == TYPE_SKIN) {
                skinId[d.slot()] = d.skin();
                skinSaved[d.slot()] = true;
            }
        }

        OutPacket p = OutPacket.create(SendOpcode.BEAUTY_RESULT);
        p.writeByte(RESP_DATA);
        p.writeByte(unlockedSlots);

        p.writeByte(SLOT_COUNT);
        for (int i = 0; i < SLOT_COUNT; i++) {
            p.writeInt(hairId[i]);
        }
        p.writeByte(SLOT_COUNT);
        for (int i = 0; i < SLOT_COUNT; i++) {
            p.writeInt(faceId[i]);
        }
        p.writeByte(SLOT_COUNT);
        for (int i = 0; i < SLOT_COUNT; i++) {
            p.writeInt(skinId[i]);
        }
        p.writeByte(SLOT_COUNT);
        for (int i = 0; i < SLOT_COUNT; i++) {
            p.writeByte(hairSaved[i] ? 1 : 0);
        }
        p.writeByte(SLOT_COUNT);
        for (int i = 0; i < SLOT_COUNT; i++) {
            p.writeByte(faceSaved[i] ? 1 : 0);
        }
        p.writeByte(SLOT_COUNT);
        for (int i = 0; i < SLOT_COUNT; i++) {
            p.writeByte(skinSaved[i] ? 1 : 0);
        }

        p.writeByte(rows.size());
        for (BeautyData d : rows) {
            p.writeByte(d.type());
            p.writeByte(d.slot());
            p.writeByte(d.gender());
            p.writeInt(d.skin());
            p.writeInt(d.hair());
            p.writeInt(d.face());
            p.writeInt(d.hat());
            p.writeInt(d.top());
            p.writeInt(d.bottom());
            p.writeInt(d.shoes());
            p.writeInt(d.weapon());
            p.writeInt(d.cashWeapon());
        }
        return p;
    }
}
