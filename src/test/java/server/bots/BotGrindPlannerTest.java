package server.bots;

import org.junit.jupiter.api.Test;
import server.bots.BotGrindPlanner.GearProspect;
import server.bots.BotGrindPlanner.MobCandidate;
import server.bots.BotGrindPlanner.Recommendation;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotGrindPlannerTest {

    private static MobCandidate mob(int mobId, int exp, double killSeconds, int mapId,
                                    int spawnPoints, List<GearProspect> drops) {
        return new MobCandidate(mobId, "mob" + mobId, 30, exp, killSeconds,
                mapId, "map" + mapId, spawnPoints, drops);
    }

    @Test
    void shouldPickBestExpWhenNoUpgradeExists() {
        MobCandidate slowRich = mob(1, 100, 8.0, 10, 8, List.of());   // 100 exp, slow kill
        MobCandidate fastModest = mob(2, 60, 2.0, 20, 8, List.of());  // better exp/h

        Recommendation rec = BotGrindPlanner.planBest(List.of(slowRich, fastModest), new Random(7));

        assertNotNull(rec);
        assertEquals(2, rec.pick().mobId());
        assertFalse(rec.gearFocused());
        assertNull(rec.wantedGear());
        assertEquals(0.0, rec.needGear(), 1e-9);
    }

    @Test
    void shouldChaseBigAttainableUpgradeOverBetterExp() {
        // 1% drop, +35% DPS at ~700 kills/h -> expected ~14 copies over the horizon: fully attainable.
        GearProspect bigUpgrade = new GearProspect(1402000, "sword", 0.01, 50.0, 0.35);
        MobCandidate gearMob = mob(1, 30, 3.0, 10, 8, List.of(bigUpgrade));
        MobCandidate expMob = mob(2, 200, 3.0, 20, 8, List.of());

        Recommendation rec = BotGrindPlanner.planBest(List.of(gearMob, expMob), new Random(7));

        assertNotNull(rec);
        assertEquals(1, rec.pick().mobId());
        assertTrue(rec.gearFocused());
        assertEquals(1402000, rec.wantedGear().itemId());
        assertEquals(1.0, rec.needGear(), 1e-9);
    }

    @Test
    void shouldNotChaseUnattainableJackpotDrop() {
        // Same +35% DPS upgrade but at one-in-a-million: attainability discounts it to noise.
        GearProspect jackpot = new GearProspect(1402000, "sword", 1e-6, 50.0, 0.35);
        MobCandidate gearMob = mob(1, 30, 3.0, 10, 8, List.of(jackpot));
        MobCandidate expMob = mob(2, 200, 3.0, 20, 8, List.of());

        Recommendation rec = BotGrindPlanner.planBest(List.of(gearMob, expMob), new Random(7));

        assertNotNull(rec);
        assertEquals(2, rec.pick().mobId());
        assertFalse(rec.gearFocused());
        assertTrue(rec.needGear() < 0.01);
    }

    @Test
    void shouldPreferDenserMapForTheSameMob() {
        MobCandidate sparse = mob(1, 100, 2.0, 10, 1, List.of());  // long seek between kills
        MobCandidate dense = mob(1, 100, 2.0, 20, 12, List.of());

        Recommendation rec = BotGrindPlanner.planBest(List.of(sparse, dense), new Random(7));

        assertNotNull(rec);
        assertEquals(20, rec.pick().mapId());
        assertTrue(BotGrindPlanner.seekSeconds(1) > BotGrindPlanner.seekSeconds(12));
    }

    @Test
    void shouldSpreadNearBestPicksAcrossBots() {
        // Two equally good maps: across many draws both must get picked (anti-clogging).
        MobCandidate a = mob(1, 100, 2.0, 10, 8, List.of());
        MobCandidate b = mob(1, 100, 2.0, 20, 8, List.of());

        boolean sawA = false;
        boolean sawB = false;
        Random rng = new Random(123);
        for (int i = 0; i < 50 && !(sawA && sawB); i++) {
            Recommendation rec = BotGrindPlanner.planBest(List.of(a, b), rng);
            sawA |= rec.pick().mapId() == 10;
            sawB |= rec.pick().mapId() == 20;
        }
        assertTrue(sawA && sawB, "both near-best maps should be chosen across draws");
    }

    @Test
    void shouldReturnNullOnEmptyOrWorthlessInput() {
        assertNull(BotGrindPlanner.planBest(List.of(), new Random(7)));
        // exp 0 and no drops -> nothing worth recommending
        assertNull(BotGrindPlanner.planBest(List.of(mob(1, 0, 2.0, 10, 8, List.of())), new Random(7)));
    }
}
