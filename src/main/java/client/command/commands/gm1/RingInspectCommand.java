/*
    LumenMS — GM-only inspect of a target player's Soul Vessel state:
    per-class stack counts plus the currently-derived stat totals. Useful for
    support tickets ("my ring stats look wrong") and tuning.

    Target lookup mirrors DcCommand: world storage first (any channel), then
    channel storage, then the current map. If the target isn't online we fall
    back to a DB-only view of stacks + computed stats (no inventory inspection
    in that case).
*/
package client.command.commands.gm1;

import client.Character;
import client.Client;
import client.command.Command;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import service.RebirthRingService;
import service.RebirthRingService.RingStats;
import service.RebirthRingService.Stacks;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class RingInspectCommand extends Command {
    {
        setDescription("Inspect a player's rebirth ring (stacks + derived stats). Syntax: !ringinspect <playername>");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        if (params.length < 1) {
            gm.yellowMessage("Syntax: !ringinspect <playername>");
            return;
        }

        String name = params[0];
        Character target = c.getWorldServer().getPlayerStorage().getCharacterByName(name);
        if (target == null) {
            target = c.getChannelServer().getPlayerStorage().getCharacterByName(name);
        }
        if (target == null && gm.getMap() != null) {
            target = gm.getMap().getCharacterByName(name);
        }

        int characterId = (target != null) ? target.getId() : lookupCharIdByName(name);
        if (characterId <= 0) {
            gm.yellowMessage("No character named '" + name + "' found (online or in DB).");
            return;
        }

        Stacks stacks = RebirthRingService.getStacks(characterId);
        RingStats derived = RebirthRingService.previewStats(stacks);

        StringBuilder sb = new StringBuilder();
        sb.append("=== Ring inspect: ").append(name);
        sb.append(target != null ? " (online)" : " (offline, DB only)").append(" ===");
        gm.dropMessage(6, sb.toString());

        gm.dropMessage(6, String.format(
                "Stacks  W=%d  M=%d  B=%d  T=%d  P=%d  (total=%d)",
                stacks.warrior, stacks.magician, stacks.bowman, stacks.thief, stacks.pirate,
                stacks.total()));

        gm.dropMessage(6, String.format(
                "Derived STR+%d DEX+%d INT+%d LUK+%d  HP+%d MP+%d  Avoid+%d Speed+%d",
                derived.str, derived.dex, derived._int, derived.luk,
                derived.hp, derived.mp, derived.avoid, derived.speed));

        if (target != null) {
            describeInventoryRing(gm, target);
        } else {
            gm.dropMessage(6, "(offline — equip inventory not inspected; relog to see equip state)");
        }
    }

    /** Walk the target's EQUIP and EQUIPPED inventories for the Soul Vessel and dump its absolute stats. */
    private void describeInventoryRing(Character gm, Character target) {
        Equip ring = findRingIn(target.getInventory(InventoryType.EQUIPPED));
        boolean equipped = ring != null;
        if (ring == null) {
            ring = findRingIn(target.getInventory(InventoryType.EQUIP));
        }
        if (ring == null) {
            gm.dropMessage(6, "Ring not in possession.");
            return;
        }
        gm.dropMessage(6, String.format(
                "Equip %s owner='%s'  STR=%d DEX=%d INT=%d LUK=%d HP=%d MP=%d Avoid=%d Speed=%d",
                equipped ? "EQUIPPED" : "inventory",
                ring.getOwner(),
                ring.getStr(), ring.getDex(), ring.getInt(), ring.getLuk(),
                ring.getHp(), ring.getMp(), ring.getAvoid(), ring.getSpeed()));
    }

    private Equip findRingIn(Inventory inv) {
        if (inv == null) return null;
        for (Item it : inv.list()) {
            if (it instanceof Equip && it.getItemId() == RebirthRingService.SOUL_VESSEL_ITEM_ID) {
                return (Equip) it;
            }
        }
        return null;
    }

    private int lookupCharIdByName(String name) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT id FROM characters WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("id");
                }
            }
        } catch (SQLException ignore) {
        }
        return -1;
    }
}
