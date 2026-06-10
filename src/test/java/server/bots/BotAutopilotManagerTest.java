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
        MobCandidate pick = new MobCandidate(130101, "Orange Mushroom", 8, 15, 1.2,
                mapId, mapName, 12, List.of());
        return new Recommendation(pick, 1800, 27_000, false, 0.1, null, 0);
    }

    private record Fixture(BotEntry entry, Character bot) {}

    private static Fixture fixture(int mapId) {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(mapId);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.lastMapId = mapId;
        return new Fixture(entry, bot);
    }

    /** Swaps the advisor + reply seams; restore via close(). */
    private static final class Seams implements AutoCloseable {
        final List<String> replies = new ArrayList<>();
        private final BotAutopilotManager.Advisor previousAdvisor = BotAutopilotManager.advisor;
        private final java.util.function.BiConsumer<BotEntry, String> previousReply = BotAutopilotManager.reply;

        Seams(Recommendation recommendation) {
            BotAutopilotManager.advisor = (entry, bot, fromMapId, maxHops) -> recommendation;
            BotAutopilotManager.reply = (entry, text) -> replies.add(text);
        }

        @Override
        public void close() {
            BotAutopilotManager.advisor = previousAdvisor;
            BotAutopilotManager.reply = previousReply;
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
            travel.when(() -> BotTravelManager.tickTravel(any(), any(), anyInt(), anyInt(), anyBoolean()))
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
}
