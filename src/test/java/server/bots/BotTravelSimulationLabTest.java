package server.bots;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.when;

/**
 * Offline travel harness — the navigation lab's real-WZ-geometry + real-physics substrate, but driving
 * {@link BotTravelManager#tickTravel} so a bot actually tries to walk to a cab/usher and board. Reproduces
 * the "stuck job-advancing at the cab" class of bug without a live server: a pre-seeded taxi hop runs the
 * exact production path ({@code tickTaxiHop} -> {@code stepMovementCore}) over the loaded map's footholds.
 *
 * <p>Skipped automatically when {@code wz/} isn't present (CI without WZ data), like the other nav-lab tests.
 */
final class BotTravelSimulationLabTest {

    private static final int ELLINIA = 101000000;
    private static final int LITH_HARBOR = 104000000;   // a real Ellinia-cab taxi destination
    private static final int ELLINIA_CAB = 1032000;
    private static final int TAXI_RADIUS = 500;          // BotTravelManager.TAXI_TRIGGER_RADIUS_PX

    private BotTravelManager.TaxiNpcLocator prevLocator;
    private BotTravelManager.TaxiRide prevRide;
    private final List<Integer> rides = new ArrayList<>();

    @BeforeEach
    void wzPresent() {
        assumeTrue(Files.isDirectory(Path.of("wz", "Map.wz")), "wz/ not present — skipping travel lab");
        System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        BotManager.dwellInstant = true;
        prevLocator = BotTravelManager.taxiNpcLocator;
        prevRide = BotTravelManager.taxiRide;
        rides.clear();
    }

    @AfterEach
    void restore() {
        BotManager.dwellInstant = false;
        BotTravelManager.taxiNpcLocator = prevLocator;
        BotTravelManager.taxiRide = prevRide;
    }

    @Test
    void botWalksTheRealElliniaTreeToTheCabAndRides() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(ELLINIA);
        BotMovementSimulationLab lab = BotMovementSimulationLab.fromMap(map);

        Point cab = BotNavigationMapLoader.npcGroundedPosition(ELLINIA, ELLINIA_CAB);
        assertNotNull(cab, "Ellinia cab NPC position should load from WZ life data");

        Point start = arrivalPoint(map, cab);
        BotEntry entry = lab.spawnBot("cabber", 1, map, start);
        Character bot = entry.bot;

        seedTaxiHop(entry, cab, /*deadlineFromNowMs*/ 120_000L);
        stubSeams(cab);

        Outcome outcome = driveTaxiHop(entry, bot, cab, /*maxTicks*/ 600);
        System.out.printf("[travel-lab] start=%s cab=%s -> rode=%s atTick=%d bestDist=%d finalGrounded=%s%n",
                start, cab, outcome.rode, outcome.rodeTick, outcome.bestDist, outcome.finalGrounded);
        for (String line : lab.formatRecentTrace("cabber", 12)) {
            System.out.println("    " + line);
        }

