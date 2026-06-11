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

    // ---- density (map area) and respawn supply ----

    @Test
    void shouldSeekByAreaDensityNotJustSpawnCount() {
        // Same 8 spawns: typical density = 3s seek; four times the field = twice the per-spawn
        // area = double the walk; no area data falls back to spawn count alone (old anchor).
        assertEquals(3.0, BotGrindPlanner.seekSeconds(2_000_000, 8), 1e-9);
        assertEquals(6.0, BotGrindPlanner.seekSeconds(4_000_000, 8), 1e-9);
        assertEquals(3.0, BotGrindPlanner.seekSeconds(0, 8), 1e-9);
    }

    @Test
    void shouldCapKillsAtTheRespawnSupply() {
        // 2 spawn points refill at most 720 kills/h (10s respawn cycle), however fast the
        // bot clears the tiny packed map.
        MobCandidate tiny = new MobCandidate(1, "mob1", 30, 100, 0.5, 10, "map10",
                2, 100_000, List.of());
        assertEquals(720.0, BotGrindPlanner.killsPerHour(tiny), 1e-9);
    }

    @Test
    void shouldPreferRoomierMapForAPartyButDenserMapSolo() {
        // Small packed map vs a bigger, slightly sparser one with 3x the spawns.
        MobCandidate smallDense = new MobCandidate(1, "mob1", 30, 100, 2.0, 10, "map10",
                4, 200_000, List.of());
        MobCandidate bigRoomy = new MobCandidate(2, "mob2", 30, 100, 2.0, 20, "map20",
                12, 1_200_000, List.of());

        // Solo: the packed small map grinds faster (short seek, supply never binds).
        for (int seed = 0; seed < 20; seed++) {
            Recommendation solo = BotGrindPlanner.planBest(
                    List.of(smallDense, bigRoomy), new Random(seed));
            assertEquals(10, solo.pick().mapId(), "solo must take the dense small map");
        }

        // Party of 4: members share the spawns - the small map starves on respawn supply
        // (1 shared point = 360 kills/h each) while the roomy one still feeds everyone.
        List<List<MobCandidate>> perMember = java.util.Collections.nCopies(
                4, List.of(smallDense, bigRoomy));
        for (int seed = 0; seed < 20; seed++) {
            BotGrindPlanner.PartyPlan plan = BotGrindPlanner.planPartyBest(perMember, new Random(seed));
            assertEquals(20, plan.mapId(), "a party must move to the roomier map");
        }
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
        // Gear progression is PRIMARY: 1% drop, +35% DPS at ~700 kills/h -> expected ~14 copies
        // over the horizon, so the gear-rich map wins over the much better exp map outright.
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
    void shouldTiebreakGearComparableMapsByExp() {
        // Same attainable upgrade on both maps -> both join the gear shortlist; exp decides.
        GearProspect upgrade = new GearProspect(1402000, "sword", 0.01, 50.0, 0.35);
        MobCandidate gearGoodExp = mob(1, 200, 2.0, 10, 8, List.of(upgrade));
        MobCandidate gearPoorExp = mob(2, 60, 2.0, 20, 8, List.of(upgrade));

        for (int seed = 0; seed < 20; seed++) {
            Recommendation rec = BotGrindPlanner.planBest(
                    List.of(gearPoorExp, gearGoodExp), new Random(seed));
            assertEquals(10, rec.pick().mapId(), "exp must break the tie among gear-comparable maps");
            assertTrue(rec.gearFocused());
        }
    }

    @Test
    void shouldPenalizeFarMapsViaTravelWeight() {
        // The far map has better raw exp, but the travel-penalty floor (0.25x) flips the pick.
        MobCandidate near = mob(1, 100, 2.0, 10, 8, List.of());
        MobCandidate far = mob(2, 150, 2.0, 20, 8, List.of());
        java.util.function.IntToDoubleFunction weight = mapId -> mapId == 20 ? 0.25 : 1.0;

        for (int seed = 0; seed < 20; seed++) {
            Recommendation rec = BotGrindPlanner.planBest(List.of(near, far), weight, new Random(seed));
            assertEquals(10, rec.pick().mapId(), "far map must lose to the slightly-worse near map");
            // Reported exp/hr stays RAW (chat layer), only the selection score is travel-weighted.
            assertEquals(rec.expPerHour(), rec.score(), 1e-9);
        }
        // Sanity: without the weight the far map wins.
        assertEquals(20, BotGrindPlanner.planBest(List.of(near, far), new Random(7)).pick().mapId());
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
        assertTrue(BotGrindPlanner.seekSeconds(0, 1) > BotGrindPlanner.seekSeconds(0, 12));
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

    @Test
    void shouldPenalizeFarFarmSitesViaTravelWeight() {
        GearProspect target = new GearProspect(2040705, "scroll", 0.02, 0, 0);
        GearProspect targetBetter = new GearProspect(2040705, "scroll", 0.03, 0, 0);
        MobCandidate near = mob(1, 50, 2.0, 10, 8, List.of(target));
        MobCandidate far = mob(2, 50, 2.0, 20, 8, List.of(targetBetter));
        java.util.function.IntToDoubleFunction weight = mapId -> mapId == 20 ? 0.25 : 1.0;

        for (int seed = 0; seed < 20; seed++) {
            Recommendation rec = BotGrindPlanner.planFarmBest(List.of(far, near), weight, new Random(seed));
            assertEquals(10, rec.pick().mapId(), "far farm site must lose under the travel penalty");
            // wantedGearPerHour reports the RAW items/hour at the site, not the weighted score.
            assertEquals(0.02 * rec.killsPerHour(), rec.wantedGearPerHour(), 1e-9);
        }
    }
}
