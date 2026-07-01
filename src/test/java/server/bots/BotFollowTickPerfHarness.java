package server.bots;

import client.Character;
import client.Client;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.keybind.KeyBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.stubbing.Answer;
import server.life.Monster;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-code perf harness for the bot tick. Mirrors the {@link BotMovementSimulationLab}
 * mocking approach so it runs without a live server. Drives N follow-mode bots through
 * {@link BotManager#runCommonTickSystemsForTest} + {@link BotManager#stepMovementOnly}
 * for many ticks and prints a per-section timing report drawn from
 * {@link BotPerformanceMonitor#snapshot()}.
 *
 * <p>Two run modes:
 * <ul>
 *   <li>{@link #singleThreaded()} — sequential ticks for clean per-section profiling.</li>
 *   <li>{@link #multiThreaded()} — drives bots concurrently across worker threads
 *       to surface MapleMap lock contention similar to the live TimerManager pool.</li>
 * </ul>
 *
 * <p>Disabled by default — run manually with
 * {@code mvn test -DrunBotPerf=true -Dtest=BotFollowTickPerfHarness}.
 */
@EnabledIfSystemProperty(named = "runBotPerf", matches = "true")
public class BotFollowTickPerfHarness {

    private static final int BOT_COUNT = 5;
    private static final int WARMUP_TICKS = 500;
    private static final int MEASURE_TICKS = 10_000;
    private static final int MAP_ID = 100000000; // Henesys (small town)

    @Test
    public void singleThreaded() {
        Scenario scenario = new Scenario(BOT_COUNT, MAP_ID);
        runScenario(scenario, WARMUP_TICKS, MEASURE_TICKS, 1);
    }

    @Test
    public void multiThreaded() {
        Scenario scenario = new Scenario(BOT_COUNT, MAP_ID);
        runScenario(scenario, WARMUP_TICKS, MEASURE_TICKS, 4);
    }

    @Test
    public void heavyLoad() {
        Scenario scenario = new Scenario(20, MAP_ID);
        runScenario(scenario, 200, 5_000, 4);
    }

    private static void runScenario(Scenario scenario, int warmupTicks, int measureTicks, int workers) {
        boolean previousPerfState = BotPerformanceMonitor.enabled();
        BotPerformanceMonitor.setEnabled(true);
        try {
            // Warmup
            scenario.runTicks(warmupTicks, workers);
            BotPerformanceMonitor.reset();

            long wallStartNs = System.nanoTime();
            scenario.runTicks(measureTicks, workers);
            long wallElapsedNs = System.nanoTime() - wallStartNs;

            printReport(scenario, measureTicks, workers, wallElapsedNs);
        } finally {
            BotPerformanceMonitor.setEnabled(previousPerfState);
        }
    }

    // -----------------------------------------------------------------------
    // Scenario setup
    // -----------------------------------------------------------------------

    private static final class Scenario {
        final BotManager manager = BotManager.getInstance();
        final MapleMap map;
        final Character owner;
        final List<BotEntry> entries;

        Scenario(int botCount, int mapId) {
            ensureWzPath();
            this.map = BotNavigationMapLoader.loadMapGeometry(mapId);
            BotNavigationGraphProvider.rebuildGraph(map);
            this.owner = mockCharacter("owner", 1, map, new Point(0, -200));
            this.entries = new ArrayList<>();
            for (int i = 0; i < botCount; i++) {
                Character bot = mockCharacter("bot" + i, 1000 + i, map, new Point(30 * i, -200));
                BotEntry entry = new BotEntry(bot, owner, null);
                entry.following = true;
                entry.lastMapId = map.getId();
                entry.movementProfile = BotMovementProfile.fromCharacter(bot);
                BotPhysicsEngine.teleportTo(entry, bot, bot.getPosition());
                BotMovementManager.resetEntryStateAfterTeleport(entry);
                entries.add(entry);
            }
        }

        void runTicks(int ticks, int workers) {
            if (workers <= 1) {
                runTicksSingleThreaded(ticks);
            } else {
                runTicksMultiThreaded(ticks, workers);
            }
        }

        private void runTicksSingleThreaded(int ticks) {
            for (int t = 0; t < ticks; t++) {
                for (BotEntry entry : entries) {
                    runOneTick(entry);
                }
            }
        }

        private void runTicksMultiThreaded(int ticks, int workers) {
            ExecutorService pool = Executors.newFixedThreadPool(workers, r -> {
                Thread thread = new Thread(r, "PerfHarness-Worker");
                thread.setDaemon(true);
                return thread;
            });
            try {
                for (int t = 0; t < ticks; t++) {
                    List<java.util.concurrent.Future<?>> futures = new ArrayList<>(entries.size());
                    for (BotEntry entry : entries) {
                        futures.add(pool.submit(() -> runOneTick(entry)));
                    }
                    for (var f : futures) {
                        try { f.get(); } catch (Exception ignore) { }
                    }
                }
            } finally {
                pool.shutdownNow();
                try { pool.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignore) { }
            }
        }

        private void runOneTick(BotEntry entry) {
            Character bot = entry.bot;
            // Mirror BotManager.tick() timing wrap so "tick-total" is captured.
            long startedAt = BotPerformanceMonitor.start();
            try {
                boolean runAiTick = consumeAiTick(entry);
                entry.lastTickWasAi = runAiTick;
                entry.lastTickAtMs = System.currentTimeMillis();
                try {
                    manager.runCommonTickSystemsForTest(entry, bot, owner, runAiTick);
                } catch (Throwable t) {
                    // Swallow per-tick — some mocked methods may NPE on missing state.
                    // We still get useful timing for sections that DID execute.
                }
                try {
                    BotManager.TargetSnapshot snap = manager.captureTargetSnapshot(entry);
                    Point ownerPos = snap.rawOwnerPos();
                    entry.lastOwnerPos = new Point(ownerPos);
                    manager.stepMovementOnly(entry, snap.primaryTargetPos(), ownerPos, runAiTick);
                } catch (Throwable t) {
                    // ignore
                }
            } finally {
                BotPerformanceMonitor.recordSince("tick-total", startedAt);
            }
        }

        private static boolean consumeAiTick(BotEntry entry) {
            entry.aiTickAccumulatorMs += BotMovementManager.cfg.TICK_MS;
            if (entry.aiTickAccumulatorMs < BotManager.cfg.AI_TICK_MS) {
                return false;
            }
            entry.aiTickAccumulatorMs -= BotManager.cfg.AI_TICK_MS;
            return true;
        }
    }

    // -----------------------------------------------------------------------
    // Reporting
    // -----------------------------------------------------------------------

    private static void printReport(Scenario scenario, int ticks, int workers, long wallNs) {
        List<BotPerformanceMonitor.SectionSnapshot> snaps =
                new ArrayList<>(BotPerformanceMonitor.snapshot());
        snaps.sort(Comparator.comparingDouble(BotPerformanceMonitor.SectionSnapshot::maxMs).reversed());

        long totalCalls = snaps.stream().mapToLong(BotPerformanceMonitor.SectionSnapshot::count).sum();
        double wallMs = wallNs / 1_000_000.0;
        int botCount = scenario.entries.size();

        System.out.println();
        System.out.println("=================================================================================");
        System.out.printf("BotFollowTickPerfHarness  bots=%d ticks=%d workers=%d wall=%.1fms (%.1f tick/sec/bot)%n",
                botCount, ticks, workers, wallMs, ticks / (wallMs / 1000.0));
        System.out.printf("totalRecords=%d (instrumentation calls across all sections)%n", totalCalls);
        System.out.println("=================================================================================");
        System.out.printf("%-28s %10s %12s %12s %12s %10s %12s%n",
                "section", "n", "avgMs", "maxMs", "totalMs", "slow%", "slowAvgMs");
        System.out.println("---------------------------------------------------------------------------------");
        for (BotPerformanceMonitor.SectionSnapshot s : snaps) {
            double totalMs = s.totalNs() / 1_000_000.0;
            double slowPct = s.slowCount() * 100.0 / Math.max(1, s.count());
            System.out.printf("%-28s %10d %12.4f %12.4f %12.2f %9.2f%% %12.4f%n",
                    s.section(), s.count(), s.avgMs(), s.maxMs(), totalMs, slowPct, s.slowAvgMs());
        }
        System.out.println("=================================================================================");
        System.out.println();
    }

    // -----------------------------------------------------------------------
    // Mocking
    // -----------------------------------------------------------------------

    private static void ensureWzPath() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
    }

    private static Character mockCharacter(String name, int id, MapleMap initialMap, Point startPosition) {
        Character character = mock(Character.class);
        Client client = mock(Client.class);
        Inventory equipped = mock(Inventory.class);
        Inventory use = mock(Inventory.class);
        Inventory etc = mock(Inventory.class);
        Inventory setup = mock(Inventory.class);
        Inventory cash = mock(Inventory.class);

        AtomicReference<Point> position = new AtomicReference<>(new Point(startPosition));
        AtomicReference<MapleMap> map = new AtomicReference<>(initialMap);
        AtomicInteger hp = new AtomicInteger(500);
        AtomicInteger mp = new AtomicInteger(500);
        AtomicInteger stance = new AtomicInteger(0);

        when(character.getName()).thenReturn(name);
        when(character.getId()).thenReturn(id);
        when(character.getClient()).thenReturn(client);
        when(character.getPosition()).thenAnswer((Answer<Point>) inv -> new Point(position.get()));
        doAnswer(inv -> { position.set(new Point(inv.getArgument(0))); return null; })
                .when(character).setPosition(any(Point.class));
        when(character.getMap()).thenAnswer(inv -> map.get());
        when(character.getMapId()).thenAnswer(inv -> map.get().getId());
        when(character.getHp()).thenAnswer(inv -> hp.get());
        when(character.getCurrentMaxHp()).thenReturn(500);
        when(character.getMp()).thenAnswer(inv -> mp.get());
        when(character.getCurrentMaxMp()).thenReturn(500);
        when(character.getStance()).thenAnswer(inv -> stance.get());
        doAnswer(inv -> { stance.set(inv.getArgument(0)); return null; })
                .when(character).setStance(anyInt());
        when(character.getJob()).thenReturn(Job.BEGINNER);
        when(character.getLevel()).thenReturn(10);
        when(character.getStr()).thenReturn(20);
        when(character.getDex()).thenReturn(20);
        when(character.getInt()).thenReturn(20);
        when(character.getLuk()).thenReturn(20);
        when(character.getSkills()).thenReturn(Map.of());
        when(character.getRemainingSps()).thenReturn(new int[5]);
        when(character.getTrade()).thenReturn(null);
        when(character.getKeymap()).thenReturn(new HashMap<Integer, KeyBinding>());
        when(character.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        when(character.getInventory(InventoryType.EQUIP)).thenReturn(equipped);
        when(character.getInventory(InventoryType.USE)).thenReturn(use);
        when(character.getInventory(InventoryType.ETC)).thenReturn(etc);
        when(character.getInventory(InventoryType.SETUP)).thenReturn(setup);
        when(character.getInventory(InventoryType.CASH)).thenReturn(cash);
        when(equipped.getItem(anyShort())).thenReturn(null);
        when(equipped.iterator()).thenReturn(Collections.<client.inventory.Item>emptyIterator());
        when(use.getSlotLimit()).thenReturn((byte) 24);
        when(use.iterator()).thenReturn(Collections.<client.inventory.Item>emptyIterator());
        when(etc.iterator()).thenReturn(Collections.<client.inventory.Item>emptyIterator());
        when(setup.iterator()).thenReturn(Collections.<client.inventory.Item>emptyIterator());
        when(cash.iterator()).thenReturn(Collections.<client.inventory.Item>emptyIterator());
        when(character.getTotalMoveSpeedStat()).thenReturn(100);
        when(character.getTotalJumpStat()).thenReturn(100);
        when(character.isLoggedinWorld()).thenReturn(true);
        doAnswer(inv -> { map.set(inv.getArgument(0)); position.set(new Point(inv.getArgument(1))); return null; })
                .when(character).changeMap(any(MapleMap.class), any(Point.class));
        return character;
    }
}
