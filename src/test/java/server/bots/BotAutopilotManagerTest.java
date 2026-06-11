package server.bots;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.bots.BotGrindPlanner.MobCandidate;
import server.bots.BotGrindPlanner.Recommendation;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class BotAutopilotManagerTest {

    private static final int TOWN = 100000000;
    private static final int HUNTING_GROUND = 100040000;

    private static Recommendation expRec(int mapId, String mapName) {
        return expRec(mapId, mapName, 27_000);
    }

    private static Recommendation expRec(int mapId, String mapName, double score) {
        MobCandidate pick = new MobCandidate(130101, "Orange Mushroom", 8, 15, 1.2,
                mapId, mapName, 12, List.of());
        return new Recommendation(pick, 1800, 27_000, false, 0.1, null, 0, score);
    }

    private static Recommendation gearRec(int mapId, String mapName, String itemName, double score) {
        MobCandidate pick = new MobCandidate(3210100, "Drake", 35, 86, 3.0,
                mapId, mapName, 10, List.of());
        return new Recommendation(pick, 900, 77_000, true, 1.0,
                new BotGrindPlanner.GearProspect(1402000, itemName, 0.01, 50, 0.35), 9, score);
    }

    private record Fixture(BotEntry entry, Character bot) {}

    private static Fixture fixture(int mapId) {
        return fixture(mapId, null);
    }

    private static Fixture fixture(int mapId, Character owner) {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(mapId);
        BotEntry entry = new BotEntry(bot, owner, null);
        entry.lastMapId = mapId;
        return new Fixture(entry, bot);
    }

    private static Character onlineOwner() {
        Character owner = mock(Character.class);
        when(owner.isLoggedinWorld()).thenReturn(true);
        return owner;
    }

    /** Swaps the advisor/party/farm/reply/runner seams (runner = synchronous); restore via close(). */
    private static final class Seams implements AutoCloseable {
        final List<String> replies = new ArrayList<>();
        final List<Boolean> advisorFerryFlags = new ArrayList<>();
        private final BotAutopilotManager.Advisor previousAdvisor = BotAutopilotManager.advisor;
        private final BotAutopilotManager.PartyDecider previousPartyDecider = BotAutopilotManager.partyDecider;
        private final BotAutopilotManager.FarmAdvisor previousFarmAdvisor = BotAutopilotManager.farmAdvisor;
        private final java.util.function.BiConsumer<BotEntry, String> previousReply = BotAutopilotManager.reply;
        private final BotAutopilotManager.DecisionRunner previousRunner = BotAutopilotManager.decisionRunner;

        Seams(Recommendation recommendation) {
            this(recommendation, recommendation);
        }

        /** Separate picks for the ferry-off and ferry-on advisor passes (ferry teaser tests). */
        Seams(Recommendation localRec, Recommendation ferryRec) {
            BotAutopilotManager.advisor = (entry, bot, fromMapId, maxHops, withFerry) -> {
                advisorFerryFlags.add(withFerry);
                return withFerry ? ferryRec : localRec;
            };
            BotAutopilotManager.partyDecider = members -> null;
            BotAutopilotManager.farmAdvisor = (entry, bot, itemId, fromMapId, maxHops, withFerry) -> null;
            BotAutopilotManager.reply = (entry, text) -> replies.add(text);
            BotAutopilotManager.decisionRunner = (compute, apply) -> apply.accept(compute.get());
        }

        @Override
        public void close() {
            BotAutopilotManager.advisor = previousAdvisor;
            BotAutopilotManager.partyDecider = previousPartyDecider;
            BotAutopilotManager.farmAdvisor = previousFarmAdvisor;
            BotAutopilotManager.reply = previousReply;
            BotAutopilotManager.decisionRunner = previousRunner;
        }
    }

    @Test
    void shouldStartGrindingModeAndAnnouncePickedMap() {
        Fixture f = fixture(TOWN);

        try (Seams seams = new Seams(expRec(HUNTING_GROUND, "Henesys Hunting Ground I"))) {
            BotAutopilotManager.start(f.entry(), f.bot());

            assertEquals(HUNTING_GROUND, f.entry().autopilotMapId);
            assertTrue(f.entry().grinding);
            assertFalse(f.entry().following);
            assertTrue(f.entry().autopilotNextDecisionAtMs > System.currentTimeMillis());
            assertEquals("Henesys Hunting Ground I", f.entry().autopilotDestinationName);
            assertTrue(f.entry().autopilotObjectiveSummary.contains("Orange Mushroom"));
            assertFalse(f.entry().autopilotArrivalAnnounced);
            assertEquals(1, seams.replies.size());
            assertTrue(seams.replies.get(0).contains("Henesys Hunting Ground I"), seams.replies.get(0));
            assertTrue(seams.replies.get(0).contains("to grind Orange Mushroom"), seams.replies.get(0));
            // Plain words only: no exp/hr or any other rate numbers in chat.
            assertFalse(seams.replies.get(0).contains("/hr"), seams.replies.get(0));
            assertFalse(seams.replies.get(0).contains("exp"), seams.replies.get(0));
        }
    }

    @Test
    void shouldAnnounceGearObjectiveAsItemFromMobWithoutNumbers() {
        Fixture f = fixture(TOWN);

        try (Seams seams = new Seams(gearRec(HUNTING_GROUND, "Drake Cave", "sword", 9_000))) {
            BotAutopilotManager.start(f.entry(), f.bot());

            assertEquals("farm sword from Drake", f.entry().autopilotObjectiveSummary);
            assertEquals(1, seams.replies.size());
            assertTrue(seams.replies.get(0).contains("farm sword from Drake"), seams.replies.get(0));
            assertFalse(seams.replies.get(0).contains("/hr"), seams.replies.get(0));
            assertFalse(seams.replies.get(0).contains("dps"), seams.replies.get(0));
            assertFalse(seams.replies.get(0).contains("%"), seams.replies.get(0));
        }
    }

    @Test
    void shouldStayPutAndSaySoWhenNothingIsReachable() {
        Fixture f = fixture(TOWN);

        try (Seams seams = new Seams(null)) {
            BotAutopilotManager.start(f.entry(), f.bot());

            assertFalse(BotAutopilotManager.isActive(f.entry()));
            assertEquals(1, seams.replies.size());
        }
    }

    @Test
    void shouldConsumeTickWhileTravelingAndYieldToGrindFlowOnSite() {
        Fixture f = fixture(TOWN);
        f.entry().autopilotMapId = HUNTING_GROUND;
        f.entry().autopilotNextDecisionAtMs = Long.MAX_VALUE;
        f.entry().grinding = true;

        try (Seams seams = new Seams(null);
             MockedStatic<BotTravelManager> travel = mockStatic(BotTravelManager.class)) {
            travel.when(() -> BotTravelManager.tickTravel(any(), any(), anyInt(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenReturn(true);
            assertTrue(BotAutopilotManager.tick(f.entry(), f.bot(), true));

            // Arrived: tick yields so the normal grind/combat flow runs.
            when(f.bot().getMapId()).thenReturn(HUNTING_GROUND);
            assertFalse(BotAutopilotManager.tick(f.entry(), f.bot(), true));
            assertTrue(BotAutopilotManager.isActive(f.entry()));
        }
    }

    @Test
    void shouldAnnounceArrivalOnceBeforeGrindingOnSite() {
        Fixture f = fixture(TOWN);

        try (Seams seams = new Seams(expRec(HUNTING_GROUND, "Henesys Hunting Ground I"))) {
            BotAutopilotManager.start(f.entry(), f.bot());
            when(f.bot().getMapId()).thenReturn(HUNTING_GROUND);

            assertFalse(BotAutopilotManager.tick(f.entry(), f.bot(), true));
            assertEquals(2, seams.replies.size());
            assertTrue(seams.replies.get(1).contains("arrived at Henesys Hunting Ground I"), seams.replies.get(1));
            assertTrue(seams.replies.get(1).contains("entering grind mode"), seams.replies.get(1));
            assertTrue(f.entry().autopilotArrivalAnnounced);

            assertFalse(BotAutopilotManager.tick(f.entry(), f.bot(), true));
            assertEquals(2, seams.replies.size());
        }
    }

    @Test
    void shouldNotAnnounceArrivalWhenPickIsTheCurrentMap() {
        Fixture f = fixture(HUNTING_GROUND);

        try (Seams seams = new Seams(expRec(HUNTING_GROUND, "Henesys Hunting Ground I"))) {
            BotAutopilotManager.start(f.entry(), f.bot());
            assertTrue(f.entry().autopilotArrivalAnnounced);

            // Already on site: "this map works" was the whole announcement — no extra
            // "arrived at ..." line on the next tick.
            assertFalse(BotAutopilotManager.tick(f.entry(), f.bot(), true));
            assertEquals(1, seams.replies.size());
            assertTrue(seams.replies.get(0).startsWith("this map works"), seams.replies.get(0));
        }
    }

    @Test
    void shouldRedecideOnTimerAndAnnounceWhenMovingOn() {
        Fixture f = fixture(HUNTING_GROUND);
        f.entry().autopilotMapId = HUNTING_GROUND;
        f.entry().autopilotNextDecisionAtMs = System.currentTimeMillis() - 1;
        f.entry().autopilotArrivalAnnounced = true;
        f.entry().grinding = true;

        try (Seams seams = new Seams(expRec(104040000, "Somewhere Better"))) {
            assertFalse(BotAutopilotManager.tick(f.entry(), f.bot(), true));

            assertEquals(104040000, f.entry().autopilotMapId);
            assertTrue(f.entry().autopilotNextDecisionAtMs > System.currentTimeMillis());
            assertEquals(1, seams.replies.size());
            assertTrue(seams.replies.get(0).startsWith("heading to"), seams.replies.get(0));
        }
    }

    @Test
    void shouldApplySharedPartyPlanToAllMembers() {
        Fixture leader = fixture(TOWN);
        Fixture buddy = fixture(TOWN);
        Character owner = mock(Character.class);

        try (Seams seams = new Seams(null)) {
            BotAutopilotManager.partyDecider = members -> new BotGrindPlanner.PartyPlan(
                    HUNTING_GROUND,
                    java.util.Arrays.asList(expRec(HUNTING_GROUND, "Henesys Hunting Ground I"), null));
            BotAutopilotManager.startParty(owner, List.of(leader.entry(), buddy.entry()));

            assertEquals(HUNTING_GROUND, leader.entry().autopilotMapId);
            assertEquals(HUNTING_GROUND, buddy.entry().autopilotMapId);
            assertTrue(leader.entry().autopilotParty);
            assertTrue(buddy.entry().autopilotParty);
            assertTrue(leader.entry().grinding);
            assertTrue(buddy.entry().grinding);
            // Tag-along member: no own objective, no extra arrival chatter.
            assertEquals("back the party up", buddy.entry().autopilotObjectiveSummary);
            assertTrue(buddy.entry().autopilotArrivalAnnounced);
            // Member timers trail the leader's so the leader always re-decides first.
            assertTrue(buddy.entry().autopilotNextDecisionAtMs > leader.entry().autopilotNextDecisionAtMs);
            // One announcement (leader's), not one per member.
            assertEquals(1, seams.replies.size());
            assertTrue(seams.replies.get(0).startsWith("party plan:"), seams.replies.get(0));
        }
    }

    @Test
    void shouldPinFarmObjectiveAndKeepItAcrossInstall() {
        Fixture f = fixture(TOWN);
        Recommendation farmRec = new Recommendation(
                new MobCandidate(130101, "Orange Mushroom", 8, 15, 1.2,
                        HUNTING_GROUND, "Henesys Hunting Ground I", 12, List.of()),
                1800, 27_000, true, 1.0,
                new BotGrindPlanner.GearProspect(2040705, "Scroll for Gloves for ATT 60%", 0.02, 0, 0),
                2.5, 2.5);

        try (Seams seams = new Seams(null)) {
            BotAutopilotManager.farmAdvisor = (entry, bot, itemId, fromMapId, maxHops, withFerry) -> farmRec;
            BotAutopilotManager.startFarmItem(f.entry(), f.bot(), 2040705, "Scroll for Gloves for ATT 60%");

            assertEquals(2040705, f.entry().autopilotFarmItemId);
            assertEquals(HUNTING_GROUND, f.entry().autopilotMapId);
            assertTrue(f.entry().autopilotObjectiveSummary.contains("Scroll for Gloves for ATT 60%"),
                    f.entry().autopilotObjectiveSummary);
            assertTrue(seams.replies.get(0).contains("farm Scroll for Gloves for ATT 60%"), seams.replies.get(0));
        }
    }

    @Test
    void shouldRunResupplyErrandAndResumeTravelToGrindMap() {
        Fixture f = fixture(HUNTING_GROUND);
        f.entry().autopilotMapId = HUNTING_GROUND;
        f.entry().autopilotNextDecisionAtMs = Long.MAX_VALUE;
        f.entry().grinding = true;
        MapleMap town = mock(MapleMap.class);
        when(town.getId()).thenReturn(TOWN);
        when(f.bot().getMap().getReturnMap()).thenReturn(town);

        try (Seams seams = new Seams(null);
             MockedStatic<BotTravelManager> travel = mockStatic(BotTravelManager.class)) {
            travel.when(() -> BotTravelManager.tickTravel(any(), any(), anyInt(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenReturn(true);

            assertTrue(BotAutopilotManager.requestResupplyErrand(f.entry(), f.bot()));
            assertEquals(TOWN, f.entry().autopilotErrandMapId);
            assertEquals(1, seams.replies.size());

            // While the errand is on, travel runs toward the TOWN, not the grind map.
            assertTrue(BotAutopilotManager.tick(f.entry(), f.bot(), true));
            travel.verify(() -> BotTravelManager.tickTravel(any(), any(), org.mockito.ArgumentMatchers.eq(TOWN),
                    anyInt(), anyBoolean(), anyBoolean()));

            // Arrived in town, shop visit already over (or never needed): errand completes...
            when(f.bot().getMapId()).thenReturn(TOWN);
            assertFalse(BotAutopilotManager.tick(f.entry(), f.bot(), true));
            assertEquals(-1, f.entry().autopilotErrandMapId);
            assertTrue(BotAutopilotManager.isActive(f.entry()));

            // ...and the next tick travels back toward the grind map.
            assertTrue(BotAutopilotManager.tick(f.entry(), f.bot(), true));
            travel.verify(() -> BotTravelManager.tickTravel(any(), any(),
                    org.mockito.ArgumentMatchers.eq(HUNTING_GROUND), anyInt(), anyBoolean(), anyBoolean()));

            // Cooldown: an immediate second request doesn't bounce to the owner...
            assertTrue(BotAutopilotManager.requestResupplyErrand(f.entry(), f.bot()));
            assertEquals(-1, f.entry().autopilotErrandMapId); // ...but doesn't start a new errand either
        }
    }

    @Test
    void shouldWaitForOwnerSupplyGraceBeforeStartingResupplyErrand() {
        Fixture f = fixture(HUNTING_GROUND);
        f.entry().autopilotMapId = HUNTING_GROUND;
        f.entry().autopilotNextDecisionAtMs = Long.MAX_VALUE;
        f.entry().grinding = true;
        MapleMap town = mock(MapleMap.class);
        when(town.getId()).thenReturn(TOWN);
        when(f.bot().getMap().getReturnMap()).thenReturn(town);

        try (Seams seams = new Seams(null)) {
            BotAutopilotManager.noteLowSupplyPartyRequest(f.entry());

            assertTrue(BotAutopilotManager.requestResupplyErrand(f.entry(), f.bot()));
            assertEquals(-1, f.entry().autopilotErrandMapId);
            assertTrue(seams.replies.isEmpty());
        }
    }

    @Test
    void shouldClearOnOwnerModeCommands() {
        Fixture f = fixture(HUNTING_GROUND);
        f.entry().autopilotMapId = HUNTING_GROUND;
        f.entry().autopilotDestinationName = "Henesys Hunting Ground I";
        f.entry().autopilotObjectiveSummary = "grind Orange Mushroom";
        f.entry().autopilotArrivalAnnounced = true;
        f.entry().grinding = true;

        BotManager.getInstance().issueFollowOwner(f.entry());

        assertFalse(BotAutopilotManager.isActive(f.entry()));
        assertEquals("", f.entry().autopilotDestinationName);
        assertEquals("", f.entry().autopilotObjectiveSummary);
        assertFalse(f.entry().autopilotArrivalAnnounced);
        assertTrue(f.entry().following);
    }

    // ---- ferry permission gate ----

    private static final int ORBIS = 200000100;

    @Test
    void shouldAskBeforeFerryingOnlyWhileOwnerIsAround() {
        // Owner online, not approved: the decision runs ferry-off, plus one ferry-on teaser pass.
        Fixture supervised = fixture(TOWN, onlineOwner());
        try (Seams seams = new Seams(expRec(HUNTING_GROUND, "Henesys Hunting Ground I"))) {
            BotAutopilotManager.start(supervised.entry(), supervised.bot());
            assertEquals(List.of(false, true), seams.advisorFerryFlags);
        }

        // Owner absent: ferries are the bot's own call — single ferry-on pass, no teaser.
        Fixture unsupervised = fixture(TOWN);
        try (Seams seams = new Seams(expRec(HUNTING_GROUND, "Henesys Hunting Ground I"))) {
            BotAutopilotManager.start(unsupervised.entry(), unsupervised.bot());
            assertEquals(List.of(true), seams.advisorFerryFlags);
        }

        // Owner online but "sail away" approved: same single ferry-on pass.
        Fixture approved = fixture(TOWN, onlineOwner());
        approved.entry().autopilotFerryApproved = true;
        try (Seams seams = new Seams(expRec(HUNTING_GROUND, "Henesys Hunting Ground I"))) {
            BotAutopilotManager.start(approved.entry(), approved.bot());
            assertEquals(List.of(true), seams.advisorFerryFlags);
            // The clear() inside issueGrind must not revoke the permission the plan used.
            assertTrue(approved.entry().autopilotFerryApproved);
        }
    }

    @Test
    void shouldTeaseFerryOnceWhenOverseasIsWayBetter() {
        Fixture f = fixture(TOWN, onlineOwner());

        try (Seams seams = new Seams(
                expRec(HUNTING_GROUND, "Henesys Hunting Ground I", 10_000),
                expRec(ORBIS, "Orbis", 20_000))) {
            BotAutopilotManager.start(f.entry(), f.bot());

            // The plan itself stays local — sailing needs the owner's word.
            assertEquals(HUNTING_GROUND, f.entry().autopilotMapId);
            assertEquals(2, seams.replies.size());
            assertTrue(seams.replies.get(1).contains("way better grind across the sea at Orbis"),
                    seams.replies.get(1));
            assertTrue(seams.replies.get(1).contains("sail away"), seams.replies.get(1));
        }
    }

    @Test
    void shouldNotTeaseFerryWhenOverseasIsOnlySlightlyBetter() {
        Fixture f = fixture(TOWN, onlineOwner());

        try (Seams seams = new Seams(
                expRec(HUNTING_GROUND, "Henesys Hunting Ground I", 10_000),
                expRec(ORBIS, "Orbis", 12_000))) {
            BotAutopilotManager.start(f.entry(), f.bot());

            assertEquals(HUNTING_GROUND, f.entry().autopilotMapId);
            assertEquals(1, seams.replies.size());
        }
    }

    @Test
    void shouldRedecideWithFerriesRightAfterSailAwayApproval() {
        Fixture f = fixture(HUNTING_GROUND, onlineOwner());
        f.entry().autopilotMapId = HUNTING_GROUND;
        f.entry().autopilotNextDecisionAtMs = Long.MAX_VALUE;
        f.entry().autopilotArrivalAnnounced = true;
        f.entry().grinding = true;

        try (Seams seams = new Seams(
                expRec(HUNTING_GROUND, "Henesys Hunting Ground I", 10_000),
                expRec(ORBIS, "Orbis", 20_000))) {
            BotAutopilotManager.approveFerry(f.entry());
            assertTrue(f.entry().autopilotFerryApproved);
            assertEquals(0L, f.entry().autopilotNextDecisionAtMs);

            // The very next on-site tick re-decides with the ferry horizon and moves on.
            assertFalse(BotAutopilotManager.tick(f.entry(), f.bot(), true));
            assertEquals(List.of(true), seams.advisorFerryFlags);
            assertEquals(ORBIS, f.entry().autopilotMapId);
        }
    }

    @Test
    void shouldPassFerryPermissionToTravelTicks() {
        Fixture f = fixture(TOWN, onlineOwner());
        f.entry().autopilotMapId = HUNTING_GROUND;
        f.entry().autopilotNextDecisionAtMs = Long.MAX_VALUE;
        f.entry().grinding = true;

        try (Seams seams = new Seams(null);
             MockedStatic<BotTravelManager> travel = mockStatic(BotTravelManager.class)) {
            travel.when(() -> BotTravelManager.tickTravel(any(), any(), anyInt(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenReturn(true);

            assertTrue(BotAutopilotManager.tick(f.entry(), f.bot(), true));
            travel.verify(() -> BotTravelManager.tickTravel(any(), any(), anyInt(), anyInt(), anyBoolean(),
                    org.mockito.ArgumentMatchers.eq(false)));

            f.entry().autopilotFerryApproved = true;
            assertTrue(BotAutopilotManager.tick(f.entry(), f.bot(), true));
            travel.verify(() -> BotTravelManager.tickTravel(any(), any(), anyInt(), anyInt(), anyBoolean(),
                    org.mockito.ArgumentMatchers.eq(true)));
        }
    }

    @Test
    void shouldResetFerryApprovalWhenAutopilotClears() {
        Fixture f = fixture(HUNTING_GROUND);
        f.entry().autopilotMapId = HUNTING_GROUND;
        f.entry().autopilotFerryApproved = true;

        BotAutopilotManager.clear(f.entry());

        assertFalse(f.entry().autopilotFerryApproved);
    }

    @Test
    void shouldPullDecisionForwardWhenUpgradeEquipsOnAutopilot() {
        Fixture f = fixture(HUNTING_GROUND);
        f.entry().autopilotMapId = HUNTING_GROUND;
        f.entry().autopilotNextDecisionAtMs = System.currentTimeMillis() + 10 * 60_000L;

        BotAutopilotManager.noteGearUpgraded(f.entry());

        long expectedAtMost = System.currentTimeMillis() + BotAutopilotManager.UPGRADE_REDECIDE_DELAY_MS;
        assertTrue(f.entry().autopilotNextDecisionAtMs <= expectedAtMost,
                "decision should be pulled forward to ~now + " + BotAutopilotManager.UPGRADE_REDECIDE_DELAY_MS);
        assertTrue(f.entry().autopilotNextDecisionAtMs > System.currentTimeMillis(),
                "decision should still be in the future, not immediate");
    }

    @Test
    void shouldNeverPushDecisionBackOnUpgrade() {
        Fixture f = fixture(HUNTING_GROUND);
        f.entry().autopilotMapId = HUNTING_GROUND;
        long soon = System.currentTimeMillis() + 1_000L;
        f.entry().autopilotNextDecisionAtMs = soon;

        BotAutopilotManager.noteGearUpgraded(f.entry());

        assertEquals(soon, f.entry().autopilotNextDecisionAtMs);
    }

    @Test
    void shouldIgnoreUpgradeWhenNotAutopilotingOrFarmOrdered() {
        // Not on autopilot at all: nothing to re-decide.
        Fixture idle = fixture(HUNTING_GROUND);
        long far = System.currentTimeMillis() + 10 * 60_000L;
        idle.entry().autopilotNextDecisionAtMs = far;
        BotAutopilotManager.noteGearUpgraded(idle.entry());
        assertEquals(far, idle.entry().autopilotNextDecisionAtMs);

        // Owner-ordered "farm <item>": the objective is pinned, the roll changes nothing.
        Fixture farming = fixture(HUNTING_GROUND);
        farming.entry().autopilotMapId = HUNTING_GROUND;
        farming.entry().autopilotFarmItemId = 1402000;
        farming.entry().autopilotNextDecisionAtMs = far;
        BotAutopilotManager.noteGearUpgraded(farming.entry());
        assertEquals(far, farming.entry().autopilotNextDecisionAtMs);
        assertEquals(1402000, farming.entry().autopilotFarmItemId, "farm objective must survive");

        // Party autopilot: re-decides are leader-driven on the group clock.
        Fixture party = fixture(HUNTING_GROUND);
        party.entry().autopilotMapId = HUNTING_GROUND;
        party.entry().autopilotParty = true;
        party.entry().autopilotNextDecisionAtMs = far;
        BotAutopilotManager.noteGearUpgraded(party.entry());
        assertEquals(far, party.entry().autopilotNextDecisionAtMs);
    }

    @Test
    void shouldAttributePartyGearGoalsToTheirBeneficiary() {
        Fixture leader = fixture(TOWN);
        Fixture buddy = fixture(TOWN);
        when(buddy.bot().getName()).thenReturn("Buddy");
        Character owner = mock(Character.class);

        try (Seams seams = new Seams(null)) {
            BotAutopilotManager.partyDecider = members -> new BotGrindPlanner.PartyPlan(
                    HUNTING_GROUND,
                    java.util.Arrays.asList(
                            expRec(HUNTING_GROUND, "Henesys Hunting Ground I"),
                            gearRec(HUNTING_GROUND, "Henesys Hunting Ground I", "sword", 9_000)));
            BotAutopilotManager.startParty(owner, List.of(leader.entry(), buddy.entry()));

            assertEquals(2, seams.replies.size());
            assertTrue(seams.replies.get(0).startsWith("party plan:"), seams.replies.get(0));
            assertEquals("farm sword from Drake for Buddy", seams.replies.get(1));
        }
    }
}
