package server.bots;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import server.ItemInformationProvider;
import server.maps.FieldLimit;
import server.maps.MapleMap;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

record BotMovementProfile(int totalSpeedStat, int totalJumpStat, boolean snowShoes)
        implements Serializable {
    // Serialized inside cached BotNavigationGraph instances; keep explicit so
    // cache compatibility is controlled by GRAPH_VERSION instead of compiler-generated UIDs.
    @Serial
    private static final long serialVersionUID = 1L;

    static final int BASE_TOTAL_STAT = 100;
    static final int STAT_BUCKET_SIZE = 5;
    static final int MAX_EFFECTIVE_SPEED_STAT = 200;
    static final int MAX_EFFECTIVE_JUMP_STAT = 123;
    static final BotMovementProfile BASE = new BotMovementProfile(BASE_TOTAL_STAT, BASE_TOTAL_STAT);

    BotMovementProfile {
        totalSpeedStat = bucketStat(totalSpeedStat);
        totalJumpStat = bucketStat(totalJumpStat);
        totalSpeedStat = Math.min(totalSpeedStat, MAX_EFFECTIVE_SPEED_STAT);
        totalJumpStat = Math.min(totalJumpStat, MAX_EFFECTIVE_JUMP_STAT);
    }

    BotMovementProfile(int totalSpeedStat, int totalJumpStat) {
        this(totalSpeedStat, totalJumpStat, false);
    }

    static BotMovementProfile base() {
        return BASE;
    }

    static BotMovementProfile fromCharacter(Character character) {
        if (character == null) {
            return BASE;
        }
        if (hasForcedBaseMovementStats(character)) {
            return BASE;
        }
        return new BotMovementProfile(character.getTotalMoveSpeedStat(), character.getTotalJumpStat(),
                wearsSnowShoes(character));
    }

    /** Snowshoes carry WZ {@code info/fs} (e.g. 10) on the worn shoe and cancel field
     *  slipperiness client-side — the wearer gets normal walk physics on snow/ice maps. */
    private static boolean wearsSnowShoes(Character character) {
        try {
            Item shoe = character.getInventory(InventoryType.EQUIPPED).getItem((short) -7);
            if (shoe == null) {
                return false;
            }
            Map<String, Integer> stats =
                    ItemInformationProvider.getInstance().getEquipStats(shoe.getItemId());
            return stats != null && stats.getOrDefault("fs", 0) >= 1;
        } catch (Throwable t) {
            return false; // WZ/equip data unavailable (unit tests, partial mocks)
        }
    }

    private static boolean hasForcedBaseMovementStats(Character character) {
        MapleMap map = character.getMap();
        return map != null && FieldLimit.MOVEMENTSKILLS.check(map.getFieldLimit());
    }

    private static int bucketStat(int stat) {
        int clamped = Math.max(1, stat);
        if (clamped < STAT_BUCKET_SIZE) {
            return clamped;
        }
        // Nearest bucket, not floor: a 144% bot plays on the 145 graph — halves the
        // worst-case drift between real stats and the physics/graph profile.
        return (int) (Math.round(clamped / (double) STAT_BUCKET_SIZE) * STAT_BUCKET_SIZE);
    }

    double speedMultiplier() {
        return totalSpeedStat / (double) BASE_TOTAL_STAT;
    }

    double jumpMultiplier() {
        return totalJumpStat / (double) BASE_TOTAL_STAT;
    }

    double walkVelocityPxs() {
        return BotMovementManager.cfg.WALK_VEL * speedMultiplier();
    }

    double hForcePxs() {
        return BotPhysicsEngine.cfg.HFORCE_PXS * speedMultiplier();
    }

    float jumpSpeedPxs() {
        return (float) (BotPhysicsEngine.cfg.JUMP_SPEED_PXS * jumpMultiplier());
    }

    float ropeJumpSpeedPxs() {
        return (float) (BotPhysicsEngine.cfg.JUMP_ROPE_PXS * jumpMultiplier());
    }
}
