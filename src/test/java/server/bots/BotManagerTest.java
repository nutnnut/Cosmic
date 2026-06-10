package server.bots;

import client.Character;
import client.BuffStat;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import client.keybind.KeyBinding;
import constants.game.CharacterStance;
import org.mockito.MockedStatic;
import org.junit.jupiter.api.Test;
import server.StatEffect;
import server.TimerManager;
import server.life.Monster;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapItem;
import server.maps.MapleMap;
import server.maps.Rope;
import testutil.Items;

import java.awt.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BotManagerTest {
    @Test
    void shouldParseTransferBotCommands() {
        BotCommandParser.BotTransferCommand command = BotCommandParser.matchBotTransferCommand("transfer Jason to Bob");

        assertNotNull(command);
        assertEquals("Jason", command.botName());
        assertEquals("Bob", command.targetName());
    }

    @Test
    void shouldStillAllowTransferWithoutTo() {
        BotCommandParser.BotTransferCommand command = BotCommandParser.matchBotTransferCommand("transfer Jason Bob");

        assertNotNull(command);
        assertEquals("Jason", command.botName());
        assertEquals("Bob", command.targetName());
    }

    @Test
    void shouldNotTreatGivePhrasesAsBotTransfers() {
        assertNull(BotCommandParser.matchBotTransferCommand("give Jason Bob"));
        assertNull(BotCommandParser.matchBotTransferCommand("give me flaming feather"));
        assertNull(BotCommandParser.matchBotTransferCommand("give flaming feather"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSkipOwnerGainOfferScanForOwnBotTradeItems() throws Exception {
        BotManager manager = BotManager.getInstance();
        Character owner = mock(Character.class);
        Character sourceBot = mock(Character.class);
        Character observerBot = mock(Character.class);
        BotEntry sourceEntry = new BotEntry(sourceBot, owner, null);
        BotEntry observerEntry = new BotEntry(observerBot, owner, null);
        Item tradedEquip = new Item(1002000, (short) 1, (short) 1);

        when(owner.getId()).thenReturn(77);
        when(sourceBot.getId()).thenReturn(10);
        when(sourceBot.getClient()).thenReturn(new client.BotClient(0, 0));
        when(observerBot.getId()).thenReturn(11);

        Map<Integer, List<BotEntry>> bots = (Map<Integer, List<BotEntry>>) field(BotManager.class, "bots").get(manager);
        bots.put(owner.getId(), List.of(sourceEntry, observerEntry));

        try (MockedStatic<BotOfferManager> offers = mockStatic(BotOfferManager.class)) {
            manager.notifyOwnerGainedTradeItem(owner, tradedEquip, sourceBot);

            offers.verifyNoInteractions();
        } finally {
            bots.remove(owner.getId());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldNotifyBotsForNonOwnBotTradeItems() throws Exception {
        BotManager manager = BotManager.getInstance();
        Character owner = mock(Character.class);
        Character observerBot = mock(Character.class);
        Character sourcePlayer = mock(Character.class);
        BotEntry observerEntry = new BotEntry(observerBot, owner, null);
        Item tradedEquip = new Item(1002000, (short) 1, (short) 1);

        when(owner.getId()).thenReturn(78);

        Map<Integer, List<BotEntry>> bots = (Map<Integer, List<BotEntry>>) field(BotManager.class, "bots").get(manager);
        bots.put(owner.getId(), List.of(observerEntry));

        TimerManager inlineTimer = mock(TimerManager.class);
        when(inlineTimer.schedule(any(Runnable.class), anyLong())).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        });
        try (MockedStatic<BotOfferManager> offers = mockStatic(BotOfferManager.class);
             MockedStatic<TimerManager> timer = mockStatic(TimerManager.class)) {
            timer.when(TimerManager::getInstance).thenReturn(inlineTimer);

            manager.notifyOwnerGainedTradeItem(owner, tradedEquip, sourcePlayer);

            offers.verify(() -> BotOfferManager.notifyOwnerGainedEquip(observerEntry, observerBot, tradedEquip));
        } finally {
            bots.remove(owner.getId());
        }
    }

    @Test
    void shouldResolveTargetedBotByPrefix() {
        BotEntry jason = botEntryNamed("Jason");
        BotEntry bob = botEntryNamed("Bob");

        BotCommandParser.TargetedBotMatch match = BotCommandParser.resolveTargetedBot(
                List.of(jason, bob), "Ja pots?");

        assertEquals(jason, match.entry());
        assertEquals("pots?", match.commandText());
        assertNull(match.feedbackMessage());
    }

    @Test
    void shouldResolveTargetedBotBySlot() {
        BotEntry jason = botEntryNamed("Jason");
        BotEntry bob = botEntryNamed("Bob");

        BotCommandParser.TargetedBotMatch match = BotCommandParser.resolveTargetedBot(
                List.of(jason, bob), "2 follow Alice");

        assertEquals(bob, match.entry());
        assertEquals("follow Alice", match.commandText());
        assertNull(match.feedbackMessage());
    }

    @Test
    void shouldRouteArbitraryBotSayThroughPartyChannel() {
        BotManager manager = spy(BotManager.getInstance());
        Character bot = mock(Character.class);
        doAnswer(invocation -> null).when(manager).botSayParty(bot, "sure!");

        manager.botSay(bot, ReplyChannel.PARTY, "sure!");

        verify(manager).botSayParty(bot, "sure!");
    }

    @Test
    void shouldReturnFeedbackForAmbiguousTargetedBotPrefix() {
        BotEntry jane = botEntryNamed("Jane");
        BotEntry jason = botEntryNamed("Jason");

        BotCommandParser.TargetedBotMatch match = BotCommandParser.resolveTargetedBot(
                List.of(jane, jason), "Ja yes");

        assertNull(match.entry());
        assertNull(match.commandText());
        assertEquals("Ambiguous bot prefix 'Ja': 1: Jane, 2: Jason. Use the full name or a slot number.",
                match.feedbackMessage());
    }

    @Test
    void shouldCountHpMpAndDualRecoveryItemsAsPotions() {
        Item hpItem = mock(Item.class);
        Item mpItem = mock(Item.class);
        Item dualItem = mock(Item.class);
        Item nonPotion = mock(Item.class);

        when(hpItem.getItemId()).thenReturn(2000002);
        when(hpItem.getQuantity()).thenReturn((short) 10);
        when(mpItem.getItemId()).thenReturn(2000003);
        when(mpItem.getQuantity()).thenReturn((short) 7);
        when(dualItem.getItemId()).thenReturn(2000004);
        when(dualItem.getQuantity()).thenReturn((short) 4);
        when(nonPotion.getItemId()).thenReturn(2040002);
        when(nonPotion.getQuantity()).thenReturn((short) 99);

        StatEffect hpEffect = mock(StatEffect.class);
        StatEffect mpEffect = mock(StatEffect.class);
        StatEffect dualEffect = mock(StatEffect.class);
        StatEffect nonPotionEffect = mock(StatEffect.class);

        when(hpEffect.getHp()).thenReturn((short) 300);
        when(hpEffect.getHpRate()).thenReturn(0d);
        when(hpEffect.getMp()).thenReturn((short) 0);
        when(hpEffect.getMpRate()).thenReturn(0d);

        when(mpEffect.getHp()).thenReturn((short) 0);
        when(mpEffect.getHpRate()).thenReturn(0d);
        when(mpEffect.getMp()).thenReturn((short) 100);
        when(mpEffect.getMpRate()).thenReturn(0d);

        when(dualEffect.getHp()).thenReturn((short) 0);
        when(dualEffect.getHpRate()).thenReturn(50d);
        when(dualEffect.getMp()).thenReturn((short) 0);
        when(dualEffect.getMpRate()).thenReturn(50d);

        when(nonPotionEffect.getHp()).thenReturn((short) 0);
        when(nonPotionEffect.getHpRate()).thenReturn(0d);
        when(nonPotionEffect.getMp()).thenReturn((short) 0);
        when(nonPotionEffect.getMpRate()).thenReturn(0d);

        java.util.Map<Integer, StatEffect> effects = java.util.Map.of(
                2000002, hpEffect,
                2000003, mpEffect,
                2000004, dualEffect,
                2040002, nonPotionEffect);

        int[] counts = BotPotionManager.countPotions(
                java.util.List.of(hpItem, mpItem, dualItem, nonPotion),
                effects::get);

        assertEquals(14, counts[0]);
        assertEquals(11, counts[1]);
    }

    @Test
    void shouldUseCombatRetreatTargetOnlyWithinSameGroundRegion() {
        MapleMap map = createEmptyTestMap(910000020);
        FootholdTree footholds = map.getFootholds();
        footholds.insert(new Foothold(new Point(0, 100), new Point(200, 100), 1));
        BotNavigationGraphProvider.rebuildGraph(map);

        Character bot = mock(Character.class);
        when(bot.getMap()).thenReturn(map);
        BotEntry entry = new BotEntry(bot, null, null);

        assertTrue(BotManager.shouldUseLocalCombatRetreatTarget(
                entry,
                new Point(100, 100),
                new Point(130, 100),
                new Point(60, 100)));
    }

    @Test
    void shouldRejectCombatRetreatTargetWhenMonsterIsInDifferentRegion() {
        MapleMap map = createEmptyTestMap(910000021);
        FootholdTree footholds = map.getFootholds();
        footholds.insert(new Foothold(new Point(0, 100), new Point(200, 100), 1));
        footholds.insert(new Foothold(new Point(0, 40), new Point(200, 40), 2));
        BotNavigationGraphProvider.rebuildGraph(map);

        Character bot = mock(Character.class);
        when(bot.getMap()).thenReturn(map);
        BotEntry entry = new BotEntry(bot, null, null);

        assertFalse(BotManager.shouldUseLocalCombatRetreatTarget(
                entry,
                new Point(100, 100),
                new Point(100, 40),
                new Point(60, 100)));
    }

    @Test
    void shouldRejectCombatRetreatTargetWhileClimbing() {
        MapleMap map = createEmptyTestMap(910000022);
        FootholdTree footholds = map.getFootholds();
        footholds.insert(new Foothold(new Point(0, 100), new Point(200, 100), 1));
        footholds.insert(new Foothold(new Point(0, 40), new Point(200, 40), 2));
        map.addRope(new Rope(100, 40, 100, false));
        BotNavigationGraphProvider.rebuildGraph(map);

        Character bot = mock(Character.class);
        when(bot.getMap()).thenReturn(map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.climbing = true;
        entry.climbRope = new Rope(100, 40, 100, false);

        assertFalse(BotManager.shouldUseLocalCombatRetreatTarget(
                entry,
                new Point(100, 100),
                new Point(140, 40),
                new Point(60, 100)));
    }

    @Test
    void shouldRecoverGrindingBotToInBoundsOwnerWhenOutOfBounds() {
        MapleMap map = createEmptyTestMap(910000052);
        map.setMapLineBoundings(-500, 500, -500, 500);
        map.getFootholds().insert(new Foothold(new Point(0, 100), new Point(200, 100), 1));
        Character owner = mockMovingBot(new Point(100, 100), map);
        Character bot = mockMovingBot(new Point(100, 1700), map);
        BotEntry entry = new BotEntry(bot, owner, null);
        entry.grinding = true;
        // Mid-map recovery, not a fresh map change: the map-change tick (which now runs before
        // the recovery checks) must not consume the tick.
        entry.lastMapId = map.getId();

        BotManager.getInstance().stepMovementOnly(entry, bot.getPosition(), owner.getPosition(), true);

        assertEquals(new Point(100, 100), bot.getPosition());
        assertFalse(entry.inAir);
        assertFalse(entry.climbing);
    }

    @Test
    void shouldRespawnDeadBotEvenWhenOwnerIsUnavailable() throws Exception {
        MapleMap map = createEmptyTestMap(910000053);
        Character bot = mockMovingBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.lastMapId = map.getId();
        entry.deadUntil = System.currentTimeMillis() - 1;

        Method handleDeadTick = BotManager.class.getDeclaredMethod(
                "handleDeadTick", BotEntry.class, Character.class, Character.class);
        handleDeadTick.setAccessible(true);

        assertTrue((Boolean) handleDeadTick.invoke(BotManager.getInstance(), entry, bot, null));
        assertEquals(0L, entry.deadUntil);
        verify(bot).respawn(map.getReturnMapId());
    }

    @Test
    void shouldNotUseLowerPlatformDropAsCrossRegionRetreat() {
        MapleMap map = createEmptyTestMap(910000060);
        FootholdTree footholds = map.getFootholds();
        footholds.insert(new Foothold(new Point(0, 100), new Point(500, 100), 1));
        footholds.insert(new Foothold(new Point(0, 220), new Point(500, 220), 2));
        BotNavigationGraphProvider.rebuildGraph(map);

        Character bot = mock(Character.class);
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(250, 100));
        BotEntry entry = new BotEntry(bot, null, null);

        assertNull(BotManager.selectCrossRegionRetreatTarget(
                entry,
                new Point(250, 100),
                new Point(300, 100)));
    }

    @Test
    void shouldUseJumpReachablePlatformAsCrossRegionRetreat() {
        MapleMap map = createEmptyTestMap(910000061);
        FootholdTree footholds = map.getFootholds();
        footholds.insert(new Foothold(new Point(0, 100), new Point(200, 100), 1));
        footholds.insert(new Foothold(new Point(250, 100), new Point(500, 100), 2));
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);

        Character bot = mock(Character.class);
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(300, 100));
        when(bot.getSkills()).thenReturn(Map.of());
        BotEntry entry = new BotEntry(bot, null, null);

        Point retreat = BotManager.selectCrossRegionRetreatTarget(
                entry,
                new Point(300, 100),
                new Point(330, 100));

        assertNotNull(retreat);
        assertTrue(retreat.x <= 200);
        assertTrue(Math.abs(retreat.x - 330) > BotCombatManager.cfg.RANGED_DEGENERATE_RANGE_X);

        int startRegionId = BotNavigationManager.resolveCurrentRegionId(graph, entry, map, new Point(300, 100));
        int retreatRegionId = BotNavigationManager.resolveTargetRegionId(graph, entry, map, retreat);
        List<BotNavigationGraph.Edge> path = BotNavigationManager.findPath(
                graph, map, new Point(300, 100), startRegionId, retreatRegionId, retreat);
        assertFalse(path.isEmpty());
        assertEquals(BotNavigationGraph.EdgeType.JUMP, path.get(0).type);
    }

    @Test
    void shouldPreferRangedAttackTargetOverDegeneratePreferredTarget() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, null, null);
        Point botPos = new Point(100, 100);
        Monster closeMob = mockMob(new Point(150, 100), 9300400);
        Monster rangedMob = mockMob(new Point(260, 100), 9300401);
        BotCombatManager.AttackPlan rangedPlan = new BotCombatManager.AttackPlan(
                0, 0, 1, new Rectangle(105, 50, 395, 100),
                List.of(rangedMob), BotCombatManager.AttackRoute.RANGED,
                0, 11, 11, 11, 4, 300, 600, null);

        when(bot.getMap()).thenReturn(map);
        when(map.getAllMonsters()).thenReturn(List.of(closeMob, rangedMob));

        try (MockedStatic<BotAttackExecutionProvider> attacks =
                     mockStatic(BotAttackExecutionProvider.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             MockedStatic<BotCombatManager> combat =
                     mockStatic(BotCombatManager.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            attacks.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot)).thenReturn(WeaponType.BOW);
            combat.when(() -> BotCombatManager.planAttack(entry, bot, rangedMob)).thenReturn(rangedPlan);
            combat.when(() -> BotCombatManager.isTargetInAttackRange(rangedPlan, bot, rangedMob)).thenReturn(true);

            assertEquals(rangedMob, BotManager.selectPriorityRangedAttackTarget(entry, bot, botPos, closeMob));
        }
    }

    @Test
    void shouldKeepMovingWhenInRangeRangedAttackDoesNotFire() {
        MapleMap map = createEmptyTestMap(910000062);
        map.getFootholds().insert(new Foothold(new Point(-200, 100), new Point(200, 100), 1));
        Character bot = mockMovingBot(new Point(100, 100), map);
        Monster target = mockMob(new Point(-50, 100), 9300500);
        when(target.getMap()).thenReturn(map);

        BotEntry entry = new BotEntry(bot, null, null);
        entry.grinding = true;
        entry.grindTarget = target;
        entry.lastMapId = map.getId();
        BotCombatManager.AttackPlan rangedPlan = new BotCombatManager.AttackPlan(
                0, 0, 1, new Rectangle(-200, 50, 300, 100),
                List.of(target), BotCombatManager.AttackRoute.RANGED,
                0, 11, 11, 11, 4, 300, 600, null);

        try (MockedStatic<BotAttackExecutionProvider> attacks =
                     mockStatic(BotAttackExecutionProvider.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             MockedStatic<BotCombatManager> combat =
                     mockStatic(BotCombatManager.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            attacks.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot)).thenReturn(WeaponType.CLAW);
            combat.when(() -> BotCombatManager.planAttack(entry, bot, target)).thenReturn(rangedPlan);
            combat.when(() -> BotCombatManager.isTargetInAttackRange(rangedPlan, bot, target)).thenReturn(true);
            combat.when(() -> BotCombatManager.canUseAttackPlanNow(entry, WeaponType.CLAW, rangedPlan)).thenReturn(true);
            combat.when(() -> BotCombatManager.attackMonster(entry, bot, rangedPlan)).thenAnswer(invocation -> null);

            BotManager.getInstance().stepMovementOnly(entry, target.getPosition(), target.getPosition(), false);
        }

        assertTrue(bot.getPosition().x < 100);
    }

    @Test
    void shouldCommitToBreakoutDirectionWhenSurroundedDespiteTargetSwap() {
        MapleMap map = spy(createEmptyTestMap(910000063));
        map.getFootholds().insert(new Foothold(new Point(-500, 100), new Point(500, 100), 1));
        BotNavigationGraphProvider.rebuildGraph(map);
        Character bot = mock(Character.class);
        Point botPos = new Point(100, 100);
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(botPos));
        BotEntry entry = new BotEntry(bot, null, null);

        // Pincer: a mob inside the retreat band on each side (dx 60 <= 80, dy 0 <= 50).
        Monster leftMob = mockMob(new Point(40, 100), 9300600);
        Monster rightMob = mockMob(new Point(160, 100), 9300601);
        doReturn(List.of(leftMob, rightMob)).when(map).getAllMonsters();

        try (MockedStatic<BotAttackExecutionProvider> attacks =
                     mockStatic(BotAttackExecutionProvider.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            attacks.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot)).thenReturn(WeaponType.BOW);

            Point first = BotManager.selectGrindNavigationTarget(entry, botPos, rightMob.getPosition());
            int dir1 = Integer.signum(first.x - botPos.x);
            assertTrue(dir1 != 0);
            assertTrue(entry.breakoutDirection != 0);

            // Crowding-swap points the active target at the OTHER flank on later ticks; the
            // committed breakout must not reverse (that flip is the oscillation we are fixing).
            Point second = BotManager.selectGrindNavigationTarget(entry, botPos, leftMob.getPosition());
            assertEquals(dir1, Integer.signum(second.x - botPos.x));
            Point third = BotManager.selectGrindNavigationTarget(entry, botPos, rightMob.getPosition());
            assertEquals(dir1, Integer.signum(third.x - botPos.x));
        }
    }

    @Test
    void shouldClearBreakoutOnceNoLongerSurrounded() {
        MapleMap map = spy(createEmptyTestMap(910000064));
        map.getFootholds().insert(new Foothold(new Point(-500, 100), new Point(500, 100), 1));
        BotNavigationGraphProvider.rebuildGraph(map);
        Character bot = mock(Character.class);
        Point botPos = new Point(100, 100);
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(botPos));
        BotEntry entry = new BotEntry(bot, null, null);
        entry.breakoutDirection = -1;
        entry.breakoutUntilMs = System.currentTimeMillis() + 5_000L;

        // Only one flank occupied -> not surrounded -> the breakout latch must release.
        Monster rightMob = mockMob(new Point(160, 100), 9300602);
        doReturn(List.of(rightMob)).when(map).getAllMonsters();

        try (MockedStatic<BotAttackExecutionProvider> attacks =
                     mockStatic(BotAttackExecutionProvider.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            attacks.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot)).thenReturn(WeaponType.BOW);

            BotManager.selectGrindNavigationTarget(entry, botPos, rightMob.getPosition());
            assertEquals(0, entry.breakoutDirection);
        }
    }

    @Test
    void shouldNotEngageBreakoutForSingleMobKiting() {
        MapleMap map = spy(createEmptyTestMap(910000065));
        map.getFootholds().insert(new Foothold(new Point(-500, 100), new Point(500, 100), 1));
        BotNavigationGraphProvider.rebuildGraph(map);
        Character bot = mock(Character.class);
        Point botPos = new Point(100, 100);
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(botPos));
        BotEntry entry = new BotEntry(bot, null, null);

        Monster rightMob = mockMob(new Point(160, 100), 9300603);
        doReturn(List.of(rightMob)).when(map).getAllMonsters();

        try (MockedStatic<BotAttackExecutionProvider> attacks =
                     mockStatic(BotAttackExecutionProvider.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            attacks.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot)).thenReturn(WeaponType.BOW);

            Point nav = BotManager.selectGrindNavigationTarget(entry, botPos, rightMob.getPosition());
            // Single mob -> ordinary local kiting (retreat away from the mob), no breakout latch.
            assertEquals(0, entry.breakoutDirection);
            assertEquals(BotAttackExecutionProvider.retreatTargetPosition(bot, botPos, rightMob.getPosition()), nav);
        }
    }

    @Test
    void shouldResetPhysicsWhenOnlineBotIsSpawnedAtOwnerPosition() {
        MapleMap map = createEmptyTestMap(910000023);
        map.getFootholds().insert(new Foothold(new Point(0, 100), new Point(200, 100), 1));
        Character bot = mockMovingBot(new Point(20, 100), map);
        BotEntry entry = new BotEntry(bot, mock(Character.class), null);
        entry.inAir = true;
        entry.physX = -999;
        entry.physY = -999;
        entry.velY = 20f;
        entry.airVelX = 6;
        entry.navTargetPos = new Point(120, 100);

        BotManager.placeSpawnedOnlineBot(entry, bot, map, new Point(80, 100));

        assertEquals(new Point(80, 100), bot.getPosition());
        assertFalse(entry.inAir);
        assertEquals(80.0, entry.physX);
        assertEquals(100.0, entry.physY);
        assertEquals(0, entry.airVelX);
        assertNull(entry.navTargetPos);
        assertEquals(map.getId(), entry.lastMapId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldCleanBotRuntimeStateWhenLeavingBotControl() throws Exception {
        BotManager manager = BotManager.getInstance();
        Character owner = mock(Character.class);
        Character bot = mock(Character.class);
        ScheduledFuture<?> task = mock(ScheduledFuture.class);
        Map<Integer, KeyBinding> keymap = new LinkedHashMap<>();
        keymap.put(91, new KeyBinding(2, 2000002));
        keymap.put(92, new KeyBinding(7, 2000003));

        when(owner.getId()).thenReturn(77);
        when(bot.getId()).thenReturn(88);
        when(bot.getKeymap()).thenReturn(keymap);
        doAnswer(invocation -> {
            int key = invocation.getArgument(0);
            KeyBinding binding = invocation.getArgument(1);
            if (binding.getType() == 0) {
                keymap.remove(key);
            } else {
                keymap.put(key, binding);
            }
            return null;
        }).when(bot).changeKeybinding(anyInt(), any(KeyBinding.class));

        BotEntry entry = new BotEntry(bot, owner, task);
        Map<Integer, List<BotEntry>> bots = (Map<Integer, List<BotEntry>>) field(BotManager.class, "bots").get(manager);
        bots.put(owner.getId(), new CopyOnWriteArrayList<>(List.of(entry)));
        try {
            assertTrue(manager.cleanupBotRuntimeState(bot));

            assertFalse(bots.containsKey(owner.getId()));
            assertEquals(7, keymap.get(91).getType());
            assertEquals(2000002, keymap.get(91).getAction());
            assertEquals(7, keymap.get(92).getType());
            assertEquals(2000003, keymap.get(92).getAction());
            verify(task).cancel(false);
            verify(bot).setAutopotHpAlert(0f);
            verify(bot).setAutopotMpAlert(0f);
        } finally {
            bots.remove(owner.getId());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldDisableBotAfterRepeatedTickFailures() throws Exception {
        BotManager manager = BotManager.getInstance();
        Character owner = mock(Character.class);
        Character bot = mock(Character.class);
        ScheduledFuture<?> task = mock(ScheduledFuture.class);

        when(owner.getId()).thenReturn(77);
        when(owner.getName()).thenReturn("Owner");
        when(bot.getId()).thenReturn(88);
        when(bot.getName()).thenReturn("Bot");
        when(bot.getMapId()).thenReturn(100000000);

        BotEntry entry = new BotEntry(bot, owner, task);
        entry.pendingAction = "drop";
        entry.pendingDropCategory = "equips";
        entry.grindLootTarget = mock(MapItem.class);
        entry.following = true;
        entry.grinding = true;
        entry.moveTarget = new Point(100, 100);

        Map<Integer, List<BotEntry>> bots = (Map<Integer, List<BotEntry>>) field(BotManager.class, "bots").get(manager);
        bots.put(owner.getId(), new CopyOnWriteArrayList<>(List.of(entry)));
        Method failureHandler = method(BotManager.class, "handleBotTickFailure",
                BotEntry.class, int.class, int.class, Throwable.class);

        try {
            failureHandler.invoke(manager, entry, owner.getId(), bot.getId(), new NullPointerException("bad drop"));
            assertTrue(bots.containsKey(owner.getId()));
            assertNull(entry.pendingAction);
            assertNull(entry.pendingDropCategory);
            assertNull(entry.grindLootTarget);
            assertTrue(entry.following);
            assertTrue(entry.grinding);

            failureHandler.invoke(manager, entry, owner.getId(), bot.getId(), new NullPointerException("bad drop"));
            assertTrue(bots.containsKey(owner.getId()));
            assertFalse(entry.following);
            assertFalse(entry.grinding);
            assertNull(entry.moveTarget);

            failureHandler.invoke(manager, entry, owner.getId(), bot.getId(), new NullPointerException("bad drop"));
            assertFalse(bots.containsKey(owner.getId()));
            verify(task).cancel(false);
        } finally {
            bots.remove(owner.getId());
        }
    }

    @Test
    void shouldUseFollowIdleFastPathOnlyWhileParkedNearTarget() {
        MapleMap map = createEmptyTestMap(910000024);
        map.getFootholds().insert(new Foothold(new Point(0, 100), new Point(200, 100), 1));
        Character bot = mockMovingBot(new Point(80, 100), map);
        BotEntry entry = new BotEntry(bot, mock(Character.class), null);
        entry.following = true;

        assertTrue(BotManager.tryFollowIdleMovementFastPath(entry, bot, new Point(100, 100), 1_000L));
        assertEquals("idle-fast", entry.lastNavDecision);
        assertTrue(BotManager.tryFollowIdleMovementFastPath(entry, bot, new Point(100, 100), 1_500L),
                "idle follow bots should skip per-tick nav/ground movement between periodic checks");
        assertFalse(BotManager.tryFollowIdleMovementFastPath(entry, bot, new Point(100, 100), 2_000L),
                "idle fast path should allow a periodic full movement/nav check");

        entry.observedOwnerStepX = 1;
        assertFalse(BotManager.tryFollowIdleMovementFastPath(entry, bot, new Point(100, 100), 2_100L),
                "owner movement should force normal movement resolution");
    }

    @Test
    void shouldKeepAttackableGrindTargetInsteadOfRetargetingDuringCooldown() {
        MapleMap map = createEmptyTestMap(910000028);
        Character bot = mockMovingBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, mock(Character.class), null);
        entry.nextGrindTargetSearchAtMs = 1_000L;
        Monster target = mock(Monster.class);
        when(target.getPosition()).thenReturn(new Point(140, 100));
        BotCombatManager.AttackPlan plan = basicClosePlan(target);

        assertFalse(BotManager.shouldSearchForGrindTarget(entry, bot, target, plan, 1_000L));
    }

    @Test
    void shouldRetargetWhenCurrentGrindTargetIsNotAttackableAndIntervalElapsed() {
        MapleMap map = createEmptyTestMap(910000029);
        Character bot = mockMovingBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, mock(Character.class), null);
        entry.nextGrindTargetSearchAtMs = 1_000L;
        Monster target = mock(Monster.class);
        when(target.getPosition()).thenReturn(new Point(300, 100));
        BotCombatManager.AttackPlan plan = basicClosePlan(target);

        assertTrue(BotManager.shouldSearchForGrindTarget(entry, bot, target, plan, 1_000L));
    }

    @Test
    void shouldKeepScanningForClusterWhenAoeBotSingleTargetsInRange() {
        MapleMap map = createEmptyTestMap(910000128);
        Character bot = mockMovingBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, mock(Character.class), null);
        entry.nextGrindTargetSearchAtMs = 1_000L;
        entry.aoeSkillId = constants.skills.Warrior.SLASH_BLAST;
        entry.aoeSkillMobs = 6;
        Monster target = mock(Monster.class);
        when(target.getPosition()).thenReturn(new Point(140, 100));
        BotCombatManager.AttackPlan singleTargetPlan = basicClosePlan(target);

        // In range, but a multi-mob AoE bot stuck single-targeting should keep scanning for a cluster.
        assertTrue(BotManager.shouldSearchForGrindTarget(entry, bot, target, singleTargetPlan, 1_000L));
    }

    @Test
    void shouldAdoptSearchedTargetWhenNotCommitted() {
        MapleMap map = createEmptyTestMap(910000129);
        Character bot = mockMovingBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, mock(Character.class), null);
        Monster current = mock(Monster.class);
        when(current.getPosition()).thenReturn(new Point(300, 100)); // out of basic range
        Monster searched = mock(Monster.class);
        when(searched.getPosition()).thenReturn(new Point(160, 100));
        BotCombatManager.AttackPlan plan = basicClosePlan(current);

        assertTrue(BotManager.shouldSwitchToSearchedTarget(entry, bot, current, searched, plan));
    }

    @Test
    void shouldNotSwitchInRangeTargetWhenClusterIsNotLarger() {
        MapleMap map = createEmptyTestMap(910000130);
        Character bot = mockMovingBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, mock(Character.class), null);
        entry.aoeSkillId = constants.skills.Warrior.SLASH_BLAST;
        entry.aoeSkillMobs = 6;
        Monster current = mock(Monster.class);
        when(current.getPosition()).thenReturn(new Point(140, 100)); // in basic range
        Monster searched = mock(Monster.class);
        when(searched.getPosition()).thenReturn(new Point(160, 100));
        BotCombatManager.AttackPlan plan = basicClosePlan(current);

        // Committed to an in-range target and the searched mob anchors no larger cluster (empty map).
        assertFalse(BotManager.shouldSwitchToSearchedTarget(entry, bot, current, searched, plan));
    }

    @Test
    void shouldReuseWanderDirectionWhenGrindHasNoTarget() {
        Character bot = mockMovingBot(new Point(100, 100), createEmptyTestMap(910000030));
        BotEntry entry = new BotEntry(bot, mock(Character.class), null);

        Point first = BotManager.resolveNoGrindTargetPosition(entry, bot.getPosition());
        int direction = entry.wanderDirection;
        Point second = BotManager.resolveNoGrindTargetPosition(entry, bot.getPosition());

        assertTrue(direction == -1 || direction == 1);
        assertEquals(new Point(100 + direction * 200, 100), first);
        assertEquals(first, second);
    }

    @Test
    void shouldIgnoreCachedGrindLootInsidePassiveLootRadiusWhenNoMobTarget() {
        Character bot = mockMovingBot(new Point(100, 100), createEmptyTestMap(910000034));
        BotEntry entry = new BotEntry(bot, mock(Character.class), null);
        entry.wanderDirection = 1;
        MapItem nearbyLoot = mockLoot(1, new Point(100 + BotManager.cfg.LOOT_RADIUS, 100));
        entry.grindLootTarget = nearbyLoot;

        Point target = BotManager.resolveNoGrindTargetPosition(entry, bot.getPosition());

        assertEquals(new Point(300, 100), target);
        assertNull(entry.grindLootTarget);
    }

    @Test
    void shouldOnlyActivelySeekGrindLootOutsidePassiveLootRadius() {
        MapleMap map = spy(createEmptyTestMap(910000035));
        Character bot = mockMovingBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, mock(Character.class), null);
        MapItem passiveLoot = mockLoot(1, new Point(100 + BotManager.cfg.LOOT_RADIUS, 100));
        MapItem activeLoot = mockLoot(2, new Point(100 + BotManager.cfg.LOOT_RADIUS + 1, 100));
        int passiveLootObjectId = passiveLoot.getObjectId();
        int activeLootObjectId = activeLoot.getObjectId();
        doReturn(List.of(passiveLoot, activeLoot)).when(map).getDroppedItems();
        doReturn(passiveLoot).when(map).getMapObject(passiveLootObjectId);
        doReturn(activeLoot).when(map).getMapObject(activeLootObjectId);

        assertEquals(activeLoot, BotInventoryManager.findNearestGrindLootTarget(entry, bot));
    }

    @Test
    void shouldNotActivelySeekFreshGrindBotInventoryDrop() {
        MapleMap map = spy(createEmptyTestMap(910000131));
        Character bot = mockMovingBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, mock(Character.class), null);
        MapItem loot = mockLoot(1, new Point(100 + BotManager.cfg.LOOT_RADIUS + 1, 100));
        int lootObjectId = loot.getObjectId();
        BotManager manager = mock(BotManager.class);
        Character dropBotOwner = mock(Character.class);

        when(loot.getOwnerId()).thenReturn(99);
        when(loot.isPlayerDrop()).thenReturn(true);
        when(loot.getDropTime()).thenReturn(System.currentTimeMillis() - 14_000L);
        when(manager.getActiveOwnerByBotCharId(99)).thenReturn(dropBotOwner);
        doReturn(List.of(loot)).when(map).getDroppedItems();
        doReturn(loot).when(map).getMapObject(lootObjectId);

        try (MockedStatic<BotManager> botManagers = mockStatic(BotManager.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            botManagers.when(BotManager::getInstance).thenReturn(manager);

            assertNull(BotInventoryManager.findNearestGrindLootTarget(entry, bot));
        }
    }

    @Test
    void shouldClearFreshCachedGrindBotInventoryDrop() {
        MapleMap map = spy(createEmptyTestMap(910000132));
        Character bot = mockMovingBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, mock(Character.class), null);
        MapItem loot = mockLoot(1, new Point(100 + BotManager.cfg.LOOT_RADIUS + 21, 100));
        int lootObjectId = loot.getObjectId();
        BotManager manager = mock(BotManager.class);
        Character dropBotOwner = mock(Character.class);

        entry.grindLootTarget = loot;
        when(loot.getOwnerId()).thenReturn(99);
        when(loot.isPlayerDrop()).thenReturn(true);
        when(loot.getDropTime()).thenReturn(System.currentTimeMillis() - 14_000L);
        when(manager.getActiveOwnerByBotCharId(99)).thenReturn(dropBotOwner);
        doReturn(loot).when(map).getMapObject(lootObjectId);

        try (MockedStatic<BotManager> botManagers = mockStatic(BotManager.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            botManagers.when(BotManager::getInstance).thenReturn(manager);

            assertNull(BotManager.convenientLootTarget(entry, bot.getPosition(), new Point(500, 100)));
            assertNull(entry.grindLootTarget);
        }
    }

    @Test
    void shouldTemporarilyIgnoreGrindLootThatRemainsAfterEnteringPassiveLootRadius() {
        MapleMap map = spy(createEmptyTestMap(910000037));
        Character bot = mockMovingBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, mock(Character.class), null);
        MapItem loot = mockLoot(1, new Point(100 + BotManager.cfg.LOOT_RADIUS, 100));
        int lootObjectId = loot.getObjectId();
        entry.wanderDirection = 1;
        entry.grindLootTarget = loot;
        doReturn(List.of(loot)).when(map).getDroppedItems();
        doReturn(loot).when(map).getMapObject(lootObjectId);

        BotManager.resolveNoGrindTargetPosition(entry, bot.getPosition());
        bot.setPosition(new Point(99, 100));

        assertNull(BotInventoryManager.findNearestGrindLootTarget(entry, bot));
    }

    @Test
    void shouldNotActivelySeekGrindLootWhenAnyInventoryIsFull() {
        MapleMap map = spy(createEmptyTestMap(910000038));
        Character bot = mockMovingBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, mock(Character.class), null);
        MapItem loot = mockLoot(1, new Point(100 + BotManager.cfg.LOOT_RADIUS + 1, 100));
        Inventory fullEquip = mock(Inventory.class);
        when(fullEquip.isFull()).thenReturn(true);
        when(bot.getInventory(InventoryType.EQUIP)).thenReturn(fullEquip);
        doReturn(List.of(loot)).when(map).getDroppedItems();

        assertNull(BotInventoryManager.findNearestGrindLootTarget(entry, bot));
    }

    @Test
    void shouldNotActivelySeekKpqPassDrops() {
        MapleMap map = spy(createEmptyTestMap(910000039));
        Character bot = mockMovingBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, mock(Character.class), null);
        MapItem pass = mockLoot(1, new Point(100 + BotManager.cfg.LOOT_RADIUS + 1, 100), 4001008, 0, 0);
        int passObjectId = pass.getObjectId();
        doReturn(List.of(pass)).when(map).getDroppedItems();
        doReturn(pass).when(map).getMapObject(passObjectId);

        assertNull(BotInventoryManager.findNearestGrindLootTarget(entry, bot));
    }

    @Test
    void shouldNotActivelySeekSkippedKpqCouponDrops() {
        MapleMap map = spy(createEmptyTestMap(910000040));
        Character bot = mockMovingBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, mock(Character.class), null);
        entry.kpq.state = 4; // KPQ stage 1 SECOND_WALK: coupons should no longer be looted.
        MapItem coupon = mockLoot(1, new Point(100 + BotManager.cfg.LOOT_RADIUS + 1, 100), 4001007, 0, 0);
        int couponObjectId = coupon.getObjectId();
        doReturn(List.of(coupon)).when(map).getDroppedItems();
        doReturn(coupon).when(map).getMapObject(couponObjectId);

        assertNull(BotInventoryManager.findNearestGrindLootTarget(entry, bot));
    }

    @Test
    void shouldNotActivelySeekUnneededQuestDrops() {
        MapleMap map = spy(createEmptyTestMap(910000041));
        Character bot = mockMovingBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, mock(Character.class), null);
        MapItem questDrop = mockLoot(1, new Point(100 + BotManager.cfg.LOOT_RADIUS + 1, 100), 4000000, 0, 2000);
        int questDropObjectId = questDrop.getObjectId();
        when(bot.needQuestItem(2000, 4000000)).thenReturn(false);
        doReturn(List.of(questDrop)).when(map).getDroppedItems();
        doReturn(questDrop).when(map).getMapObject(questDropObjectId);

        assertNull(BotInventoryManager.findNearestGrindLootTarget(entry, bot));
    }

    @Test
    void shouldScoreGrindLootByTravelNeededBeyondPassiveLootRadius() {
        MapleMap map = spy(createEmptyTestMap(910000036));
        Character bot = mockMovingBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, mock(Character.class), null);
        Point botPos = bot.getPosition();
        Point mobPos = new Point(500, 100);
        Point lootPos = new Point(100 + BotManager.cfg.LOOT_RADIUS + 21, 100);
        MapItem loot = mockLoot(1, lootPos);
        int lootObjectId = loot.getObjectId();
        entry.grindLootTarget = loot;
        doReturn(loot).when(map).getMapObject(lootObjectId);

        assertEquals(lootPos, BotManager.convenientLootTarget(entry, botPos, mobPos));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldResolveFollowTargetRegionFromFollowAnchorInsteadOfOwner() throws Exception {
        MapleMap map = createEmptyTestMap(910000025);
        map.getFootholds().insert(new Foothold(new Point(0, 100), new Point(400, 100), 1));
        map.addRope(new Rope(100, 40, 100, false));
        BotNavigationGraphProvider.rebuildGraph(map);

        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(77);
        when(owner.getMap()).thenReturn(map);
        when(owner.getPosition()).thenReturn(new Point(100, 60));
        when(owner.getStance()).thenReturn(CharacterStance.LADDER_STANCE);

        Character follower = mockMovingBot(new Point(100, 60), map);
        when(follower.getId()).thenReturn(88);
        Character followAnchor = mockMovingBot(new Point(300, 100), map);
        when(followAnchor.getId()).thenReturn(99);
        when(followAnchor.getName()).thenReturn("BotB");
        when(followAnchor.isLoggedinWorld()).thenReturn(true);

        BotEntry followerEntry = new BotEntry(follower, owner, null);
        followerEntry.following = true;
        followerEntry.followTargetId = followAnchor.getId();
        BotEntry anchorEntry = new BotEntry(followAnchor, owner, null);

        BotManager manager = BotManager.getInstance();
        Map<Integer, List<BotEntry>> bots = (Map<Integer, List<BotEntry>>) field(BotManager.class, "bots").get(manager);
        bots.put(owner.getId(), List.of(followerEntry, anchorEntry));
        try {
            BotNavigationGraph graph = BotNavigationGraphProvider.peekGraph(map);
            int targetRegionId = BotNavigationManager.resolveTargetRegionId(
                    graph, followerEntry, map, new Point(300, 100));
            BotNavigationGraph.Region targetRegion = graph.getRegion(targetRegionId);

            assertNotNull(targetRegion);
            assertFalse(targetRegion.isRopeRegion,
                    "botA follow botB should resolve navigation against botB, not owner's rope");
            assertEquals("BotB", manager.captureTargetSnapshot(followerEntry).followAnchorName());
        } finally {
            bots.remove(owner.getId());
        }
    }

    @Test
    void shouldUseShopTargetAsPrimaryWhileResupplying() {
        MapleMap map = createEmptyTestMap(910000026);
        Character owner = mockMovingBot(new Point(50, 100), map);
        Character bot = mockMovingBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, owner, null);
        entry.following = true;
        entry.shopVisitPending = true;
        entry.shopNpcPos = new Point(900, 100);
        entry.shopTargetPos = new Point(850, 100);

        BotManager.TargetSnapshot snapshot = BotManager.getInstance().captureTargetSnapshot(entry);

        assertEquals(new Point(850, 100), snapshot.primaryTargetPos());
        assertEquals("shop-target", snapshot.primaryTargetSource());
    }

    @Test
    void shouldUseFarmHereAnchorAsPrimaryTargetAndEnterGrindMode() {
        MapleMap map = createEmptyTestMap(910000031);
        Character owner = mockMovingBot(new Point(50, 100), map);
        Character bot = mockMovingBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, owner, null);

        BotManager.getInstance().issueFarmHere(entry, new Point(300, 100));

        assertEquals(new Point(300, 100), entry.farmAnchor);
        assertEquals(map.getId(), entry.farmAnchorMapId);
        assertEquals(new Point(300, 100), entry.moveTarget);
        assertTrue(entry.moveTargetPrecise);
        assertFalse(entry.following);
        assertTrue(entry.grinding);

        BotManager.TargetSnapshot snapshot = BotManager.getInstance().captureTargetSnapshot(entry);
        assertEquals(new Point(300, 100), snapshot.primaryTargetPos());
        assertEquals("move-target", snapshot.primaryTargetSource());
    }

    @Test
    void shouldKeepFarmHereAnchorPrimaryAfterArrivalClearsMoveTarget() {
        MapleMap map = createEmptyTestMap(910000032);
        Character owner = mockMovingBot(new Point(50, 100), map);
        Character bot = mockMovingBot(new Point(300, 100), map);
        BotEntry entry = new BotEntry(bot, owner, null);
        entry.farmAnchor = new Point(300, 100);
        entry.farmAnchorMapId = map.getId();

        BotManager.TargetSnapshot snapshot = BotManager.getInstance().captureTargetSnapshot(entry);

        assertEquals(new Point(300, 100), snapshot.primaryTargetPos());
        assertEquals("farm-anchor", snapshot.primaryTargetSource());
    }

    @Test
    void shouldCancelShopVisitWhenOwnerIssuesFollow() {
        MapleMap map = createEmptyTestMap(910000027);
        Character owner = mockMovingBot(new Point(50, 100), map);
        Character bot = mockMovingBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, owner, null);
        entry.shopVisitPending = true;
        entry.shopSequenceActive = true;
        entry.shopNpcPos = new Point(900, 100);
        entry.shopTargetPos = new Point(850, 100);

        BotManager.getInstance().issueFollowOwner(entry);

        assertFalse(entry.shopVisitPending);
        assertFalse(entry.shopSequenceActive);
        assertNull(entry.shopNpcPos);
        assertNull(entry.shopTargetPos);
        assertTrue(entry.following);
    }

    @Test
    void shouldClearFarmHereAnchorWhenOwnerIssuesFollow() {
        MapleMap map = createEmptyTestMap(910000033);
        Character owner = mockMovingBot(new Point(50, 100), map);
        Character bot = mockMovingBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, owner, null);
        entry.farmAnchor = new Point(300, 100);
        entry.farmAnchorMapId = map.getId();
        entry.moveTarget = new Point(300, 100);
        entry.moveTargetPrecise = true;

        BotManager.getInstance().issueFollowOwner(entry);

        assertNull(entry.farmAnchor);
        assertEquals(-1, entry.farmAnchorMapId);
        assertNull(entry.moveTarget);
        assertFalse(entry.moveTargetPrecise);
        assertTrue(entry.following);
    }

    @Test
    void shouldKeepTenMinutePotShareBackoffSeparateForHpAndMp() throws Exception {
        BotManager manager = BotManager.getInstance();
        MapleMap map = mock(MapleMap.class);
        Character owner = mock(Character.class);
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, owner, null);

        when(owner.getId()).thenReturn(77);
        when(owner.getName()).thenReturn("Owner");
        when(bot.getId()).thenReturn(88);
        when(bot.getTrade()).thenReturn(null);
        when(bot.getMap()).thenReturn(map);

        @SuppressWarnings("unchecked")
        Map<Integer, List<BotEntry>> bots = (Map<Integer, List<BotEntry>>) field(BotManager.class, "bots").get(manager);
        @SuppressWarnings("unchecked")
        Map<Integer, Long> sharedCooldown = (Map<Integer, Long>) field(BotPotionManager.class, "potShareCooldownUntil").get(null);
        @SuppressWarnings("unchecked")
        Map<Integer, Long> hpBackoff = (Map<Integer, Long>) field(BotPotionManager.class, "potShareHpBackoffUntil").get(null);
        @SuppressWarnings("unchecked")
        Map<Integer, Long> mpBackoff = (Map<Integer, Long>) field(BotPotionManager.class, "potShareMpBackoffUntil").get(null);

        bots.put(owner.getId(), List.of(entry));
        sharedCooldown.remove(owner.getId());
        hpBackoff.remove(owner.getId());
        mpBackoff.remove(owner.getId());

        Method requestPotShare = BotPotionManager.class.getDeclaredMethod("requestPotShare", BotEntry.class, Character.class, boolean.class);
        requestPotShare.setAccessible(true);
        try {
            assertTrue((Boolean) requestPotShare.invoke(null, entry, bot, false),
                    "first MP request should broadcast and install MP-only long backoff when no donor exists");
            assertTrue(mpBackoff.get(owner.getId()) > System.currentTimeMillis());
            assertFalse(hpBackoff.containsKey(owner.getId()));

            sharedCooldown.put(owner.getId(), 0L);

            assertTrue((Boolean) requestPotShare.invoke(null, entry, bot, true),
                    "HP request should still be allowed after shared 30 s cooldown even if MP is under 10 min backoff");
            assertTrue(hpBackoff.get(owner.getId()) > System.currentTimeMillis());

            sharedCooldown.put(owner.getId(), 0L);
            assertFalse((Boolean) requestPotShare.invoke(null, entry, bot, false),
                    "MP request should remain blocked by its own 10 min backoff");
        } finally {
            bots.remove(owner.getId());
            sharedCooldown.remove(owner.getId());
            hpBackoff.remove(owner.getId());
            mpBackoff.remove(owner.getId());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldLetOwnerPotRequestsBypassShareCooldowns() throws Exception {
        BotManager manager = BotManager.getInstance();
        Character owner = mock(Character.class);
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, owner, null);

        when(owner.getId()).thenReturn(79);
        when(owner.getTrade()).thenReturn(null);

        Map<Integer, List<BotEntry>> bots = (Map<Integer, List<BotEntry>>) field(BotManager.class, "bots").get(manager);
        Map<Integer, Long> sharedCooldown = (Map<Integer, Long>) field(BotPotionManager.class, "potShareCooldownUntil").get(null);
        Map<Integer, Long> hpBackoff = (Map<Integer, Long>) field(BotPotionManager.class, "potShareHpBackoffUntil").get(null);

        bots.put(owner.getId(), List.of());
        sharedCooldown.put(owner.getId(), Long.MAX_VALUE);
        hpBackoff.put(owner.getId(), Long.MAX_VALUE);
        try {
            assertEquals(BotPotionManager.OwnerPotShareResult.NO_DONOR,
                    BotPotionManager.offerPotShareToOwner(entry, true),
                    "manual owner requests should still attempt donor lookup while automatic share cooldowns are active");
        } finally {
            bots.remove(owner.getId());
            sharedCooldown.remove(owner.getId());
            hpBackoff.remove(owner.getId());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldLetOwnerAmmoRequestsBypassShareCooldowns() throws Exception {
        BotManager manager = BotManager.getInstance();
        Character owner = mock(Character.class);
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, owner, null);

        when(owner.getId()).thenReturn(80);
        when(owner.getMapId()).thenReturn(1000);
        when(owner.getTrade()).thenReturn(null);

        Map<Integer, List<BotEntry>> bots = (Map<Integer, List<BotEntry>>) field(BotManager.class, "bots").get(manager);
        Map<Integer, Long> sharedCooldown = (Map<Integer, Long>) field(BotAmmoManager.class, "ammoShareCooldownUntil").get(null);
        Map<String, Long> backoff = (Map<String, Long>) field(BotAmmoManager.class, "ammoShareBackoffUntil").get(null);
        String backoffKey = owner.getId() + ":" + WeaponType.BOW.name();

        bots.put(owner.getId(), List.of());
        sharedCooldown.put(owner.getId(), Long.MAX_VALUE);
        backoff.put(backoffKey, Long.MAX_VALUE);
        try {
            assertEquals(BotAmmoManager.OwnerAmmoShareResult.NO_DONOR,
                    BotAmmoManager.offerAmmoShareToOwner(entry, WeaponType.BOW),
                    "manual owner ammo requests should still attempt donor lookup while automatic share cooldowns are active");
        } finally {
            bots.remove(owner.getId());
            sharedCooldown.remove(owner.getId());
            backoff.remove(backoffKey);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldLetPlayerAskedBotPotRequestBypassShareCooldowns() throws Exception {
        BotManager manager = BotManager.getInstance();
        MapleMap map = mock(MapleMap.class);
        Character owner = mock(Character.class);
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, owner, null);

        when(owner.getId()).thenReturn(81);
        when(owner.getName()).thenReturn("Owner");
        when(bot.getId()).thenReturn(82);
        when(bot.getTrade()).thenReturn(null);
        when(bot.getMap()).thenReturn(map);

        Map<Integer, List<BotEntry>> bots = (Map<Integer, List<BotEntry>>) field(BotManager.class, "bots").get(manager);
        Map<Integer, Long> sharedCooldown = (Map<Integer, Long>) field(BotPotionManager.class, "potShareCooldownUntil").get(null);
        Map<Integer, Long> hpBackoff = (Map<Integer, Long>) field(BotPotionManager.class, "potShareHpBackoffUntil").get(null);

        bots.put(owner.getId(), List.of(entry));
        sharedCooldown.put(owner.getId(), Long.MAX_VALUE);
        hpBackoff.put(owner.getId(), Long.MAX_VALUE);

        try {
            assertTrue(BotPotionManager.requestPotShare(entry, bot, true, true),
                    "player-asked bot supply checks should bypass automatic cooldown/backoff guards");
            assertEquals(Long.MAX_VALUE, sharedCooldown.get(owner.getId()));
            assertEquals(Long.MAX_VALUE, hpBackoff.get(owner.getId()));
        } finally {
            bots.remove(owner.getId());
            sharedCooldown.remove(owner.getId());
            hpBackoff.remove(owner.getId());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldLetPlayerAskedBotAmmoRequestBypassShareCooldowns() throws Exception {
        BotManager manager = BotManager.getInstance();
        MapleMap map = mock(MapleMap.class);
        Character owner = mock(Character.class);
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, owner, null);

        when(owner.getId()).thenReturn(83);
        when(bot.getTrade()).thenReturn(null);
        when(bot.getMap()).thenReturn(map);

        Map<Integer, List<BotEntry>> bots = (Map<Integer, List<BotEntry>>) field(BotManager.class, "bots").get(manager);
        Map<Integer, Long> sharedCooldown = (Map<Integer, Long>) field(BotAmmoManager.class, "ammoShareCooldownUntil").get(null);
        Map<String, Long> backoff = (Map<String, Long>) field(BotAmmoManager.class, "ammoShareBackoffUntil").get(null);
        String backoffKey = owner.getId() + ":" + WeaponType.BOW.name();

        bots.put(owner.getId(), List.of(entry));
        sharedCooldown.put(owner.getId(), Long.MAX_VALUE);
        backoff.put(backoffKey, Long.MAX_VALUE);

        try {
            assertTrue(BotAmmoManager.requestAmmoShare(entry, bot, WeaponType.BOW, 0, true),
                    "player-asked bot supply checks should bypass automatic cooldown/backoff guards");
            assertEquals(Long.MAX_VALUE, sharedCooldown.get(owner.getId()));
            assertEquals(Long.MAX_VALUE, backoff.get(backoffKey));
        } finally {
            bots.remove(owner.getId());
            sharedCooldown.remove(owner.getId());
            backoff.remove(backoffKey);
        }
    }

    @Test
    void shouldPreferNonAmmoUsersWhenSharingArrows() throws Exception {
        BotManager manager = BotManager.getInstance();
        Character owner = mock(Character.class);
        Character needy = ammoBot(10, 1000, 100);
        Character nonBow800 = ammoBot(11, 1000, 800);
        Character nonBow600 = ammoBot(12, 1000, 600);
        Character bow3000 = ammoBot(13, 1000, 3000);
        Character ignored499 = ammoBot(14, 1000, 499);

        when(owner.getId()).thenReturn(77);

        BotEntry needyEntry = new BotEntry(needy, owner, null);
        BotEntry nonBow800Entry = new BotEntry(nonBow800, owner, null);
        BotEntry nonBow600Entry = new BotEntry(nonBow600, owner, null);
        BotEntry bow3000Entry = new BotEntry(bow3000, owner, null);
        BotEntry ignored499Entry = new BotEntry(ignored499, owner, null);

        @SuppressWarnings("unchecked")
        Map<Integer, List<BotEntry>> bots = (Map<Integer, List<BotEntry>>) field(BotManager.class, "bots").get(manager);
        bots.put(owner.getId(), List.of(needyEntry, nonBow600Entry, bow3000Entry, ignored499Entry, nonBow800Entry));

        try (MockedStatic<BotAttackExecutionProvider> attacks = mockStatic(BotAttackExecutionProvider.class, invocation -> {
            Character character = invocation.getArgument(0);
            if (character == needy || character == bow3000) {
                return WeaponType.BOW;
            }
            return WeaponType.SWORD1H;
        })) {

            BotAmmoManager.AmmoDonorPlan plan = BotAmmoManager.selectAmmoDonor(needyEntry, needy, WeaponType.BOW);

            assertNotNull(plan);
            assertEquals(nonBow800Entry, plan.entry());
            assertEquals(800, plan.donationQty());
            assertFalse(plan.donorNeedsSameAmmo());
        } finally {
            bots.remove(owner.getId());
        }
    }

    @Test
    void shouldOnlyDonateHalfSurplusFromSameAmmoUser() throws Exception {
        BotManager manager = BotManager.getInstance();
        Character owner = mock(Character.class);
        Character needy = ammoBot(10, 1000, 100);
        Character bow3000 = ammoBot(13, 1000, 3000);

        when(owner.getId()).thenReturn(78);

        BotEntry needyEntry = new BotEntry(needy, owner, null);
        BotEntry bow3000Entry = new BotEntry(bow3000, owner, null);

        @SuppressWarnings("unchecked")
        Map<Integer, List<BotEntry>> bots = (Map<Integer, List<BotEntry>>) field(BotManager.class, "bots").get(manager);
        bots.put(owner.getId(), List.of(needyEntry, bow3000Entry));

        try (MockedStatic<BotAttackExecutionProvider> attacks = mockStatic(BotAttackExecutionProvider.class,
                invocation -> WeaponType.BOW)) {
            BotAmmoManager.AmmoDonorPlan plan = BotAmmoManager.selectAmmoDonor(needyEntry, needy, WeaponType.BOW);

            assertNotNull(plan);
            assertEquals(bow3000Entry, plan.entry());
            assertTrue(plan.donorNeedsSameAmmo());
            assertEquals(1250, plan.donationQty());
        } finally {
            bots.remove(owner.getId());
        }
    }

    @Test
    void shouldSplitSingleAmmoStackByShareBudget() {
        BotEntry entry = new BotEntry(mock(Character.class), mock(Character.class), null);
        entry.pendingPotShareBudget = 2250;

        short tradeQty = BotInventoryManager.capTradeQuantityByShareBudget(entry, (short) 5000);

        assertEquals(2250, tradeQty);
        assertEquals(0, entry.pendingPotShareBudget);
    }

    @Test
    void shouldRestoreTradeWindowCopyAfterTemporarilyUnequippedItemIsAddedToTrade() {
        BotEntry entry = new BotEntry(mock(Character.class), mock(Character.class), null);
        Item equippedItem = new Item(1040000, (short) 1, (short) 1);
        Item tradeWindowCopy = equippedItem.copy();

        entry.pendingTradeRestoreSlots.put(equippedItem, (short) -5);

        BotInventoryManager.rememberTradeWindowItemForRestore(entry, equippedItem, tradeWindowCopy);

        assertFalse(entry.pendingTradeRestoreSlots.containsKey(equippedItem));
        assertEquals((short) -5, entry.pendingTradeRestoreSlots.get(tradeWindowCopy));
    }

    @Test
    void shouldPrioritizeEtcTradeItemsRecipientAlreadyHasBeforeItemIdOrder() {
        Character recipient = mock(Character.class);
        Inventory etcInventory = new Inventory(recipient, InventoryType.ETC, (byte) 24);

        etcInventory.addItem(new Item(4000001, (short) 1, (short) 20));
        when(recipient.getInventory(InventoryType.ETC)).thenReturn(etcInventory);

        Item item4000002 = new Item(4000002, (short) 3, (short) 10);
        Item item4000000 = new Item(4000000, (short) 1, (short) 10);
        Item item4000001 = new Item(4000001, (short) 2, (short) 10);

        List<Item> ordered = BotInventoryManager.prioritizeEtcTradeItems(
                List.of(item4000002, item4000000, item4000001), recipient);

        assertEquals(List.of(item4000001, item4000000, item4000002), ordered);
    }

    @Test
    void shouldMatchNaturalSupplyRequestPhrases() {
        assertTrue(BotChatManager.isNeedPotCommand("nned pot"));
        assertTrue(BotChatManager.isNeedPotCommand("need some pots"));
        assertTrue(BotChatManager.isNeedPotCommand("anybody got pot"));
        assertTrue(BotChatManager.isNeedPotCommand("low on pots"));
        assertTrue(BotChatManager.isNeedHpPotCommand("anyone have hp pots"));
        assertTrue(BotChatManager.isNeedMpPotCommand("running low on mana potions"));
        assertTrue(BotChatManager.isNeedAmmoCommand("anybody got arrows"));
        assertTrue(BotChatManager.isNeedAmmoCommand("low on ammo"));
    }

    @Test
    void shouldNormalizeNamedItemCommandsAndQueries() {
        assertEquals("warrior potion", BotInventoryManager.normalizeItemQuery("Warrior Potions?!"));
        assertEquals("name:warrior potion", BotChatManager.matchChoiceCategory("drop warrior potions?"));
        assertEquals("name:warrior potion", BotChatManager.matchTradeCategory("trade me warrior potions"));
        assertEquals("warrior potion", BotChatManager.matchItemQuery("anybody got warrior potions?"));
    }

    @Test
    void shouldPrioritizeRecipientDuplicatesWithinUseTradeBuckets() {
        Character owner = mock(Character.class);
        Inventory ownerUse = new Inventory(owner, InventoryType.USE, (byte) 24);
        ownerUse.addItem(Items.itemWithQuantity(2030000, 1));
        ownerUse.addItem(Items.itemWithQuantity(2040000, 1));
        when(owner.getInventory(InventoryType.USE)).thenReturn(ownerUse);

        List<Item> ordered = BotInventoryManager.prioritizeTradeUseItems(
                List.of(
                        Items.itemWithQuantity(2030001, 1),
                        Items.itemWithQuantity(2030000, 1)),
                List.of(
                        Items.itemWithQuantity(2040000, 1)),
                List.of(
                        Items.itemWithQuantity(2060000, 1)),
                owner);

        assertEquals(List.of(2030000, 2030001, 2040000, 2060000),
                ordered.stream().map(Item::getItemId).toList());
    }

    @Test
    void shouldRankPotionAndAmmoLastWithinUseTradeBuckets() {
        Character owner = mock(Character.class);
        Inventory ownerUse = new Inventory(owner, InventoryType.USE, (byte) 24);
        ownerUse.addItem(Items.itemWithQuantity(2000000, 1)); // potion owner already has
        ownerUse.addItem(Items.itemWithQuantity(2040000, 1)); // scroll owner already has
        when(owner.getInventory(InventoryType.USE)).thenReturn(ownerUse);

        List<Item> ordered = BotInventoryManager.prioritizeTradeUseItems(
                List.of(Items.itemWithQuantity(2030000, 1)),
                List.of(
                        Items.itemWithQuantity(2040001, 1),
                        Items.itemWithQuantity(2040000, 1)),
                List.of(
                        Items.itemWithQuantity(2000001, 1),
                        Items.itemWithQuantity(2000000, 1)),
                owner);

        assertEquals(List.of(2030000, 2040000, 2040001, 2000000, 2000001),
                ordered.stream().map(Item::getItemId).toList());
    }

    @Test
    void shouldPrioritizeScrollTradeItemsRecipientAlreadyHasBeforeItemIdOrder() {
        Character recipient = mock(Character.class);
        Inventory recipientUse = new Inventory(recipient, InventoryType.USE, (byte) 24);
        recipientUse.addItem(Items.itemWithQuantity(2040001, 1));
        when(recipient.getInventory(InventoryType.USE)).thenReturn(recipientUse);

        List<Item> ordered = BotInventoryManager.prioritizeScrollTradeItems(
                List.of(
                        Items.itemWithQuantity(2040002, 1),
                        Items.itemWithQuantity(2040000, 1),
                        Items.itemWithQuantity(2040001, 1)),
                recipient);

        assertEquals(List.of(2040001, 2040000, 2040002),
                ordered.stream().map(Item::getItemId).toList());
    }

    private static BotCombatManager.AttackPlan basicClosePlan(Monster target) {
        return new BotCombatManager.AttackPlan(
                0, 0, 1, null, List.of(target), BotCombatManager.AttackRoute.CLOSE,
                0, 0, 0, 0, 0, 0, 0, null);
    }

    private static MapleMap createEmptyTestMap(int mapId) {
        MapleMap map = new MapleMap(mapId, 0, 0, mapId, 1.0f);
        map.setFootholds(new FootholdTree(new Point(-2000, -2000), new Point(2000, 2000)));
        return map;
    }

    private static Character mockMovingBot(Point startPosition, MapleMap map) {
        Character bot = mock(Character.class);
        AtomicReference<Point> position = new AtomicReference<>(new Point(startPosition));
        AtomicInteger stance = new AtomicInteger(0);
        int mapId = map.getId();

        when(bot.getId()).thenReturn(88);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(mapId);
        when(bot.getPosition()).thenAnswer(invocation -> new Point(position.get()));
        doAnswer(invocation -> {
            position.set(new Point(invocation.getArgument(0)));
            return null;
        }).when(bot).setPosition(any(Point.class));
        when(bot.getHp()).thenReturn(100);
        when(bot.getTotalMoveSpeedStat()).thenReturn(100);
        when(bot.getTotalJumpStat()).thenReturn(100);
        when(bot.getStance()).thenAnswer(invocation -> stance.get());
        doAnswer(invocation -> {
            stance.set(invocation.getArgument(0));
            return null;
        }).when(bot).setStance(anyInt());
        return bot;
    }

    private static BotEntry botEntryNamed(String name) {
        Character bot = mock(Character.class);
        when(bot.getName()).thenReturn(name);
        return new BotEntry(bot, null, null);
    }

    private static Monster mockMob(Point position, int id) {
        Monster mob = mock(Monster.class);
        when(mob.getPosition()).thenReturn(new Point(position));
        when(mob.getId()).thenReturn(id);
        when(mob.getObjectId()).thenReturn(id);
        when(mob.isAlive()).thenReturn(true);
        return mob;
    }

    private static MapItem mockLoot(int objectId, Point position) {
        return mockLoot(objectId, position, 0, 1, 0);
    }

    private static MapItem mockLoot(int objectId, Point position, int itemId, int meso, int questId) {
        MapItem loot = mock(MapItem.class);
        when(loot.getObjectId()).thenReturn(objectId);
        when(loot.getPosition()).thenReturn(new Point(position));
        when(loot.isPickedUp()).thenReturn(false);
        when(loot.canBePickedBy(any(Character.class))).thenReturn(true);
        when(loot.getDropTime()).thenReturn(System.currentTimeMillis() - 5_000L);
        when(loot.getItemId()).thenReturn(itemId);
        when(loot.getMeso()).thenReturn(meso);
        when(loot.getQuest()).thenReturn(questId);
        return loot;
    }

    private static Character ammoBot(int id, int mapId, int arrowCount) {
        Character bot = mock(Character.class);
        Inventory use = new Inventory(bot, InventoryType.USE, (byte) 24);
        use.addItem(Items.itemWithQuantity(2060000, arrowCount));
        when(bot.getId()).thenReturn(id);
        when(bot.getMapId()).thenReturn(mapId);
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);
        when(bot.getBuffedValue(any(BuffStat.class))).thenReturn(null);
        return bot;
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Method method(Class<?> type, String name, Class<?>... parameterTypes) throws Exception {
        Method method = type.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }
}
