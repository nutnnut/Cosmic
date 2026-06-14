package server.bots;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.bots.BotGrindPlanner.MobCandidate;
import server.bots.BotGrindPlanner.Recommendation;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        private final BotAutopilotManager.PartyMembersLookup previousPartyMembers = BotAutopilotManager.partyMembers;
        private final BotAutopilotManager.HopDistance previousHopDistance = BotAutopilotManager.hopDistance;
        private final BotAutopilotManager.SupplyLevel previousSupplyLevel = BotAutopilotManager.supplyLevel;
        private final BotAutopilotManager.BagFull previousBagFull = BotAutopilotManager.bagFull;

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
            // Default: well-stocked, so the pre-travel resupply gate never trips for the
            // travel/formation/portal-wait tests. The pre-travel test overrides this.
            BotAutopilotManager.supplyLevel = bot -> false;
            // Default: bag has room, so the bag-full pre-travel gate never trips either.
            BotAutopilotManager.bagFull = (entry, bot) -> false;
        }

        @Override
        public void close() {
            BotAutopilotManager.advisor = previousAdvisor;
            BotAutopilotManager.partyDecider = previousPartyDecider;
            BotAutopilotManager.farmAdvisor = previousFarmAdvisor;
            BotAutopilotManager.reply = previousReply;
            BotAutopilotManager.decisionRunner = previousRunner;
            BotAutopilotManager.partyMembers = previousPartyMembers;
            BotAutopilotManager.hopDistance = previousHopDistance;
            BotAutopilotManager.supplyLevel = previousSupplyLevel;
            BotAutopilotManager.bagFull = previousBagFull;
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
    void shouldWanderToRandomPortalWhenStrandedOffDestination() {
        Fixture f = fixture(TOWN);
        f.entry().autopilotMapId = HUNTING_GROUND; // destination is elsewhere -> off-site
        f.entry().autopilotNextDecisionAtMs = Long.MAX_VALUE;
        f.entry().grinding = true;

        try (Seams seams = new Seams(null);
             MockedStatic<BotTravelManager> travel = mockStatic(BotTravelManager.class)) {
            // No legal travel progress (route gone / hop failed).
            travel.when(() -> BotTravelManager.tickTravel(any(), any(), anyInt(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenReturn(false);
            travel.when(() -> BotTravelManager.tickWanderToRandomPortal(any(), any(), anyBoolean()))
                    .thenReturn(true);

            // Stranded: tick consumes the tick by wandering to a portal instead of yielding to
            // the grind flow (which would let the owner-anchor fallback fire).
            assertTrue(BotAutopilotManager.tick(f.entry(), f.bot(), true));
            travel.verify(() -> BotTravelManager.tickWanderToRandomPortal(f.entry(), f.bot(), true));
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
    void shouldFollowLeaderInFormationWhileTravelingWithParty() {
        Fixture leader = fixture(104000000, onlineOwner());
        Fixture follower = fixture(TOWN, onlineOwner());
        when(leader.bot().getId()).thenReturn(7001);
        for (Fixture f : List.of(leader, follower)) {
            f.entry().autopilotMapId = HUNTING_GROUND;
            f.entry().autopilotParty = true;
            f.entry().autopilotNextDecisionAtMs = Long.MAX_VALUE;
            f.entry().grinding = true;
        }

        try (Seams seams = new Seams(null)) {
            BotAutopilotManager.partyMembers = entry -> List.of(leader.entry(), follower.entry());

            // Transition tick is consumed: the follow anchor was resolved before autopilot ran.
            assertTrue(BotAutopilotManager.tick(follower.entry(), follower.bot(), true));
            assertTrue(follower.entry().autopilotTransitFollow);
            assertTrue(follower.entry().following);
            assertEquals(7001, follower.entry().followTargetId);
            assertFalse(follower.entry().grinding);

            // Already in formation: yield the tick to the regular follow pipeline.
            assertFalse(BotAutopilotManager.tick(follower.entry(), follower.bot(), true));
            assertTrue(follower.entry().following);
        }
    }

    @Test
    void shouldRestoreGrindWhenTransitFollowerArrivesAtDestination() {
        Fixture follower = fixture(HUNTING_GROUND, onlineOwner());
        follower.entry().autopilotMapId = HUNTING_GROUND;
        follower.entry().autopilotParty = true;
        follower.entry().autopilotNextDecisionAtMs = Long.MAX_VALUE;
        follower.entry().autopilotArrivalAnnounced = true;
        follower.entry().autopilotTransitFollow = true;
        follower.entry().following = true;
        follower.entry().followTargetId = 7001;

        try (Seams seams = new Seams(null)) {
            assertFalse(BotAutopilotManager.tick(follower.entry(), follower.bot(), true));
            assertFalse(follower.entry().autopilotTransitFollow);
            assertFalse(follower.entry().following);
            assertEquals(0, follower.entry().followTargetId);
            assertTrue(follower.entry().grinding);
        }
    }

    @Test
    void shouldHoldTheMapWhileAMemberIsFarBehindThenTravelOnceCaughtUp() {
        Fixture leader = fixture(104000000, onlineOwner());
        Fixture follower = fixture(TOWN, onlineOwner());
        for (Fixture f : List.of(leader, follower)) {
            f.entry().autopilotMapId = HUNTING_GROUND;
            f.entry().autopilotParty = true;
            f.entry().autopilotNextDecisionAtMs = Long.MAX_VALUE;
            f.entry().grinding = true;
        }

        try (Seams seams = new Seams(null);
             MockedStatic<BotTravelManager> travel = mockStatic(BotTravelManager.class)) {
            travel.when(() -> BotTravelManager.tickTravel(any(), any(), anyInt(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenReturn(true);
            BotAutopilotManager.partyMembers = entry -> List.of(leader.entry(), follower.entry());
            BotAutopilotManager.hopDistance = (from, to) -> BotManager.cfg.STRAGGLER_WAIT_HOPS + 1;

            // Straggler too far: travel is skipped, the tick falls through to grinding here.
            assertFalse(BotAutopilotManager.tick(leader.entry(), leader.bot(), true));
            assertTrue(leader.entry().autopilotWaitingForStragglers);
            assertEquals(0, seams.replies.size()); // the wait hold is silent now (no chatter)

            // Caught up: next check clears the hold and travel resumes.
            leader.entry().autopilotNextStragglerCheckAtMs = 0L;
            BotAutopilotManager.hopDistance = (from, to) -> 1;
            assertTrue(BotAutopilotManager.tick(leader.entry(), leader.bot(), true));
            assertFalse(leader.entry().autopilotWaitingForStragglers);
            assertEquals(0, seams.replies.size());
        }
    }

    @Test
    void leaderPortalAnchorsWhileWaitingInTransitAndDoesNotEnterThePortal() {
        // Leader still in transit (current map != destination) with a member 2+ hops behind.
        Fixture leader = fixture(104000000, onlineOwner());
        Fixture follower = fixture(TOWN, onlineOwner());
        for (Fixture f : List.of(leader, follower)) {
            f.entry().autopilotMapId = HUNTING_GROUND;
            f.entry().autopilotParty = true;
            f.entry().autopilotNextDecisionAtMs = Long.MAX_VALUE;
            f.entry().grinding = true;
        }

        try (Seams seams = new Seams(null);
             MockedStatic<BotTravelManager> travel = mockStatic(BotTravelManager.class)) {
            // The leader resolves a next-hop portal to stand at; tickTravel (the enter path) is
            // mocked to fail the test if it's ever called while waiting.
            travel.when(() -> BotTravelManager.nextHopPortalPosition(any(), any(), anyInt(), anyInt()))
                    .thenReturn(new Point(500, 100));
            BotAutopilotManager.partyMembers = entry -> List.of(leader.entry(), follower.entry());
            BotAutopilotManager.hopDistance = (from, to) -> 2; // 2 > threshold(1): hold

            assertFalse(BotAutopilotManager.tick(leader.entry(), leader.bot(), true));
            assertTrue(leader.entry().autopilotWaitingForStragglers);
            // Portal-anchored, not grind-wandering: anchor pinned, grinding off, NEVER entered.
            assertEquals(new Point(500, 100), leader.entry().autopilotWaitAnchor);
            assertEquals(104000000, leader.entry().autopilotWaitAnchorMapId);
            assertFalse(leader.entry().grinding);
            travel.verify(() -> BotTravelManager.tickTravel(any(), any(), anyInt(), anyInt(), anyBoolean(), anyBoolean()),
                    org.mockito.Mockito.never());

            // Caught up: the hold releases, the anchor clears, grinding is restored, and travel
            // (the enter path) is allowed to run again.
            travel.when(() -> BotTravelManager.tickTravel(any(), any(), anyInt(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenReturn(true);
            leader.entry().autopilotNextStragglerCheckAtMs = 0L;
            BotAutopilotManager.hopDistance = (from, to) -> 1;
            assertTrue(BotAutopilotManager.tick(leader.entry(), leader.bot(), true));
            assertFalse(leader.entry().autopilotWaitingForStragglers);
            assertNull(leader.entry().autopilotWaitAnchor);
            assertEquals(-1, leader.entry().autopilotWaitAnchorMapId);
            assertTrue(leader.entry().grinding);
        }
    }

    @Test
    void leaderHoldsMapWithoutAnchorWhenNoNextHopPortal() {
        // No walkable next-hop portal (consumable/taxi leg): fall back to plain "hold + grind".
        Fixture leader = fixture(104000000, onlineOwner());
        Fixture follower = fixture(TOWN, onlineOwner());
        for (Fixture f : List.of(leader, follower)) {
            f.entry().autopilotMapId = HUNTING_GROUND;
            f.entry().autopilotParty = true;
            f.entry().autopilotNextDecisionAtMs = Long.MAX_VALUE;
            f.entry().grinding = true;
        }

        try (Seams seams = new Seams(null);
             MockedStatic<BotTravelManager> travel = mockStatic(BotTravelManager.class)) {
            travel.when(() -> BotTravelManager.nextHopPortalPosition(any(), any(), anyInt(), anyInt()))
                    .thenReturn(null);
            travel.when(() -> BotTravelManager.tickTravel(any(), any(), anyInt(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenReturn(true);
            BotAutopilotManager.partyMembers = entry -> List.of(leader.entry(), follower.entry());
            BotAutopilotManager.hopDistance = (from, to) -> 2;

            assertFalse(BotAutopilotManager.tick(leader.entry(), leader.bot(), true));
            assertTrue(leader.entry().autopilotWaitingForStragglers);
            assertNull(leader.entry().autopilotWaitAnchor); // nothing to stand at
            assertTrue(leader.entry().grinding);            // plain hold keeps grinding
        }
    }

    @Test
    void shouldWaitWhenAMemberIsTwoMapsBehindButNotOneMap() {
        Fixture leader = fixture(104000000, onlineOwner());
        Fixture follower = fixture(TOWN, onlineOwner());
        for (Fixture f : List.of(leader, follower)) {
            f.entry().autopilotMapId = HUNTING_GROUND;
            f.entry().autopilotParty = true;
            f.entry().autopilotNextDecisionAtMs = Long.MAX_VALUE;
            f.entry().grinding = true;
        }

        try (Seams seams = new Seams(null);
             MockedStatic<BotTravelManager> travel = mockStatic(BotTravelManager.class)) {
            travel.when(() -> BotTravelManager.tickTravel(any(), any(), anyInt(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenReturn(true);
            BotAutopilotManager.partyMembers = entry -> List.of(leader.entry(), follower.entry());

            // One map behind (1 hop): within tolerance, threshold is 1 so 1 > 1 is false. No wait.
            BotAutopilotManager.hopDistance = (from, to) -> 1;
            assertTrue(BotAutopilotManager.tick(leader.entry(), leader.bot(), true));
            assertFalse(leader.entry().autopilotWaitingForStragglers);

            // Two maps behind (2 hops): 2 > 1, the leader holds.
            leader.entry().autopilotNextStragglerCheckAtMs = 0L;
            BotAutopilotManager.hopDistance = (from, to) -> 2;
            assertFalse(BotAutopilotManager.tick(leader.entry(), leader.bot(), true));
            assertTrue(leader.entry().autopilotWaitingForStragglers);
        }
    }

    @Test
    void shouldWaitForSameMapStragglerPastTheBandAndReleaseWithinHysteresis() {
        // Leader already on the destination map (no next-hop portal) so the same-map gap is the
        // only thing that can trigger a wait, and the existing arrived "hold + grind" applies.
        Fixture leader = fixture(HUNTING_GROUND, onlineOwner());
        Fixture follower = fixture(HUNTING_GROUND, onlineOwner());
        when(leader.bot().getPosition()).thenReturn(new Point(0, 0));
        for (Fixture f : List.of(leader, follower)) {
            f.entry().autopilotMapId = HUNTING_GROUND;
            f.entry().autopilotParty = true;
            f.entry().autopilotNextDecisionAtMs = Long.MAX_VALUE;
            f.entry().grinding = true;
        }

        try (Seams seams = new Seams(null)) {
            BotAutopilotManager.partyMembers = entry -> List.of(leader.entry(), follower.entry());
            BotAutopilotManager.hopDistance = (from, to) -> 0; // same map, no hops apart

            // Past SAME_MAP_STRAGGLER_PX (700): the leader waits.
            when(follower.bot().getPosition()).thenReturn(new Point(900, 0));
            assertTrue(waitingForStragglers(leader));
            assertTrue(leader.entry().autopilotWaitingForStragglers);

            // Inside the trigger band but still outside the tighter resume band (350..700):
            // hysteresis keeps the hold while already waiting.
            leader.entry().autopilotNextStragglerCheckAtMs = 0L;
            when(follower.bot().getPosition()).thenReturn(new Point(500, 0));
            assertTrue(waitingForStragglers(leader));
            assertTrue(leader.entry().autopilotWaitingForStragglers);

            // Within the resume band (<=350): release.
            leader.entry().autopilotNextStragglerCheckAtMs = 0L;
            when(follower.bot().getPosition()).thenReturn(new Point(300, 0));
            assertFalse(waitingForStragglers(leader));
            assertFalse(leader.entry().autopilotWaitingForStragglers);
        }
    }

    @Test
    void leaderReleasesWhenMembersBunchAtPortalEvenIfFarFromSpreadFormationSlots() {
        // Regression for pathlog-Bowgurl 2026-06-14T08:09: after a portal hop the party bunches at
        // the landing (small BODY gaps) but their formation slots are spread by followOffsetX, so a
        // slot-only straggler check read the bunched members as ~far and the leader held forever.
        // A member present by EITHER body OR slot must release the hold.
        Fixture leader = fixture(HUNTING_GROUND, onlineOwner());
        Fixture follower = fixture(HUNTING_GROUND, onlineOwner());
        when(leader.bot().getPosition()).thenReturn(new Point(0, 0));
        for (Fixture f : List.of(leader, follower)) {
            f.entry().autopilotMapId = HUNTING_GROUND;
            f.entry().autopilotParty = true;
            f.entry().autopilotNextDecisionAtMs = Long.MAX_VALUE;
            f.entry().grinding = true;
        }
        follower.entry().followOffsetX = 400; // wide slot, beyond the 350 resume band
        leader.entry().autopilotWaitingForStragglers = true; // already holding -> resume band (350) applies

        try (Seams seams = new Seams(null)) {
            BotAutopilotManager.partyMembers = entry -> List.of(leader.entry(), follower.entry());
            BotAutopilotManager.hopDistance = (from, to) -> 0; // same map

            // Bunched at the leader's body: body gap 0, but slot gap 400 (> 350). Slot-only would
            // stay stuck; min(body, slot) = 0 releases the hold.
            when(follower.bot().getPosition()).thenReturn(new Point(0, 0));
            leader.entry().autopilotNextStragglerCheckAtMs = 0L;
            assertFalse(waitingForStragglers(leader));
            assertFalse(leader.entry().autopilotWaitingForStragglers);

            // A genuine straggler is far by BOTH metrics (body 800, slot 400) -> still waits.
            leader.entry().autopilotWaitingForStragglers = true;
            leader.entry().autopilotNextStragglerCheckAtMs = 0L;
            when(follower.bot().getPosition()).thenReturn(new Point(800, 0));
            assertTrue(waitingForStragglers(leader));
            assertTrue(leader.entry().autopilotWaitingForStragglers);
        }
    }

    /** Drives the package-private straggler check directly (the on-destination leader path
     *  returns before tickPartyCohesion, so exercise the wait predicate in isolation). */
    private static boolean waitingForStragglers(Fixture leader) {
        return BotAutopilotManager.waitingForStragglers(
                leader.entry(), leader.bot(), BotAutopilotManager.partyMembers.members(leader.entry()));
    }

    @Test
    void shouldNotWaitForMemberAlreadyAtTheDestination() {
        // Regression for pathlog-Bowgurl 2026-06-14T11:25: leader stuck in town after a resupply
        // while a member sat AT the grind destination. hops(member -> leaderTown) routes backward
        // and exceeds the cap -> MAX_VALUE, so the leader waited forever for a bot that had arrived.
        // The member is AHEAD (0 hops to dest vs the leader's many), not behind, so no hold.
        Fixture leader = fixture(TOWN, onlineOwner());
        Fixture atDest = fixture(HUNTING_GROUND, onlineOwner());
        for (Fixture f : List.of(leader, atDest)) {
            f.entry().autopilotMapId = HUNTING_GROUND;
            f.entry().autopilotParty = true;
            f.entry().autopilotNextDecisionAtMs = Long.MAX_VALUE;
            f.entry().grinding = true;
        }

        try (Seams seams = new Seams(null)) {
            BotAutopilotManager.partyMembers = entry -> List.of(leader.entry(), atDest.entry());
            // Pair-keyed: same map -> 0 hops; any cross-map pair is unreachable within the cap (MAX).
            // memberHops = hops(dest, town) = MAX > 1 (enters the hold branch); but the ahead guard
            // sees memberToDest=hops(dest,dest)=0 < leaderToDest=hops(town,dest)=MAX -> ahead -> no wait.
            BotAutopilotManager.hopDistance = (from, to) -> from == to ? 0 : Integer.MAX_VALUE;

            assertFalse(waitingForStragglers(leader));
            assertFalse(leader.entry().autopilotWaitingForStragglers);
        }
    }

    @Test
    void shouldFollowNextNonResupplyingMemberWhenNominalLeaderIsResupplying() {
        // 3 members: members.get(0) is off on a resupply errand, so the effective cohesion
        // leader is members.get(1); the follower transit-follows THAT bot, not the absent one.
        Fixture nominal = fixture(104000000, onlineOwner());
        Fixture effective = fixture(104000000, onlineOwner());
        Fixture follower = fixture(TOWN, onlineOwner());
        when(nominal.bot().getId()).thenReturn(7001);
        when(effective.bot().getId()).thenReturn(7002);
        for (Fixture f : List.of(nominal, effective, follower)) {
            f.entry().autopilotMapId = HUNTING_GROUND;
            f.entry().autopilotParty = true;
            f.entry().autopilotNextDecisionAtMs = Long.MAX_VALUE;
            f.entry().grinding = true;
        }
        nominal.entry().autopilotErrandMapId = TOWN; // resupplying -> excluded as leader

        try (Seams seams = new Seams(null)) {
            BotAutopilotManager.partyMembers =
                    entry -> List.of(nominal.entry(), effective.entry(), follower.entry());

            assertTrue(BotAutopilotManager.tick(follower.entry(), follower.bot(), true));
            assertTrue(follower.entry().autopilotTransitFollow);
            assertEquals(7002, follower.entry().followTargetId); // members.get(1), not get(0)
        }
    }

    @Test
    void shouldRunIndependentlyWhenAllOtherMembersAreResupplying() {
        // Only one non-resupplying member remains: no cohesion group, it travels itself.
        Fixture resupplying = fixture(104000000, onlineOwner());
        Fixture lone = fixture(TOWN, onlineOwner());
        when(resupplying.bot().getId()).thenReturn(7001);
        for (Fixture f : List.of(resupplying, lone)) {
            f.entry().autopilotMapId = HUNTING_GROUND;
            f.entry().autopilotParty = true;
            f.entry().autopilotNextDecisionAtMs = Long.MAX_VALUE;
            f.entry().grinding = true;
        }
        resupplying.entry().autopilotErrandMapId = TOWN;

        try (Seams seams = new Seams(null);
             MockedStatic<BotTravelManager> travel = mockStatic(BotTravelManager.class)) {
            travel.when(() -> BotTravelManager.tickTravel(any(), any(), anyInt(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenReturn(true);
            BotAutopilotManager.partyMembers = entry -> List.of(resupplying.entry(), lone.entry());

            // No transit-follow: the lone member travels toward the grind map on its own.
            assertTrue(BotAutopilotManager.tick(lone.entry(), lone.bot(), true));
            assertFalse(lone.entry().autopilotTransitFollow);
            travel.verify(() -> BotTravelManager.tickTravel(any(), any(),
                    org.mockito.ArgumentMatchers.eq(HUNTING_GROUND), anyInt(), anyBoolean(), anyBoolean()));
        }
    }

    @Test
    void leaderDoesNotWaitForAResupplyingMember() {
        // The straggler far behind is off resupplying: the leader must NOT hold for it.
        Fixture leader = fixture(104000000, onlineOwner());
        Fixture resupplying = fixture(TOWN, onlineOwner());
        for (Fixture f : List.of(leader, resupplying)) {
            f.entry().autopilotMapId = HUNTING_GROUND;
            f.entry().autopilotParty = true;
            f.entry().autopilotNextDecisionAtMs = Long.MAX_VALUE;
            f.entry().grinding = true;
        }
        resupplying.entry().autopilotErrandMapId = TOWN;

        try (Seams seams = new Seams(null);
             MockedStatic<BotTravelManager> travel = mockStatic(BotTravelManager.class)) {
            travel.when(() -> BotTravelManager.tickTravel(any(), any(), anyInt(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenReturn(true);
            BotAutopilotManager.partyMembers = entry -> List.of(leader.entry(), resupplying.entry());
            BotAutopilotManager.hopDistance = (from, to) -> BotManager.cfg.STRAGGLER_WAIT_HOPS + 1;

            // Were the resupplying member counted, the leader would hold; instead it travels on.
            assertTrue(BotAutopilotManager.tick(leader.entry(), leader.bot(), true));
            assertFalse(leader.entry().autopilotWaitingForStragglers);
        }
    }

    @Test
    void followerOffGrindMapRoutesToGrindMapNotTownWhenLeaderIsResupplying() {
        // members.get(0) is resupplying; a follower drifted off the grind map. With dynamic
        // leadership it anchors on the next non-resupplying member (members.get(1)) - never the
        // absent leader heading to town. Here that anchor IS the follower's own next-in-line, so
        // the follower travels to the grind map itself (no transit-follow toward town).
        Fixture resupplying = fixture(104000000, onlineOwner());
        Fixture follower = fixture(104010000, onlineOwner()); // off the grind map, mid-route
        when(resupplying.bot().getId()).thenReturn(7001);
        for (Fixture f : List.of(resupplying, follower)) {
            f.entry().autopilotMapId = HUNTING_GROUND;
            f.entry().autopilotParty = true;
            f.entry().autopilotNextDecisionAtMs = Long.MAX_VALUE;
            f.entry().grinding = true;
        }
        resupplying.entry().autopilotErrandMapId = TOWN;

        try (Seams seams = new Seams(null);
             MockedStatic<BotTravelManager> travel = mockStatic(BotTravelManager.class)) {
            travel.when(() -> BotTravelManager.tickTravel(any(), any(), anyInt(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenReturn(true);
            BotAutopilotManager.partyMembers = entry -> List.of(resupplying.entry(), follower.entry());

            assertTrue(BotAutopilotManager.tick(follower.entry(), follower.bot(), true));
            assertFalse(follower.entry().autopilotTransitFollow); // not chasing the absent leader
            travel.verify(() -> BotTravelManager.tickTravel(any(), any(),
                    org.mockito.ArgumentMatchers.eq(HUNTING_GROUND), anyInt(), anyBoolean(), anyBoolean()));
        }
    }

    @Test
    void shouldResupplyBeforeDepartingWhenSuppliesAlreadyLowOffDestination() {
        // Off the grind map with supplies already below threshold: restock FIRST (town errand),
        // don't travel out and bounce back. Adequate supplies -> travel straight to the grind map.
        Fixture f = fixture(104010000); // mid-route, distinct from both the town and the grind map
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
            BotAutopilotManager.supplyLevel = bot -> true; // low on supplies

            assertTrue(BotAutopilotManager.tick(f.entry(), f.bot(), true));
            assertEquals(TOWN, f.entry().autopilotErrandMapId); // errand triggered pre-travel
            travel.verify(() -> BotTravelManager.tickTravel(any(), any(),
                    org.mockito.ArgumentMatchers.eq(TOWN), anyInt(), anyBoolean(), anyBoolean()));

            // Returning from the errand must NOT re-trigger the pre-travel gate (loop guard).
            f.entry().autopilotErrandMapId = -1;
            f.entry().autopilotReturningFromErrand = true;
            assertTrue(BotAutopilotManager.tick(f.entry(), f.bot(), true));
            assertEquals(-1, f.entry().autopilotErrandMapId);
            travel.verify(() -> BotTravelManager.tickTravel(any(), any(),
                    org.mockito.ArgumentMatchers.eq(HUNTING_GROUND), anyInt(), anyBoolean(), anyBoolean()));
        }
    }

    @Test
    void shouldResupplyBeforeDepartingWhenBagIsFullEvenWithAdequateSupplies() {
        // Supplies are fine, but the bag is full enough to need a junk dump: still divert to town
        // FIRST (sell trash there) instead of traveling out to grind with no room for loot.
        Fixture f = fixture(104010000);
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
            BotAutopilotManager.supplyLevel = bot -> false; // well-stocked on pots/ammo
            BotAutopilotManager.bagFull = (entry, bot) -> true; // ...but the bag is full of trash

            assertTrue(BotAutopilotManager.tick(f.entry(), f.bot(), true));
            assertEquals(TOWN, f.entry().autopilotErrandMapId); // bag-full triggered the town errand
            travel.verify(() -> BotTravelManager.tickTravel(any(), any(),
                    org.mockito.ArgumentMatchers.eq(TOWN), anyInt(), anyBoolean(), anyBoolean()));
        }
    }

    @Test
    void shouldTravelStraightToGrindMapWhenSuppliesAdequate() {
        Fixture f = fixture(TOWN);
        f.entry().autopilotMapId = HUNTING_GROUND;
        f.entry().autopilotNextDecisionAtMs = Long.MAX_VALUE;
        f.entry().grinding = true;

        try (Seams seams = new Seams(null);
             MockedStatic<BotTravelManager> travel = mockStatic(BotTravelManager.class)) {
            travel.when(() -> BotTravelManager.tickTravel(any(), any(), anyInt(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenReturn(true);
            // supplyLevel defaults to false (adequate) in Seams: no errand, straight travel.
            assertTrue(BotAutopilotManager.tick(f.entry(), f.bot(), true));
            assertEquals(-1, f.entry().autopilotErrandMapId);
            travel.verify(() -> BotTravelManager.tickTravel(any(), any(),
                    org.mockito.ArgumentMatchers.eq(HUNTING_GROUND), anyInt(), anyBoolean(), anyBoolean()));
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
            assertEquals("supplies low, popping back to town real quick", seams.replies.get(0));

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
    void shouldNameSellTrashReasonWhenStartingResupplyErrand() {
        Fixture f = fixture(HUNTING_GROUND);

        try (MockedStatic<BotShopManager> shop = mockStatic(BotShopManager.class)) {
            shop.when(() -> BotShopManager.shouldAutoSellTrash(f.entry(), f.bot())).thenReturn(true);

            assertEquals("bags are full enough to sell junk - popping back to town real quick",
                    BotAutopilotManager.resupplyErrandMessage(f.entry(), f.bot()));
        }
    }

    @Test
    void shouldReportAutopilotStatusOnDestination() {
        Fixture f = fixture(HUNTING_GROUND);
        when(f.bot().getHp()).thenReturn(50);
        when(f.bot().getMap().getMapName()).thenReturn("Henesys Hunting Ground I");
        f.entry().autopilotMapId = HUNTING_GROUND;
        f.entry().autopilotDestinationName = "Henesys Hunting Ground I";
        f.entry().autopilotObjectiveSummary = "farm Pan Lid from Orange Mushroom";

        String report = BotAutopilotManager.statusReport(f.entry(), f.bot());

        assertEquals("farming Pan Lid from Orange Mushroom at Henesys Hunting Ground I", report);
    }

    @Test
    void shouldExplainWhyInStatusReports() {
        Fixture f = fixture(TOWN);
        when(f.bot().getHp()).thenReturn(50);
        when(f.bot().getMap().getMapName()).thenReturn("Henesys");

        try (Seams seams = new Seams(expRec(HUNTING_GROUND, "Henesys Hunting Ground I"))) {
            BotAutopilotManager.start(f.entry(), f.bot());

            String reason = f.entry().autopilotObjectiveReason;
            assertFalse(reason.isEmpty());
            assertFalse(reason.matches(".*\\d.*"), reason); // plain words, no numbers

            // Both the traveling and the on-site answer carry the why.
            String traveling = BotAutopilotManager.statusReport(f.entry(), f.bot());
            assertTrue(traveling.endsWith("- " + reason), traveling);
            when(f.bot().getMapId()).thenReturn(HUNTING_GROUND);
            when(f.bot().getMap().getMapName()).thenReturn("Henesys Hunting Ground I");
            String onSite = BotAutopilotManager.statusReport(f.entry(), f.bot());
            assertTrue(onSite.contains("grinding Orange Mushroom at Henesys Hunting Ground I"), onSite);
            assertTrue(onSite.endsWith("- " + reason), onSite);
        }
    }

    @Test
    void shouldGiveGearReasonWhenGearFocusedAndNoneWhenOwnerPinnedTheItem() {
        Fixture gear = fixture(TOWN);
        try (Seams seams = new Seams(gearRec(HUNTING_GROUND, "Drake Cave", "sword", 9_000))) {
            BotAutopilotManager.start(gear.entry(), gear.bot());
            String reason = gear.entry().autopilotObjectiveReason;
            assertFalse(reason.isEmpty());
            assertFalse(reason.contains("exp"), reason); // gear plan explains the gear, not exp
        }

        Fixture pinned = fixture(TOWN);
        try (Seams seams = new Seams(null)) {
            BotAutopilotManager.farmAdvisor = (entry, bot, itemId, fromMapId, maxHops, withFerry) ->
                    gearRec(HUNTING_GROUND, "Drake Cave", "sword", 9_000);
            BotAutopilotManager.startFarmItem(pinned.entry(), pinned.bot(), 1402000, "sword");
            // Owner picked the goal: "farm sword from Drake" needs no extra excuse.
            assertEquals("", pinned.entry().autopilotObjectiveReason);
        }
    }

    @Test
    void shouldReportAutopilotResupplyDetour() {
        Fixture f = fixture(TOWN);
        when(f.bot().getHp()).thenReturn(50);
        when(f.bot().getMap().getMapName()).thenReturn("Henesys");
        f.entry().autopilotMapId = HUNTING_GROUND;
        f.entry().autopilotDestinationName = "Henesys Hunting Ground I";
        f.entry().autopilotObjectiveSummary = "grind Orange Mushroom";
        f.entry().autopilotErrandMapId = TOWN;

        String report = BotAutopilotManager.statusReport(f.entry(), f.bot());

        assertTrue(report.contains("grinding Orange Mushroom at Henesys Hunting Ground I"), report);
        assertTrue(report.contains("going back to town to resupply"), report);
    }

    @Test
    void shouldReportRecentAutopilotDeathReturn() {
        Fixture f = fixture(TOWN);
        when(f.bot().getHp()).thenReturn(50);
        when(f.bot().getMap().getMapName()).thenReturn("Henesys");
        f.entry().autopilotMapId = HUNTING_GROUND;
        f.entry().autopilotDestinationName = "Henesys Hunting Ground I";
        f.entry().autopilotObjectiveSummary = "grind Orange Mushroom";
        f.entry().autopilotLastDeathAtMs = System.currentTimeMillis();

        String report = BotAutopilotManager.statusReport(f.entry(), f.bot());

        assertTrue(report.contains("i died and omw back"), report);
    }

    @Test
    void shouldReportNonAutopilotActivity() {
        Fixture f = fixture(TOWN);
        when(f.bot().getMap().getMapName()).thenReturn("Henesys");
        f.entry().following = true;

        assertEquals("im at Henesys, following you", BotAutopilotManager.statusReport(f.entry(), f.bot()));
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
