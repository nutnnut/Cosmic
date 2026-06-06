package net.server.channel.handlers;

import client.Character;
import client.Client;
import client.DamageSkinCatalog;
import client.DamageSkinInventory;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.PacketCreator;

public final class DamageSkinPurchaseHandler extends AbstractPacketHandler {
    private static final Logger log = LoggerFactory.getLogger(DamageSkinPurchaseHandler.class);

    private static final int OP_PURCHASE = 2;

    @Override
    public void handlePacket(InPacket p, Client c) {
        int skinId = p.readInt();
        Character chr = c.getPlayer();
        if (chr == null) return;

        final int curMesos = chr.getMeso();

        if (skinId == DamageSkinInventory.DEFAULT_SKIN_ID) {
            c.sendPacket(PacketCreator.damageSkinResult(OP_PURCHASE, false, skinId, curMesos));
            return;
        }
        long priceL = DamageSkinCatalog.getPrice(skinId);
        if (priceL < 0) {
            log.info("chr {} tried to buy unknown damage skin {}", chr.getId(), skinId);
            c.sendPacket(PacketCreator.damageSkinResult(OP_PURCHASE, false, skinId, curMesos));
            return;
        }
        DamageSkinInventory inv = chr.getDamageSkinInventory();
        if (inv.ownsSkin(skinId)) {
            c.sendPacket(PacketCreator.damageSkinResult(OP_PURCHASE, false, skinId, curMesos));
            return;
        }
        int priceI = priceL > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) priceL;
        if (curMesos < priceI) {
            c.sendPacket(PacketCreator.damageSkinResult(OP_PURCHASE, false, skinId, curMesos));
            return;
        }

        try {
            boolean inserted = inv.addSkin(chr.getId(), skinId);
            if (!inserted) {
                c.sendPacket(PacketCreator.damageSkinResult(OP_PURCHASE, false, skinId, curMesos));
                return;
            }
        } catch (Exception e) {
            log.error("damage skin insert failed for chr {} skin {}", chr.getId(), skinId, e);
            c.sendPacket(PacketCreator.damageSkinResult(OP_PURCHASE, false, skinId, curMesos));
            return;
        }

        chr.gainMeso(-priceI, true);
        c.sendPacket(PacketCreator.damageSkinResult(OP_PURCHASE, true, skinId, chr.getMeso()));
    }
}
