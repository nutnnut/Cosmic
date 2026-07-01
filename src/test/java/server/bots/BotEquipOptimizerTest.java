package server.bots;

import client.Character;
import client.Job;
import client.inventory.Equip;
import client.inventory.WeaponType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Combinatorial-correctness tests for {@link BotEquipManager#solveForWeapon}.
 *
 * Each scenario is constructed so a per-slot greedy solver would fall into a "trap":
 * each non-weapon slot offers a small immediate-damage pick (+WATK 1) and a chain pick
 * (+DEX 5). The +WATK trap is the obvious greedy choice (more immediate damage on the
 * currently-equipped weapon), but only the +DEX chain across many slots accumulates
 * enough DEX to unlock a much stronger weapon. The Pareto-DP must enumerate the full
 * combination, not just per-slot best.
 *
 * Tests stub {@link BotEquipManager.OptimizerHooks} via lambdas — Mockito cannot
 * instrument {@link server.ItemInformationProvider} in unit tests due to its WZ-data
 * static initializer, so the optimizer is decoupled from II behind this small interface.
 */
class BotEquipOptimizerTest {

    private static final int W0_ID = 1100000; // weak bow, no req
    private static final int W1_ID = 1100001; // strong bow, requires DEX 60

    private static final short S_HAT = -1, S_FACE = -2, S_EYE = -3, S_EAR = -4,
                                S_SHOES = -7, S_GLOVE = -8, S_CAPE = -9;
    private static final short[] CHAIN_SLOTS = {S_HAT, S_FACE, S_EYE, S_EAR, S_SHOES, S_GLOVE, S_CAPE};

    @Test
    void dpPicksChainOverTrapToUnlockStrongerWeapon() {
        Character bot = mockBowman();
        Equip w0 = weapon(W0_ID, /*watk*/ 20, /*pos*/ -11);
        Equip w1 = weapon(W1_ID, /*watk*/ 100, /*pos*/ 1);

        Map<Integer, Integer> reqDexByItem = new HashMap<>();
        reqDexByItem.put(W0_ID, 0);
        reqDexByItem.put(W1_ID, 60);

        Map<Short, List<Equip>> bySlot = new LinkedHashMap<>();
        int pos = 2;
        for (short slot : CHAIN_SLOTS) {
            Equip trap = armor(2000 + slot, /*dex*/ 0, /*watk*/ 1, pos++);
            Equip chain = armor(3000 + slot, /*dex*/ 5, /*watk*/ 0, pos++);
            bySlot.put(slot, List.of(trap, chain));
            reqDexByItem.put(trap.getItemId(), 0);
            reqDexByItem.put(chain.getItemId(), 0);
        }

        BotEquipManager.OptimizerHooks hooks = bowHooks(reqDexByItem);
        Map<Short, Equip> currentBySlot = new HashMap<>();
        currentBySlot.put((short) -11, w0);

        BotEquipManager.StatSnapshot naked = new BotEquipManager.StatSnapshot(
                /*str*/ 4, /*dex*/ 30, /*int_*/ 4, /*luk*/ 4,
                /*watk*/ 0, /*magic*/ 0, /*flatAcc*/ 0,
                /*level*/ 50, /*fame*/ 0, Job.BOWMAN);

        BotEquipManager.MapDamageProfile mob =
                new BotEquipManager.MapDamageProfile(/*wdef*/ 50, /*avoid*/ 30, /*level*/ 55);

        BotEquipManager.DpResult resultW1 = BotEquipManager.solveForWeapon(
                bot, hooks, naked, w1, asList(CHAIN_SLOTS), currentBySlot, bySlot, mob);

        assertNotNull(resultW1, "DP should find a feasible chain that unlocks W1");
        // Base DEX 30 + 7 × 5 = 65 ≥ 60 → W1 validates. Any +WATK trap pick would short
        // the DEX budget by 5 and fail W1's req, so the DP must pick chain at every slot.
        for (short slot : CHAIN_SLOTS) {
            Equip pick = resultW1.picks().get(slot);
            assertNotNull(pick, "expected a pick at slot " + slot + " for W1");
            assertEquals((short) 5, pick.getDex(),
                    "slot " + slot + " under W1 must pick the DEX-5 chain item, not the WATK trap");
            assertEquals((short) 0, pick.getWatk(),
                    "slot " + slot + " under W1 must NOT pick the WATK trap");
        }
        assertTrue(resultW1.score().damage() > 0, "W1 chain score should be positive");
    }

    @Test
    void dpScoresW1ChainHigherThanW0Trap() {
        // Side-by-side comparison: solving for W1 with the chain picks must outscore solving
        // for W0 with whatever picks the DP chooses. Validates that the outer weapon-loop
        // would prefer the chain-unlock branch in a real autoEquip call.
        Character bot = mockBowman();
        Equip w0 = weapon(W0_ID, 20, -11);
        Equip w1 = weapon(W1_ID, 100, 1);

        Map<Integer, Integer> reqDexByItem = new HashMap<>();
        reqDexByItem.put(W0_ID, 0);
        reqDexByItem.put(W1_ID, 60);

        Map<Short, List<Equip>> bySlot = new LinkedHashMap<>();
        int pos = 2;
        for (short slot : CHAIN_SLOTS) {
            Equip trap = armor(2000 + slot, 0, /*watk*/ 1, pos++);
            Equip chain = armor(3000 + slot, /*dex*/ 5, 0, pos++);
            bySlot.put(slot, List.of(trap, chain));
            reqDexByItem.put(trap.getItemId(), 0);
            reqDexByItem.put(chain.getItemId(), 0);
        }

        BotEquipManager.OptimizerHooks hooks = bowHooks(reqDexByItem);
        Map<Short, Equip> currentBySlot = new HashMap<>();
        currentBySlot.put((short) -11, w0);

        BotEquipManager.StatSnapshot naked = new BotEquipManager.StatSnapshot(
                4, 30, 4, 4, 0, 0, 0, 50, 0, Job.BOWMAN);
        BotEquipManager.MapDamageProfile mob =
                new BotEquipManager.MapDamageProfile(50, 30, 55);

        BotEquipManager.DpResult resultW0 = BotEquipManager.solveForWeapon(
                bot, hooks, naked, w0, asList(CHAIN_SLOTS), currentBySlot, bySlot, mob);
        BotEquipManager.DpResult resultW1 = BotEquipManager.solveForWeapon(
                bot, hooks, naked, w1, asList(CHAIN_SLOTS), currentBySlot, bySlot, mob);

        assertNotNull(resultW0);
        assertNotNull(resultW1);
        assertTrue(resultW1.score().damage() > resultW0.score().damage(),
                "W1+chain damage must beat W0 best — got W1=" + resultW1.score().damage()
                        + " W0=" + resultW0.score().damage());
    }

    @Test
    void dpRejectsW1WhenChainIsTooShort() {
        // Only 5 chain slots available; chain max DEX = 25. Base 30 + 25 = 55 < 60 → no
        // feasible state for W1 → solveForWeapon returns null. Validates the validator.
        Character bot = mockBowman();
        Equip w1 = weapon(W1_ID, 100, 1);

        Map<Integer, Integer> reqDexByItem = new HashMap<>();
        reqDexByItem.put(W1_ID, 60);

        short[] shortChain = {S_HAT, S_FACE, S_EYE, S_EAR, S_SHOES}; // only 5 slots
        Map<Short, List<Equip>> bySlot = new LinkedHashMap<>();
        int pos = 2;
        for (short slot : shortChain) {
            Equip chain = armor(3000 + slot, /*dex*/ 5, 0, pos++);
            bySlot.put(slot, List.of(chain));
            reqDexByItem.put(chain.getItemId(), 0);
        }

        BotEquipManager.OptimizerHooks hooks = bowHooks(reqDexByItem);
        Map<Short, Equip> currentBySlot = new HashMap<>();

        BotEquipManager.StatSnapshot naked = new BotEquipManager.StatSnapshot(
                4, 30, 4, 4, 0, 0, 0, 50, 0, Job.BOWMAN);

        BotEquipManager.DpResult result = BotEquipManager.solveForWeapon(
                bot, hooks, naked, w1, asList(shortChain), currentBySlot, bySlot, null);

        assertEquals(null, result,
                "expected no feasible state for W1 when chain is too short to clear DEX 60 req");
    }

    @Test
    void statGatedItemDoesNotPruneFeasibleFallback() {
        // Reproduces Clawer-2026-05-03 bug: glove pool has Orihalcon Arbion (req dex70 luk95,
        // +7 LUK +14 WDEF +53 HP +49 MP), Purple Work Gloves (no req, +4 LUK +1 WDEF +48 HP
        // +43 MP), Red Marker (no req, +3 INT +7 WDEF). Bot is Assassin dex=50 luk=152.
        // Without the snapshot-feasibility guard in paretoPruneNodes, Arbion vec-dominates
        // PWG on every dim, PWG is pruned, Arbion later fails validateReqs (dex 50 < 70) and
        // relaxes to no-glove, then Red Marker beats no-glove on the def tie. Correct: PWG.
        final int CLAW_ID = 1472000;
        final int ARBION_ID = 1082140, PWG_ID = 1082002, RED_MARKER_ID = 1082023;

        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.ASSASSIN);
        when(bot.getLevel()).thenReturn(38);
        when(bot.getFame()).thenReturn(0);

        Equip claw = mock(Equip.class);
        when(claw.getItemId()).thenReturn(CLAW_ID);
        when(claw.getWatk()).thenReturn((short) 35);
        when(claw.getPosition()).thenReturn((short) -11);

        Equip arbion = mock(Equip.class);
        when(arbion.getItemId()).thenReturn(ARBION_ID);
        when(arbion.getLuk()).thenReturn((short) 7);
        when(arbion.getWdef()).thenReturn((short) 14);
        when(arbion.getHp()).thenReturn((short) 53);
        when(arbion.getMp()).thenReturn((short) 49);
        when(arbion.getPosition()).thenReturn((short) 1);

        Equip pwg = mock(Equip.class);
        when(pwg.getItemId()).thenReturn(PWG_ID);
        when(pwg.getLuk()).thenReturn((short) 4);
        when(pwg.getWdef()).thenReturn((short) 1);
        when(pwg.getHp()).thenReturn((short) 48);
        when(pwg.getMp()).thenReturn((short) 43);
        when(pwg.getPosition()).thenReturn((short) 2);

        Equip redMarker = mock(Equip.class);
        when(redMarker.getItemId()).thenReturn(RED_MARKER_ID);
        when(redMarker.getInt()).thenReturn((short) 3);
        when(redMarker.getWdef()).thenReturn((short) 7);
        when(redMarker.getPosition()).thenReturn((short) 3);

        BotEquipManager.OptimizerHooks hooks = new BotEquipManager.OptimizerHooks() {
            @Override public boolean isTwoHanded(int itemId) { return false; }
            @Override public WeaponType getWeaponType(int itemId) {
                return itemId == CLAW_ID ? WeaponType.CLAW : null;
            }
            @Override public boolean isOverall(int itemId) { return false; }
            @Override public boolean meetsReqs(Equip e, Job job, int lvl, int s, int d, int i, int l, int f) {
                if (e.getItemId() == ARBION_ID) return d >= 70 && l >= 95;
                return true;
            }
        };

        Map<Short, Equip> currentBySlot = new HashMap<>();
        currentBySlot.put((short) -11, claw);
        Map<Short, List<Equip>> bySlot = new LinkedHashMap<>();
        bySlot.put(S_GLOVE, List.of(arbion, pwg, redMarker));

        BotEquipManager.StatSnapshot naked = new BotEquipManager.StatSnapshot(
                /*str*/ 4, /*dex*/ 50, /*int_*/ 4, /*luk*/ 152,
                /*watk*/ 0, /*magic*/ 0, /*flatAcc*/ 0,
                /*level*/ 38, /*fame*/ 0, Job.ASSASSIN);
        BotEquipManager.MapDamageProfile mob =
                new BotEquipManager.MapDamageProfile(/*wdef*/ 70, /*avoid*/ 0, /*level*/ 40);

        BotEquipManager.DpResult result = BotEquipManager.solveForWeapon(
                bot, hooks, naked, claw, List.of(S_GLOVE), currentBySlot, bySlot, mob);

        assertNotNull(result, "expected a feasible plan");
        Equip pick = result.picks().get(S_GLOVE);
        assertNotNull(pick, "expected a glove pick");
        assertEquals(PWG_ID, pick.getItemId(),
                "Stat-gated Arbion must not prune feasible PWG fallback; got itemId=" + pick.getItemId());
    }

    @Test
    void itemReqMustBeMetWithoutItsOwnStatContribution() {
        // Reproduces Preston-2026-05-10 bug: Cleric bot ends up with no hat equipped
        // because the DP picks Brown Guiltian (req luk43, +luk2) over Brown Matty
        // (req luk38, +luk3) when bot's pre-hat luk is 42. Old validateReqs counted
        // Guiltian's own +luk2 toward its own req (state luk = 44 >= 43, "passes"),
        // but at equip time luk is 42 < 43 -- own contribution doesn't satisfy own req.
        // Correct behavior: DP must reject Guiltian and pick Matty (req 38 met by 42).
        final int GUILTIAN_ID = 1002000;
        final int MATTY_ID = 1002001;

        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.CLERIC);
        when(bot.getLevel()).thenReturn(46);
        when(bot.getFame()).thenReturn(0);

        Equip guiltian = mock(Equip.class);
        when(guiltian.getItemId()).thenReturn(GUILTIAN_ID);
        when(guiltian.getInt()).thenReturn((short) 7);
        when(guiltian.getLuk()).thenReturn((short) 2);
        when(guiltian.getPosition()).thenReturn((short) 1);

        Equip matty = mock(Equip.class);
        when(matty.getItemId()).thenReturn(MATTY_ID);
        when(matty.getInt()).thenReturn((short) 4);
        when(matty.getLuk()).thenReturn((short) 3);
        when(matty.getPosition()).thenReturn((short) 2);

        Map<Integer, Integer> reqLukByItem = new HashMap<>();
        reqLukByItem.put(GUILTIAN_ID, 43);
        reqLukByItem.put(MATTY_ID, 38);

        BotEquipManager.OptimizerHooks hooks = new BotEquipManager.OptimizerHooks() {
            @Override public boolean isTwoHanded(int itemId) { return false; }
            @Override public WeaponType getWeaponType(int itemId) { return null; }
            @Override public boolean isOverall(int itemId) { return false; }
            @Override public boolean meetsReqs(Equip e, Job job, int lvl, int s, int d, int i, int l, int f) {
                return l >= reqLukByItem.getOrDefault(e.getItemId(), 0);
            }
        };

        Map<Short, Equip> currentBySlot = new HashMap<>();
        Map<Short, List<Equip>> bySlot = new LinkedHashMap<>();
        bySlot.put(S_HAT, List.of(guiltian, matty));

        BotEquipManager.StatSnapshot naked = new BotEquipManager.StatSnapshot(
                /*str*/ 4, /*dex*/ 4, /*int_*/ 200, /*luk*/ 42,
                /*watk*/ 0, /*magic*/ 200, /*flatAcc*/ 0,
                /*level*/ 46, /*fame*/ 0, Job.CLERIC);
        BotEquipManager.MapDamageProfile mob =
                new BotEquipManager.MapDamageProfile(/*wdef*/ 0, /*avoid*/ 0, /*level*/ 50);

        BotEquipManager.DpResult result = BotEquipManager.solveForWeapon(
                bot, hooks, naked, /*weapon*/ null, List.of(S_HAT), currentBySlot, bySlot, mob);

        assertNotNull(result, "expected a feasible plan");
        Equip pick = result.picks().get(S_HAT);
        assertNotNull(pick, "expected a hat pick (Matty is feasible)");
        assertEquals(MATTY_ID, pick.getItemId(),
                "Guiltian (req luk43, +luk2) must NOT be picked when bot pre-hat luk=42 "
                + "(item's own +luk2 cannot satisfy its own req); expected Matty (req 38). "
                + "Got itemId=" + pick.getItemId());
    }

    @Test
    void mageOneHandedMatkWeaponWinsWhenFreedShieldSlotCarriesTheEnsemble() {
        // Black Umbrella scenario: a 1H any-job MAD sword (92) vs a slightly stronger 2H
        // staff (95). The staff branch blocks the shield slot (2H<->shield exclusivity);
        // the umbrella branch keeps it, and the INT shield tips the ensemble. Mage scoring
        // is magicScore (INT*1.1 + magic) — weapon type/cycle is irrelevant for casting.
        Character mage = mock(Character.class);
        when(mage.getJob()).thenReturn(Job.CLERIC);
        when(mage.getLevel()).thenReturn(70);
        when(mage.getFame()).thenReturn(0);

        int staffId = 1382005, umbrellaId = 1302026, shieldId = 1092010;
        Equip staff = matkWeapon(staffId, 95, 1);
        Equip umbrella = matkWeapon(umbrellaId, 92, 2);
        Equip shield = mock(Equip.class);
        when(shield.getItemId()).thenReturn(shieldId);
        when(shield.getInt()).thenReturn((short) 10);
        when(shield.getPosition()).thenReturn((short) 3);

        BotEquipManager.OptimizerHooks hooks = new BotEquipManager.OptimizerHooks() {
            @Override public boolean isTwoHanded(int itemId) { return itemId == staffId; }
            @Override public WeaponType getWeaponType(int itemId) {
                if (itemId == staffId) return WeaponType.STAFF;
                if (itemId == umbrellaId) return WeaponType.SWORD1H;
                return null;
            }
            @Override public boolean isOverall(int itemId) { return false; }
            @Override public boolean meetsReqs(Equip e, Job job, int lvl, int s, int d, int i, int l, int f) {
                return true;
            }
        };

        Map<Short, List<Equip>> bySlot = new LinkedHashMap<>();
        bySlot.put((short) -10, List.of(shield));
        List<Short> dpSlots = List.of((short) -10);
        Map<Short, Equip> currentBySlot = new HashMap<>();

        BotEquipManager.StatSnapshot naked = new BotEquipManager.StatSnapshot(
                /*str*/ 6, /*dex*/ 6, /*int_*/ 80, /*luk*/ 30,
                /*watk*/ 0, /*magic*/ 100, /*flatAcc*/ 0,
                /*level*/ 70, /*fame*/ 0, Job.CLERIC);
        BotEquipManager.MapDamageProfile mob =
                new BotEquipManager.MapDamageProfile(50, 30, 55);

        BotEquipManager.DpResult staffResult = BotEquipManager.solveForWeapon(
                mage, hooks, naked, staff, dpSlots, currentBySlot, bySlot, mob);
        BotEquipManager.DpResult umbrellaResult = BotEquipManager.solveForWeapon(
                mage, hooks, naked, umbrella, dpSlots, currentBySlot, bySlot, mob);

        assertNotNull(staffResult);
        assertNotNull(umbrellaResult);
        assertEquals(null, staffResult.picks().get((short) -10),
                "2H staff branch must not pick a shield");
        assertNotNull(umbrellaResult.picks().get((short) -10),
                "1H umbrella branch must pick the INT shield");
        assertTrue(umbrellaResult.score().damage() > staffResult.score().damage(),
                "umbrella + shield ensemble must outscore the bare 2H staff — got umbrella="
                        + umbrellaResult.score().damage() + " staff=" + staffResult.score().damage());
    }

    // ------------------------------------------------------------------ helpers

    private static Equip matkWeapon(int id, int matk, int pos) {
        Equip e = mock(Equip.class);
        when(e.getItemId()).thenReturn(id);
        when(e.getMatk()).thenReturn((short) matk);
        when(e.getPosition()).thenReturn((short) pos);
        return e;
    }

    private static List<Short> asList(short[] arr) {
        List<Short> out = new ArrayList<>(arr.length);
        for (short s : arr) out.add(s);
        return out;
    }

    private static Character mockBowman() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.BOWMAN);
        when(bot.getLevel()).thenReturn(50);
        when(bot.getFame()).thenReturn(0);
        return bot;
    }

    private static Equip weapon(int id, int watk, int pos) {
        Equip e = mock(Equip.class);
        when(e.getItemId()).thenReturn(id);
        when(e.getWatk()).thenReturn((short) watk);
        when(e.getPosition()).thenReturn((short) pos);
        return e;
    }

    private static Equip armor(int id, int dex, int watk, int pos) {
        Equip e = mock(Equip.class);
        when(e.getItemId()).thenReturn(id);
        when(e.getDex()).thenReturn((short) dex);
        when(e.getWatk()).thenReturn((short) watk);
        when(e.getPosition()).thenReturn((short) pos);
        return e;
    }

    /** Hooks for a bow scenario: weapons are 2H BOW; reqs are DEX-only via the lookup map. */
    private static BotEquipManager.OptimizerHooks bowHooks(Map<Integer, Integer> reqDexByItem) {
        return new BotEquipManager.OptimizerHooks() {
            @Override public boolean isTwoHanded(int itemId) { return itemId == W0_ID || itemId == W1_ID; }
            @Override public WeaponType getWeaponType(int itemId) {
                return (itemId == W0_ID || itemId == W1_ID) ? WeaponType.BOW : null;
            }
            @Override public boolean isOverall(int itemId) { return false; }
            @Override public boolean meetsReqs(Equip e, Job job, int lvl, int s, int d, int i, int l, int f) {
                int reqDex = reqDexByItem.getOrDefault(e.getItemId(), 0);
                return d >= reqDex;
            }
        };
    }
}
