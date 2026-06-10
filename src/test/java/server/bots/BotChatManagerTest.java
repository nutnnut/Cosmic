package server.bots;

import client.Character;
import client.Job;
import client.inventory.Inventory;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import server.maps.FieldLimit;
import server.maps.MapleMap;

import java.util.List;
import java.util.Locale;
import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotChatManagerTest {
    @Test
    void shouldParseTradeMesosAsAllWhenNoAmountIsSpecified() {
        assertEquals("mesos", BotChatManager.matchTradeCategory("trade mesos"));
        assertEquals("mesos", BotChatManager.matchTradeCategory("trade me all your mesos"));
    }

    @Test
    void shouldParseTradeMesosWithExplicitAmounts() {
        assertEquals("mesos:1000000", BotChatManager.matchTradeCategory("trade 1m mesos"));
        assertEquals("mesos:1250000", BotChatManager.matchTradeCategory("trade 1,250,000 mesos"));
        assertEquals("mesos:1500000", BotChatManager.matchTradeCategory("trade 1.5m mesos"));
    }

    @Test
    void shouldParseAdditionalMesoTransferPhrasings() {
        assertEquals("mesos:5000000", BotChatManager.matchTradeCategory("give me 5m"));
        assertEquals("mesos:200000", BotChatManager.matchTradeCategory("gimme 200000"));
        assertEquals("mesos", BotChatManager.matchTradeCategory("trade meso"));
        assertEquals("mesos:10000000", BotChatManager.matchTradeCategory("give meso 10m"));
        assertEquals("mesos:10000000", BotChatManager.matchTradeCategory("trade 10m"));
    }

    @Test
    void shouldStillParseNamedItemTrades() {
        assertEquals("name:chaos scroll", BotChatManager.matchTradeCategory("trade chaos scroll"));
        assertEquals("name:chaos scroll", BotChatManager.matchTradeCategory("trade chaos scrolls"));
    }

    @Test
    void shouldParseViewEquipmentRequestsAsTradeCommands() {
        assertEquals("name:hat", BotChatManager.matchTradeCategory("show me your hat"));
        assertEquals("name:ring 2", BotChatManager.matchTradeCategory("let me see ur ring 2"));
        assertEquals("name:weapon", BotChatManager.matchTradeCategory("can i c yo weapon"));
    }

    @Test
    void shouldParseFollowTargetCommandsWithoutBreakingPlainFollow() {
        assertEquals("clawer", BotChatManager.matchFollowTarget("follow clawer"));
        assertEquals("Clawer", BotChatManager.matchFollowTarget("follow Clawer please"));
        assertNull(BotChatManager.matchFollowTarget("follow me"));
        assertNull(BotChatManager.matchFollowTarget("follow here"));
    }

    @Test
    void shouldOnlyMatchMovementModeCommandsAsWholeCommands() {
        assertTrue(BotChatManager.isMoveHereCommand("here"));
        assertTrue(BotChatManager.isMoveHereCommand("move here!"));
        assertFalse(BotChatManager.isMoveHereCommand("some random chat message here"));

        assertTrue(BotChatManager.isGrindCommand("farm"));
        assertTrue(BotChatManager.isGrindCommand("go grind"));
        assertFalse(BotChatManager.isGrindCommand("Im going to the farm today"));

        assertTrue(BotChatManager.isAutopilotCommand("go grind somewhere"));
        assertTrue(BotChatManager.isAutopilotCommand("autopilot"));
        assertTrue(BotChatManager.isAutopilotCommand("go solo"));
        assertFalse(BotChatManager.isAutopilotCommand("go grind"));

        assertTrue(BotChatManager.isFarmHereCommand("farm here"));
        assertTrue(BotChatManager.isFarmHereCommand("grind here please"));
        assertFalse(BotChatManager.isFarmHereCommand("Im going to farm here today"));

        assertTrue(BotChatManager.isFarmHereCommand("sentry"));
        assertTrue(BotChatManager.isFarmHereCommand("go sentry"));
        assertTrue(BotChatManager.isFarmHereCommand("sentry here"));
        assertTrue(BotChatManager.isFarmHereCommand("sentry mode"));
        assertTrue(BotChatManager.isFarmHereCommand("go sentry mode"));
        assertTrue(BotChatManager.isFarmHereCommand("camp"));
        assertTrue(BotChatManager.isFarmHereCommand("camp here"));
        assertTrue(BotChatManager.isFarmHereCommand("guard mode"));
        assertTrue(BotChatManager.isFarmHereCommand("go defend mode"));
        assertTrue(BotChatManager.isFarmHereCommand("post up"));
        assertTrue(BotChatManager.isFarmHereCommand("post up here"));
        assertTrue(BotChatManager.isFarmHereCommand("anchor here"));
        assertTrue(BotChatManager.isFarmHereCommand("anchor"));
        assertFalse(BotChatManager.isFarmHereCommand("Im going to camp today"));
        assertFalse(BotChatManager.isFarmHereCommand("setting up camp"));
    }

    @Test
    void shouldParseNamedItemGiveRequests() {
        assertEquals("name:flaming feather", BotChatManager.matchChoiceCategory("give me flaming feather"));
        assertEquals("name:flaming feather", BotChatManager.matchChoiceCategory("give flaming feather"));
    }

    @Test
    void shouldParseRecommendedGearTrades() {
        assertEquals("recommended", BotChatManager.matchTradeCategory("trade recommended gear"));
        assertEquals("recommended", BotChatManager.matchTradeCategory("trade me upgrades"));
        assertEquals("recommended", BotChatManager.matchTradeCategory("trade better equipment"));
    }

    @Test
    void shouldParseAmmoTrades() {
        assertEquals("ammo", BotChatManager.matchTradeCategory("trade ammo"));
        assertEquals("ammo", BotChatManager.matchTradeCategory("trade me your arrows"));
        assertEquals("ammo", BotChatManager.matchTradeCategory("trade bullets"));
    }

    @Test
    void shouldParseReservedEquipTradesWithOptionalPage() {
        assertEquals("equips:reserved:1", BotChatManager.matchTradeCategory("trade reserve"));
        assertEquals("equips:reserved:1", BotChatManager.matchTradeCategory("trade reserved"));
        assertEquals("equips:reserved:3", BotChatManager.matchTradeCategory("trade reserve 3"));
        assertEquals("equips:reserved:12", BotChatManager.matchTradeCategory("trade me your reserve 12"));
    }

    @Test
    void shouldParseTrashGearTrades() {
        assertEquals("trash", BotChatManager.matchTradeCategory("trade trash"));
        assertEquals("trash", BotChatManager.matchTradeCategory("trade my trash"));
        assertEquals("trash", BotChatManager.matchTradeCategory("trade junk"));
        assertEquals("trash", BotChatManager.matchTradeCategory("got trash?"));
        assertEquals("trash", BotChatManager.matchTradeCategory("have any junk?"));
        assertNull(BotChatManager.matchItemQuery("got trash?"));
        assertEquals("trash", BotChatManager.matchTradeCategory("show me your junk"));
        assertEquals("trash", BotChatManager.matchTradeCategory("show your junk"));
        assertEquals("trash", BotChatManager.matchTradeCategory("show ur junk"));
    }

    @Test
    void shouldNotParseSellTrashAsTradeTrash() {
        assertNull(BotChatManager.matchTradeCategory("sell trash"));
        assertNull(BotChatManager.matchTradeCategory("sell junk"));
    }

    @Test
    void shouldMatchMesoQueries() {
        assertTrue(BotChatManager.isMesoQuery("meso?"));
        assertTrue(BotChatManager.isMesoQuery("mesos?"));
        assertTrue(BotChatManager.isMesoQuery("cash?"));
        assertTrue(BotChatManager.isMesoQuery("how much cash do you have"));
        assertTrue(BotChatManager.isMesoQuery("your mesos"));
        assertFalse(BotChatManager.isMesoQuery("trade mesos"));
    }

    @Test
    void shouldMatchMovementStatQueries() {
        assertTrue(BotChatManager.isMovementStatsQuery("speed?"));
        assertTrue(BotChatManager.isMovementStatsQuery("jump?"));
        assertTrue(BotChatManager.isMovementStatsQuery("movement stats"));
        assertTrue(BotChatManager.isMovementStatsQuery("how fast are you"));
        assertFalse(BotChatManager.isMovementStatsQuery("trade mesos"));
    }

    @Test
    void shouldTriggerGreetingFidgetHalfTheTimeWhileFollowing() {
        BotEntry entry = new BotEntry(null, null, null);
        entry.following = true;

        assertTrue(BotFidgetManager.maybeStartGreetingFidget(entry, 0));
        assertFalse(entry.fidgetMode == BotFidgetMode.NONE);
        assertEquals(BotFidgetTrigger.SOCIAL, entry.fidgetTrigger);

        BotFidgetManager.clear(entry);

        assertFalse(BotFidgetManager.maybeStartGreetingFidget(entry, 99));
        assertEquals(BotFidgetMode.NONE, entry.fidgetMode);
    }

    @Test
    void shouldTriggerFidgetCommandWithoutGreetingRoll() {
        BotEntry entry = new BotEntry(null, null, null);
        entry.following = true;

        assertTrue(BotChatManager.isFidgetCommand("fidget"));
        assertTrue(BotChatManager.isFidgetCommand("fidget!"));
        assertFalse(BotChatManager.isFidgetCommand("please fidget"));
        for (int i = 0; i < 100; i++) {
            assertTrue(Set.of(2, 3, 5, 6, 7).contains(BotChatManager.randomFidgetExpression()));
        }

        assertTrue(BotFidgetManager.maybeStartSocialFidget(entry));
        assertFalse(entry.fidgetMode == BotFidgetMode.NONE);
        assertEquals(BotFidgetTrigger.SOCIAL, entry.fidgetTrigger);
    }

    @Test
    void shouldTrackPerScrollerStreaksAndDisableHundredPercentStreakChats() {
        BotEntry entry = new BotEntry(null, null, null);
        long start = 2_000_000L;
        int alice = 101;
        int bob = 202;

        assertEquals(1, BotScrollReactionManager.updateReactionStreak(entry, alice, true, start));
        assertEquals(2, BotScrollReactionManager.updateReactionStreak(entry, alice, true, start + 30_000L));
        assertEquals(3, BotScrollReactionManager.updateReactionStreak(entry, alice, true, start + 60_000L));

        assertEquals(1, BotScrollReactionManager.updateReactionStreak(entry, bob, true, start + 10_000L));
        assertEquals(1, BotScrollReactionManager.updateReactionStreak(entry, alice, false, start + 90_000L));
        assertEquals(2, BotScrollReactionManager.updateReactionStreak(entry, alice, false, start + 120_000L));
        assertEquals(1, BotScrollReactionManager.updateReactionStreak(
                entry, alice, false, start + 120_000L + BotScrollReactionManager.streakWindowMs() + 1L));
    }

    @Test
    void shouldNotTriggerGreetingFidgetWhileOwnerIsAfk() {
        BotEntry entry = new BotEntry(null, null, null);
        entry.following = true;
        entry.ownerWasAfk = true;

        assertFalse(BotFidgetManager.maybeStartGreetingFidget(entry, 0));
        assertEquals(BotFidgetMode.NONE, entry.fidgetMode);
    }

    @Test
    void shouldFormatCompactMesos() {
        assertEquals("999", BotChatManager.formatCompactMesos(999));
        assertEquals("6k", BotChatManager.formatCompactMesos(6_000));
        assertEquals("3.5k", BotChatManager.formatCompactMesos(3_500));
        assertEquals("2.1m", BotChatManager.formatCompactMesos(2_100_000));
    }

    @Test
    void shouldBuildMesoReportUsingCompactAmounts() {
        assertTrue(BotChatManager.buildMesoReport(6_000).contains("6k"));
        assertTrue(BotChatManager.buildMesoReport(3_500).contains("3.5k"));
        assertTrue(BotChatManager.buildMesoReport(2_100_000).contains("2.1m"));
    }

    @Test
    void shouldShowBuffDebugStateWithEnabledAndMode() {
        BotEntry entry = new BotEntry(null, null, null);

        entry.buffConsumablesEnabled = true;
        entry.buffCheapMode = true;
        assertEquals("buff on(cheap)", BotBuffManager.formatDebugState(entry));

        entry.buffConsumablesEnabled = false;
        entry.buffCheapMode = false;
        assertEquals("buff off(best)", BotBuffManager.formatDebugState(entry));
    }

    @Test
    void shouldParseProactiveOfferToggleCommands() {
        assertTrue(BotChatManager.isProactiveOffersOnCommand("proactive offers on"));
        assertTrue(BotChatManager.isProactiveOffersOnCommand("future upgrades on"));
        assertTrue(BotChatManager.isProactiveOffersOffCommand("proactive offers off"));
        assertTrue(BotChatManager.isProactiveOffersOffCommand("offers future off"));
        assertFalse(BotChatManager.isProactiveOffersOnCommand("trade recommended gear"));
    }

    @Test
    void shouldParseOwnerGearNeedQuestionsAsUpgradeRequests() {
        assertTrue(BotChatManager.isRequestUpgradeCommand("do you need any gear from me?"));
        assertTrue(BotChatManager.isRequestUpgradeCommand("need gear from me"));
        assertTrue(BotChatManager.isRequestUpgradeCommand("do you need equipment"));
        assertFalse(BotChatManager.isRequestUpgradeCommand("trade recommended gear"));
    }

    @Test
    void shouldBuildMovementStatsReportUsingGameStatsAndDerivedPhysics() {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(bot.getMap()).thenReturn(map);
        when(bot.getTotalMoveSpeedStat()).thenReturn(120);
        when(bot.getTotalJumpStat()).thenReturn(110);
        BotMovementProfile profile = BotMovementProfile.fromCharacter(bot);

        List<String> report = BotChatManager.buildMovementStatsReport(bot);

        assertEquals(List.of(
                "speed 120% jump 110%",
                String.format(Locale.ROOT, "walk %.1f px/s, %d px/tick, climb %d, hforce %.1f",
                        profile.walkVelocityPxs(),
                        BotMovementManager.walkStep(map, profile),
                        BotPhysicsEngine.climbStepPerTick(),
                        profile.hForcePxs()),
                String.format(Locale.ROOT, "jump %.1f, rope %.1f, max %.1f px, reach %d/%d px",
                        BotPhysicsEngine.jumpForcePerTick(profile),
                        BotPhysicsEngine.ropeJumpForcePerTick(profile),
                        BotPhysicsEngine.calculateMaxJumpHeight(profile),
                        BotPhysicsEngine.maxJumpHorizontalTravel(map, profile),
                        BotPhysicsEngine.maxRopeJumpHorizontalTravel(map, profile))
        ), report);
    }

    @Test
    void shouldReportForcedMovementStatsOnMovementSkillLimitMaps() {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(map.getFieldLimit()).thenReturn((int) FieldLimit.MOVEMENTSKILLS.getValue());
        when(bot.getMap()).thenReturn(map);
        when(bot.getTotalMoveSpeedStat()).thenReturn(140);
        when(bot.getTotalJumpStat()).thenReturn(125);

        List<String> report = BotChatManager.buildMovementStatsReport(bot);

        assertEquals("speed 100% jump 100% (map forced; raw 140%/125%)", report.getFirst());
    }

    @Test
    void shouldBuildPhysicalRangeReportFromEffectiveTotals() {
        Character bot = mock(Character.class);
        Inventory equipped = mock(Inventory.class);
        when(bot.getJob()).thenReturn(Job.FIGHTER);
        when(bot.getLevel()).thenReturn(48);
        when(bot.getTotalWatk()).thenReturn(20);
        when(bot.getTotalDex()).thenReturn(100);
        when(bot.getTotalLuk()).thenReturn(40);
        when(bot.getInventory(client.inventory.InventoryType.EQUIPPED)).thenReturn(equipped);
        when(equipped.getItem((short) -11)).thenReturn(null);
        when(equipped.iterator()).thenReturn(List.<client.inventory.Item>of().iterator());
        when(bot.calculateMinBaseDamage(20, 0.1d)).thenReturn(50);
        when(bot.calculateMaxBaseDamage(20)).thenReturn(99);

        String report = BotChatManager.buildRangeReport(bot,
                new BotEquipManager.MapDamageProfile(100, 40, 48));

        assertEquals("my dmg is 50-99, watk 20, acc 100 | hit 47% vs hardest mob (avd 40)", report);
    }

    @Test
    void shouldBuildMageRangeReportFromEffectiveMagicTotals() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.MAGICIAN);
        when(bot.getLevel()).thenReturn(50);
        when(bot.getTotalMagic()).thenReturn(200);
        when(bot.getTotalInt()).thenReturn(100);
        when(bot.getTotalLuk()).thenReturn(50);

        String report = BotChatManager.buildRangeReport(bot,
                new BotEquipManager.MapDamageProfile(100, 30, 50));

        assertEquals("my dmg is 3-9, matk 200, magic acc 75 | hit 26% vs hardest mob (avd 30)", report);
    }

    @Test
    void shouldBuildOwnerLootOfferPrompt() {
        String prompt = BotOfferManager.buildLootOfferPrompt("Owner", "Blue Moon", true);
        assertTrue(Set.of(
                "Owner, I have Blue Moon, you want?",
                "Owner, picked up Blue Moon, want it?",
                "Owner, I got Blue Moon if you want it",
                "Owner, want Blue Moon?",
                "Owner, I can trade you Blue Moon",
                "Owner, grabbed Blue Moon for you if you want it").contains(prompt));
    }

    @Test
    void shouldBuildPartyLootOfferPrompt() {
        String prompt = BotOfferManager.buildLootOfferPrompt("Alice", "Blue Moon", false);
        assertTrue(Set.of(
                "Alice, I have Blue Moon, you want?",
                "Alice, picked up Blue Moon, want it?",
                "Alice, I got Blue Moon if you want it",
                "Alice, want Blue Moon?",
                "Alice, I can trade you Blue Moon",
                "Alice, grabbed Blue Moon for you if you want it").contains(prompt));
    }

    @Test
    void shouldMatchRespecCommands() {
        assertTrue(BotChatManager.isRespecCommand("respec"));
        assertTrue(BotChatManager.isRespecCommand("reset skills"));
        assertTrue(BotChatManager.isRespecCommand("rebuild sp"));
    }

    @Test
    void shouldMatchApRespecCommands() {
        assertTrue(BotChatManager.isApRespecCommand("respec ap"));
        assertTrue(BotChatManager.isApRespecCommand("reset ap"));
        assertTrue(BotChatManager.isApRespecCommand("rebuild ap"));
        assertFalse(BotChatManager.isApRespecCommand("respec"));
    }

    @Test
    void shouldMarkQueuedRepliesAsOwnerDirected() {
        BotEntry entry = new BotEntry(null, null, null);
        entry.msgSending = true;

        BotChatManager.queueBotReply(entry, "owner reply");
        BotChatManager.queueBotSay(entry, "party chatter");

        BotChatManager.QueuedMessage first = entry.msgQueue.poll();
        BotChatManager.QueuedMessage second = entry.msgQueue.poll();
        assertEquals("owner reply", first.text);
        assertTrue(first.ownerDirected);
        assertEquals("party chatter", second.text);
        assertFalse(second.ownerDirected);
    }

    @Test
    void shouldQueueHelpAsOwnerDirectedReply() throws Exception {
        BotEntry entry = new BotEntry(null, null, null);
        entry.msgSending = true;

        Method reportHelp = BotChatManager.class.getDeclaredMethod("reportHelp", BotEntry.class);
        reportHelp.setAccessible(true);
        reportHelp.invoke(null, entry);

        assertEquals(5, entry.msgQueue.size());
        for (BotChatManager.QueuedMessage message : entry.msgQueue) {
            assertTrue(message.ownerDirected);
        }
    }

    @Test
    void shouldClearPendingOfferStateForOwnerAsk() {
        BotEntry entry = new BotEntry(null, null, null);
        entry.pendingDropCategory = "equips";
        entry.pendingLootOfferItem = new Item(1002000, (short) 1, (short) 1);
        entry.pendingLootOfferRecipientId = 123;
        entry.pendingLootOfferExpiresAt = Long.MAX_VALUE;
        entry.pendingLootOfferBotRequesting = true;
        entry.pendingGearPromptAt = Long.MAX_VALUE;

        BotOfferManager.clearPendingOfferForOwnerAsk(entry);

        assertNull(entry.pendingDropCategory);
        assertNull(entry.pendingLootOfferItem);
        assertEquals(0, entry.pendingLootOfferRecipientId);
        assertEquals(0L, entry.pendingLootOfferExpiresAt);
        assertFalse(entry.pendingLootOfferBotRequesting);
        assertEquals(0L, entry.pendingGearPromptAt);
    }
}
