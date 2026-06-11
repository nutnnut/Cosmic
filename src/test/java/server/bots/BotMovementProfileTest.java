package server.bots;

import client.Character;
import org.junit.jupiter.api.Test;
import server.maps.FieldLimit;
import server.maps.MapleMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotMovementProfileTest {
    @Test
    void shouldBucketStatsToTheNearestFivePointStep() {
        BotMovementProfile profile = new BotMovementProfile(109, 117);

        // Nearest bucket, not floor: 109 -> 110, 117 -> 115.
        assertEquals(110, profile.totalSpeedStat());
        assertEquals(115, profile.totalJumpStat());
    }

    @Test
    void shouldLeaveExactFivePointBucketsUnchanged() {
        BotMovementProfile profile = new BotMovementProfile(105, 120);

        assertEquals(105, profile.totalSpeedStat());
        assertEquals(120, profile.totalJumpStat());
    }

    @Test
    void shouldCapEffectivePhysicsStats() {
        BotMovementProfile profile = new BotMovementProfile(240, 130);

        assertEquals(200, profile.totalSpeedStat());
        assertEquals(123, profile.totalJumpStat());
    }

    @Test
    void shouldUseBaseStatsWhenMapForcesMovementSkillLimit() {
        MapleMap map = new MapleMap(100000202, 0, 0, 100000000, 1.0f);
        map.setFieldLimit((int) FieldLimit.MOVEMENTSKILLS.getValue());
        Character character = mock(Character.class);
        when(character.getMap()).thenReturn(map);
        when(character.getTotalMoveSpeedStat()).thenReturn(140);
        when(character.getTotalJumpStat()).thenReturn(123);

        BotMovementProfile profile = BotMovementProfile.fromCharacter(character);

        assertEquals(BotMovementProfile.base(), profile);
    }

    @Test
    void shouldLoadPetWalkingRoadMovementSkillLimit() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(100000202);

        assertTrue(FieldLimit.MOVEMENTSKILLS.check(map.getFieldLimit()));
    }
}
