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

    @Test
    void shouldShiftPartyToRoomierMapWhenSharedSpawnsGetCramped() {
        // Solo math: dense-but-small map 10 (exp 350, sp 8) beats wide map 20 (exp 100, sp 48).
        // Four members sharing the spawns flips it: sp 8/4=2 starves everyone, sp 48/4=12 doesn't.
        MobCandidate smallDense = mob(1, 350, 1.0, 10, 8, List.of());
        MobCandidate wideOpen = mob(2, 100, 1.0, 20, 48, List.of());
        List<MobCandidate> options = List.of(smallDense, wideOpen);

        assertEquals(10, BotGrindPlanner.planBest(options, new Random(7)).pick().mapId());

        BotGrindPlanner.PartyPlan plan = BotGrindPlanner.planPartyBest(
                List.of(options, options, options, options), new Random(7));
        assertNotNull(plan);
        assertEquals(20, plan.mapId());
        assertEquals(4, plan.perMember().size());
        for (Recommendation rec : plan.perMember()) {
            assertNotNull(rec);
            assertEquals(20, rec.pick().mapId());
        }
    }

    @Test
    void shouldPickSharedMapThatBenefitsSeveralMembersOverOneMembersFavorite() {
        // Map 30 is decent for everyone (good exp + an attainable upgrade for member B);
        // map 10 is member A's solo favorite but worthless for B. The summed score wins.
        GearProspect bUpgrade = new GearProspect(1402000, "sword", 0.01, 50.0, 0.35);
        List<MobCandidate> memberA = List.of(
                mob(1, 200, 2.0, 10, 12, List.of()),
                mob(2, 150, 2.0, 30, 12, List.of()));
        List<MobCandidate> memberB = List.of(
                mob(2, 150, 2.0, 30, 12, List.of(bUpgrade)));

        BotGrindPlanner.PartyPlan plan = BotGrindPlanner.planPartyBest(
                List.of(memberA, memberB), new Random(7));

        assertNotNull(plan);
        assertEquals(30, plan.mapId());
        assertNotNull(plan.perMember().get(0));
        assertNotNull(plan.perMember().get(1));
        assertTrue(plan.perMember().get(1).gearFocused());
    }

    @Test
    void shouldPlanFarmSiteByExpectedItemsPerHour() {
        GearProspect target = new GearProspect(2040705, "scroll", 0.02, 0, 0);
        GearProspect targetRare = new GearProspect(2040705, "scroll", 0.002, 0, 0);
        // Same kill cycle: 10x the drop chance wins regardless of rng.
        MobCandidate goodSource = mob(1, 50, 2.0, 10, 8, List.of(target));
        MobCandidate poorSource = mob(2, 50, 2.0, 20, 8, List.of(targetRare));

        Recommendation rec = BotGrindPlanner.planFarmBest(List.of(poorSource, goodSource), new Random(7));

        assertNotNull(rec);
        assertEquals(10, rec.pick().mapId());
        assertTrue(rec.gearFocused());
        assertEquals("scroll", rec.wantedGear().itemName());
        assertEquals(0.02 * rec.killsPerHour(), rec.wantedGearPerHour(), 1e-9);

        // No candidate actually carries the item -> null (caller reports "nothing drops it").
        assertNull(BotGrindPlanner.planFarmBest(List.of(mob(3, 50, 2.0, 10, 8, List.of())), new Random(7)));
    }
}
