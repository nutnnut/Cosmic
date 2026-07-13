package server.bots;

import client.Character;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.maps.MapItem;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Grind doctrine + spot clustering unit tests (pure core — no map/graph/WZ needed). */
public class BotGrindDoctrineTest {

    private static BotGrindSpots.SpawnPt pt(int x, int y, int regionId) {
        return new BotGrindSpots.SpawnPt(x, y, regionId);
    }

    // ---------------------------------------------------------------- clustering

    @Test
    public void compactSingleLedgeClusterYieldsOneSpot() {
        List<BotGrindSpots.SpawnPt> pts = List.of(
                pt(100, 300, 7), pt(200, 300, 7), pt(300, 300, 7), pt(400, 300, 7),
                pt(500, 300, 7), pt(600, 300, 7), pt(700, 300, 7), pt(800, 300, 7));
        BotGrindSpots.Profile p = BotGrindSpots.buildProfile(pts, 800);

        assertEquals(1, p.spots().size());
        BotGrindSpots.Spot s = p.spots().get(0);
        assertEquals(8, s.sameLedgeSpawnCount());
        assertEquals(7, s.regionId());
        // dense single cluster -> COMPACT, campable (>= 4 same-ledge spawns) -> not roam
        assertEquals(BotGrindSpots.Regime.COMPACT, p.regime());
        assertFalse(p.roam());
        assertTrue(p.stacks().isEmpty());
    }

    @Test
    public void stackedLedgesSplitIntoSpotsAndFormAStack() {
        // Two platforms 100px apart vertically, overlapping X: anisotropic distance
        // (dy*2.5 = 250 > merge would still join at dx=0... 250 <= 350 joins) then the
        // ledge partition splits them; stack detection re-joins them as a column.
        List<BotGrindSpots.SpawnPt> pts = List.of(
                pt(100, 300, 1), pt(220, 300, 1), pt(340, 300, 1), pt(460, 300, 1),
                pt(120, 200, 2), pt(240, 200, 2), pt(360, 200, 2), pt(480, 200, 2));
        BotGrindSpots.Profile p = BotGrindSpots.buildProfile(pts, 600);

        assertEquals(2, p.spots().size());
        assertEquals(1, p.stacks().size());
        BotGrindSpots.SpotStack st = p.stacks().get(0);
        assertEquals(8, st.totalFeed());
        assertTrue(st.hopTraversable()); // 100px gap <= STACK_HOP_DY(110)
        assertTrue(st.topY() < st.bottomY());
    }

    @Test
    public void sparseMapClassifiesSparseAndRoams() {
        List<BotGrindSpots.SpawnPt> pts = List.of(
                pt(100, 300, 1), pt(1500, 300, 2), pt(3000, 300, 3));
        BotGrindSpots.Profile p = BotGrindSpots.buildProfile(pts, 4000);

        assertEquals(BotGrindSpots.Regime.SPARSE, p.regime());
        assertTrue(p.roam()); // best ledge feeds 1 < MIN_CAMPABLE_SAMELEDGE_SPAWN
    }

