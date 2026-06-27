package server.bots;

import client.inventory.WeaponType;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The damage-profile cache key packs (skillId, skillLevel, route, weapon) into one long. If the bit
 * layout ever overlaps, two different skills collide to the same key and the bot scores one with the
 * other's damage profile. This guards the packing: representative distinct inputs must stay distinct,
 * and same inputs must map to the same key.
 */
class BotDamageProfileKeyTest {

    @Test
    void distinctInputsProduceDistinctKeys() {
        int[] skillIds = {0, 1001005, 3201005, 5201001, 5221004 /* near the largest real skill ids */};
        int[] levels = {0, 1, 20, 30, 31};
        WeaponType[] weapons = {null, WeaponType.NOT_A_WEAPON, WeaponType.BOW, WeaponType.GUN, WeaponType.WAND};

        Set<Long> keys = new HashSet<>();
        int count = 0;
        for (int skillId : skillIds) {
            for (int level : levels) {
                for (BotCombatManager.AttackRoute route : BotCombatManager.AttackRoute.values()) {
                    for (WeaponType weapon : weapons) {
                        keys.add(BotCombatManager.damageProfileKey(skillId, level, route, weapon));
                        count++;
                    }
                }
            }
        }
        // null-route shares the routeBits slot (3) with no real route ordinal (0..2), so it is also distinct.
        keys.add(BotCombatManager.damageProfileKey(3201005, 20, null, WeaponType.BOW));
        count++;
        assertEquals(count, keys.size(), "damage-profile key packing collided");
    }

    @Test
    void sameInputsAreStable() {
        long a = BotCombatManager.damageProfileKey(3201005, 20, BotCombatManager.AttackRoute.RANGED, WeaponType.BOW);
        long b = BotCombatManager.damageProfileKey(3201005, 20, BotCombatManager.AttackRoute.RANGED, WeaponType.BOW);
        assertEquals(a, b);
        assertTrue(a != BotCombatManager.damageProfileKey(3201005, 21, BotCombatManager.AttackRoute.RANGED, WeaponType.BOW));
    }
}