        // The bot must at least reach the cab's hailing radius walking the real tree (the whole point of
        // the harness: prove the approach physics works offline). The ride fires once it does.
        assertTrue(outcome.bestDist <= TAXI_RADIUS,
                "bot should reach within the taxi radius of the real cab; bestDist=" + outcome.bestDist);
        assertTrue(outcome.rode, "bot should hail the cab and ride to Lith Harbor");
    }

    @Test
    void hailsTheCabFromHereWhenTheApproachBudgetLapses() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(ELLINIA);
        BotMovementSimulationLab lab = BotMovementSimulationLab.fromMap(map);
        Point cab = BotNavigationMapLoader.npcGroundedPosition(ELLINIA, ELLINIA_CAB);
        assertNotNull(cab);

        // Start far up the tree and lapse the budget immediately: the production fix hails the cab from
        // wherever the bot is rather than failing the errand within sight of it.
        Point start = treeTopApprox(map, cab);
        BotEntry entry = lab.spawnBot("cabber", 1, map, start);
        seedTaxiHop(entry, cab, /*deadlineFromNowMs*/ -1L); // already lapsed
        // Pre-seed bestDist to the start distance so the first tick sees no fresh progress and the
        // progress-aware deadline isn't re-pushed — i.e. simulate a bot that already burned its budget
        // without closing on the cab (the real "deadline" failure), so the lapse actually holds.
        entry.followTravelBestDist = Math.abs(start.x - cab.x) + Math.abs(start.y - cab.y);
        stubSeams(cab);

        Outcome outcome = driveTaxiHop(entry, entry.bot, cab, /*maxTicks*/ 5);
        System.out.printf("[travel-lab/hail] start=%s rode=%s atTick=%d%n", start, outcome.rode, outcome.rodeTick);
        assertTrue(outcome.rode, "lapsed-budget hop should hail the cab from here and ride");
    }

    @Test
    void jobErrandDoesNotResetTheTaxiDwellWhileTravelingToTheCab() {
        // Regression: the job errand and the taxi cab share npcDwellUntilMs. tickJobErrand used to reset it
        // on every TRAVELING tick, which zeroed the cab's 2-7s "one ticket please" dwell so it never
        // completed — the bot reached the cab grounded + in range but never paid the fare, stuck forever
        // with no give-up and nothing in console. The dwell must survive a TRAVELING tick.
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(ELLINIA);
        BotMovementSimulationLab lab = BotMovementSimulationLab.fromMap(map);
        Point cab = BotNavigationMapLoader.npcGroundedPosition(ELLINIA, ELLINIA_CAB);
        assertNotNull(cab);

        BotManager.dwellInstant = false; // exercise the real 2-7s cab dwell, not the instant-test path
        BotEntry entry = lab.spawnBot("cabber", 1, map, new Point(cab)); // stand ON the cab: in range at once
        lab.teleport("cabber", new Point(cab)); // ground the bot on the cab's foothold
        Character bot = entry.bot;
        when(bot.getMeso()).thenReturn(900_000);

        // Job errand whose instructor is a taxi hop away (Ellinia cab -> Lith Harbor), so the approach is
        // cross-map TRAVELING that routes through the cab, exactly like the real 2nd-job field instructors.
        entry.jobErrandMapId = LITH_HARBOR;
        entry.jobErrandTarget = client.Job.WARRIOR;
        entry.jobErrandNpcId = 999;

        var prevScroll = BotTravelManager.returnScrollCount;
        try {
            BotTravelManager.returnScrollCount = b -> 0;
            // Pre-seed the Ellinia->Lith Harbor taxi hop (same as the sibling cab tests) so tickTravel
            // takes the taxi-hop branch before partition routing — partition routing now runs even for
            // fully-connected maps (commit 459421c91) and would NPE on the lab's clientless mock at
            // BotTravelManager.java:337 (bot.getClient().getChannelServer().getMapFactory()).
            seedTaxiHop(entry, cab, /*deadlineFromNowMs*/ 120_000L);
            stubSeams(cab);

            // First tick seeds the taxi hop and dwells at the cab (sets npcDwellUntilMs to a fresh 2-7s window).
            BotStarterKitManager.tickJobErrand(entry, bot, true);
            long dwellAfterFirst = entry.npcDwellUntilMs;
            assertTrue(dwellAfterFirst > 0,
                    "taxi should have armed its dwell at the cab; got " + dwellAfterFirst);

            // Subsequent TRAVELING ticks must NOT zero it (the bug). Pre-fix this dropped to 0 every tick.
            for (int i = 0; i < 5; i++) {
                BotStarterKitManager.tickJobErrand(entry, bot, true);
                assertTrue(entry.npcDwellUntilMs > 0,
                        "job errand must not reset the cab dwell while TRAVELING (tick " + i + ")");
            }
        } finally {
            BotTravelManager.returnScrollCount = prevScroll;
        }
    }

    // --- harness internals -------------------------------------------------------------------------

    private void seedTaxiHop(BotEntry entry, Point cab, long deadlineFromNowMs) {
        entry.followTravelTargetMapId = LITH_HARBOR;
        entry.followTravelNextHopMapId = LITH_HARBOR;
        entry.followTravelFromMapId = ELLINIA;
        entry.followTravelTaxiNpcId = ELLINIA_CAB;
        entry.followTravelTaxiPos = new Point(cab);
        entry.followTravelBestDist = Integer.MAX_VALUE;
        entry.followTravelDeadlineMs = System.currentTimeMillis() + deadlineFromNowMs;
    }

    private void stubSeams(Point cab) {
        BotTravelManager.taxiNpcLocator = (m, npcId) -> npcId == ELLINIA_CAB ? new Point(cab) : null;
        BotTravelManager.taxiRide = (b, edge) -> {
            rides.add(edge.toMapId());
            return true;
        };
    }

    private Outcome driveTaxiHop(BotEntry entry, Character bot, Point cab, int maxTicks) {
        Outcome outcome = new Outcome();
        for (int tick = 0; tick < maxTicks && !outcome.rode; tick++) {
            BotTravelManager.tickTravel(entry, bot, LITH_HARBOR, 8, true, false);
            Point pos = bot.getPosition();
            int dist = Math.abs(pos.x - cab.x) + Math.abs(pos.y - cab.y);
            if (dist < outcome.bestDist) {
                outcome.bestDist = dist;
            }
            outcome.finalGrounded = !entry.inAir && !entry.climbing;
            if (!rides.isEmpty() && outcome.rodeTick < 0) {
                outcome.rode = true;
                outcome.rodeTick = tick;
            }
        }
        return outcome;
    }

    /** A realistic arrival point: the map's spawn portal, else just left of the cab. */
    private static Point arrivalPoint(MapleMap map, Point cab) {
        Portal sp = map.getPortal("sp");
        if (sp != null) {
            return new Point(sp.getPosition());
        }
        return new Point(cab.x - 200, cab.y);
    }

    /** Highest spawn/town portal we can find — stands in for "bot landed up in the tree". */
    private static Point treeTopApprox(MapleMap map, Point cab) {
        Point highest = null;
        for (Portal portal : map.getPortals()) {
            Point p = portal.getPosition();
            if (highest == null || p.y < highest.y) {
                highest = p;
            }
        }
        return highest != null ? new Point(highest) : new Point(cab.x, cab.y - 1500);
    }

    private static final class Outcome {
        boolean rode;
        int rodeTick = -1;
        int bestDist = Integer.MAX_VALUE;
        boolean finalGrounded;
    }
}