    @Test
    public void robustHalfSpreadIgnoresLoneOutlier() {
        List<BotGrindSpots.SpawnPt> tight = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            tight.add(pt(1000 + i * 20, 300, 1));
        }
        tight.add(pt(5000, 300, 1)); // lone outlier
        int spread = BotGrindSpots.robustHalfSpreadX(tight);
        assertTrue(spread < 1000, "p90 spread should ignore the outlier, got " + spread);
    }

    // ---------------------------------------------------------------- style selection

    @Test
    public void styleSelectionFollowsRegimeAndRoamFlag() {
        // roam flag wins over regime
        BotGrindSpots.Profile roaming = new BotGrindSpots.Profile(
                List.of(spot(100, 300, 1, 2)), List.of(), BotGrindSpots.Regime.SPREAD, true);
        assertNull(BotGrindDoctrine.chooseStyle(null, roaming));

        // SPREAD with >= 3 spots -> PATROL
        BotGrindSpots.Profile spread = new BotGrindSpots.Profile(
                List.of(spot(100, 300, 1, 5), spot(900, 300, 2, 5), spot(1700, 300, 3, 5)),
                List.of(), BotGrindSpots.Regime.SPREAD, false);
        assertEquals(BotGrindDoctrine.Style.PATROL, BotGrindDoctrine.chooseStyle(null, spread));

        // COMPACT -> CAMP
        BotGrindSpots.Profile compact = new BotGrindSpots.Profile(
                List.of(spot(100, 300, 1, 5)), List.of(), BotGrindSpots.Regime.COMPACT, false);
        assertEquals(BotGrindDoctrine.Style.CAMP, BotGrindDoctrine.chooseStyle(null, compact));

        // no spots -> no doctrine
        assertNull(BotGrindDoctrine.chooseStyle(null, BotGrindSpots.Profile.EMPTY));
    }

    @Test
    public void hopTraversableDominantStackSelectsStackStyle() {
        BotGrindSpots.Spot a = spot(100, 300, 1, 5);
        BotGrindSpots.Spot b = spot(120, 200, 2, 5);
        BotGrindSpots.SpotStack st = new BotGrindSpots.SpotStack(
                List.of(0, 1), -150, 350, 200, 300, 10, true);
        BotGrindSpots.Profile p = new BotGrindSpots.Profile(
                List.of(a, b), List.of(st), BotGrindSpots.Regime.COMPACT, false);
        // feed 10 >= STACK_MIN_FEED(8) and >= bestSameLedge(5)*1.5 -> STACK even for a walker
        assertEquals(BotGrindDoctrine.Style.STACK, BotGrindDoctrine.chooseStyle(null, p));

        // blink-only stack (not hop-traversable) is ignored for a walker -> CAMP
        BotGrindSpots.SpotStack blinkOnly = new BotGrindSpots.SpotStack(
                List.of(0, 1), -150, 350, 200, 300, 10, false);
        BotGrindSpots.Profile p2 = new BotGrindSpots.Profile(
                List.of(a, b), List.of(blinkOnly), BotGrindSpots.Regime.COMPACT, false);
        assertEquals(BotGrindDoctrine.Style.CAMP, BotGrindDoctrine.chooseStyle(null, p2));
    }

    private static BotGrindSpots.Spot spot(int x, int y, int regionId, int sameLedge) {
        return new BotGrindSpots.Spot(new Point(x, y), regionId, 300, sameLedge, sameLedge, 400, 3);
    }

    // ---------------------------------------------------------------- candidate filtering

    @Test
    public void filterCandidatesKeepsInSpotMobsAndTracksDryness() {
        BotEntry entry = new BotEntry(null, null, null);
        entry.grindDoctrineStyle = BotGrindDoctrine.Style.CAMP;
        entry.grindSpotAnchor = new Point(500, 300);
        entry.grindSpotRadius = 300;

        Monster inSpot = monsterAt(600, 300);
        Monster offLedge = monsterAt(600, 100);   // above the Y band
        Monster farAway = monsterAt(1500, 300);   // outside radius+margin

        long now = 1_000_000L;
        List<Monster> filtered = BotGrindDoctrine.filterCandidates(
                entry, List.of(inSpot, offLedge, farAway), now);
        assertEquals(1, filtered.size());
        assertSame(inSpot, filtered.get(0));
        assertEquals(0, entry.grindSpotDrySinceMs);

        // spot empty -> dry timer starts, sticks to first-dry timestamp
        List<Monster> dry = BotGrindDoctrine.filterCandidates(entry, List.of(farAway), now + 1000);
        assertTrue(dry.isEmpty());
        assertEquals(now + 1000, entry.grindSpotDrySinceMs);
        BotGrindDoctrine.filterCandidates(entry, List.of(farAway), now + 2000);
        assertEquals(now + 1000, entry.grindSpotDrySinceMs);
    }

    @Test
    public void filterCandidatesPassesThroughWhenDoctrineInactive() {
        BotEntry entry = new BotEntry(null, null, null);
        List<Monster> candidates = List.of(monsterAt(100, 100));
        assertSame(candidates, BotGrindDoctrine.filterCandidates(entry, candidates, 0L));
    }

    @Test
    public void stackBoxFiltersAcrossLedges() {
        BotEntry entry = new BotEntry(null, null, null);
        entry.grindDoctrineStyle = BotGrindDoctrine.Style.STACK;
        entry.grindSpotAnchor = new Point(200, 300);
        entry.grindSpotRadius = 300;
        entry.grindStackBox = new java.awt.Rectangle(0, 140, 600, 220); // y 140..360

        Monster upperFloor = monsterAt(300, 200);
        Monster below = monsterAt(300, 500);
        List<Monster> filtered = BotGrindDoctrine.filterCandidates(
                entry, List.of(upperFloor, below), 0L);
        assertEquals(List.of(upperFloor), filtered);
    }

    private static Monster monsterAt(int x, int y) {
        Monster m = mock(Monster.class);
        when(m.getPosition()).thenReturn(new Point(x, y));
        return m;
    }

    // ---------------------------------------------------------------- claims

    @Test
    public void claimsCountExcludesSelfAndExpire() {
        long now = 5_000_000L;
        int mapId = 104040000;
        long key = 12345L;
        BotGrindSpots.Claims.renew(mapId, key, 1, now);
        BotGrindSpots.Claims.renew(mapId, key, 2, now);
        assertEquals(1, BotGrindSpots.Claims.holders(mapId, key, 1, now)); // excludes self
        assertEquals(2, BotGrindSpots.Claims.holders(mapId, key, 99, now));
        // stale claims stop counting after the TTL
        assertEquals(0, BotGrindSpots.Claims.holders(mapId, key, 99, now + BotGrindSpots.CLAIM_TTL_MS + 1));
        BotGrindSpots.Claims.release(mapId, key, 1);
        BotGrindSpots.Claims.release(mapId, key, 2);
    }

    // ---------------------------------------------------------------- level band

    @Test
    public void levelBandUsesConstantLookDownSpan() {
        assertTrue(BotGrindAdvisor.levelBandAllows(30, 42));   // +12 upper edge
        assertFalse(BotGrindAdvisor.levelBandAllows(30, 43));
        assertTrue(BotGrindAdvisor.levelBandAllows(30, 5));    // -25 lower edge
        assertFalse(BotGrindAdvisor.levelBandAllows(30, 4));
        assertTrue(BotGrindAdvisor.levelBandAllows(10, 1));    // lowbie floors at mob level 1
        assertFalse(BotGrindAdvisor.levelBandAllows(80, 54));  // lv 80 floor sits at 55
        assertTrue(BotGrindAdvisor.levelBandAllows(80, 55));
    }

    // ---------------------------------------------------------------- loot sweep chain

    @Test
    public void sweepChainPicksFarEndOfSameLedgeChainOnNearSide() {
        Point botPos = new Point(0, 300);
        MapItem near = dropAt(200, 300);
        MapItem mid = dropAt(400, 310);
        MapItem far = dropAt(700, 290);
        MapItem otherSide = dropAt(-500, 300);   // behind the bot
        MapItem otherLedge = dropAt(600, 100);   // different ledge (dy > 120)

        MapItem chosen = BotInventoryManager.sweepChainEnd(
                botPos, near, List.of(near, mid, far, otherSide, otherLedge));
        assertSame(far, chosen);
    }

    private static MapItem dropAt(int x, int y) {
        MapItem drop = mock(MapItem.class);
        when(drop.getPosition()).thenReturn(new Point(x, y));
        return drop;
    }

    // ---------------------------------------------------------------- engage style

    @Test
    public void engageHopOnlyForThiefLines() {
        assertTrue(jobIsJumpAttack(412));  // night lord
        assertTrue(jobIsJumpAttack(410));  // assassin
        assertTrue(jobIsJumpAttack(422));  // shadower
        assertFalse(jobIsJumpAttack(112)); // hero
        assertFalse(jobIsJumpAttack(312)); // bowmaster
        assertFalse(jobIsJumpAttack(212)); // arch mage
        assertFalse(jobIsJumpAttack(0));   // beginner
    }

    private static boolean jobIsJumpAttack(int jobId) {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(client.Job.getById(jobId));
        return BotCombatManager.isJumpAttackJob(bot);
    }
}
