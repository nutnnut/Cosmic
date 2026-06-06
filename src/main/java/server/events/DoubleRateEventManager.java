/*
    This file is part of the LumenMS / CosmicMS server.

    Manages the server-wide randomized 2x EXP / 2x Drop event.
    - Schedules two random hour-long fires per day (one in the morning window,
      one in the afternoon/evening window) so the two never overlap.
    - At each midnight, fresh random times are re-rolled for the next day.
    - GM commands may trigger the event manually with a custom duration.
    - Concurrent starts are rejected gracefully (no extension, no double-stack).
*/
package server.events;

import net.server.Server;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import tools.PacketCreator;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class DoubleRateEventManager {
    private static final Logger log = LoggerFactory.getLogger(DoubleRateEventManager.class);
    private static final DoubleRateEventManager instance = new DoubleRateEventManager();

    public static final long DEFAULT_DURATION_MS = TimeUnit.HOURS.toMillis(1);
    public static final int RATE_MULTIPLIER = 2;

    // PacketCreator.serverNotice types: 5 = pink text (toast-style top-of-chat),
    // 6 = light-blue [Notice] (standard server chat notification).
    private static final int CHAT_NOTICE_TYPE = 6;
    private static final int TOAST_NOTICE_TYPE = 5;

    private final Random random = new Random();

    private boolean active = false;
    private long endsAtMillis = 0L;
    private final Map<Integer, int[]> savedRates = new HashMap<>(); // worldId -> [exp, drop]
    private ScheduledFuture<?> endTaskHandle;

    private DoubleRateEventManager() {
    }

    public static DoubleRateEventManager getInstance() {
        return instance;
    }

    public synchronized boolean isActive() {
        return active;
    }

    public synchronized long getEndsAtMillis() {
        return endsAtMillis;
    }

    /** Bootstraps the randomized twice-daily schedule. Call once at server start. */
    public void bootstrap() {
        synchronized (this) {
            scheduleDailyRandomEvents();
        }
        // Re-roll a fresh pair of times every midnight.
        long timeUntilMidnight = millisUntilMidnight();
        TimerManager.getInstance().register(() -> {
            synchronized (DoubleRateEventManager.this) {
                scheduleDailyRandomEvents();
            }
        }, TimeUnit.DAYS.toMillis(1), timeUntilMidnight);
        log.info("DoubleRateEventManager bootstrapped; midnight re-roll in {} ms", timeUntilMidnight);
    }

    private void scheduleDailyRandomEvents() {
        long now = System.currentTimeMillis();
        // Two non-overlapping random hour-long slots.
        // Morning window: start hour in [0, 10] (latest finish: 11:59).
        // Evening window: start hour in [12, 22] (latest finish: 23:59).
        long firstStart = randomStartToday(0, 11);
        long secondStart = randomStartToday(12, 23);

        if (firstStart > now) {
            TimerManager.getInstance().scheduleAtTimestamp(this::triggerScheduled, firstStart);
            log.info("Scheduled randomized double-rate event #1 at epoch {} ({} ms from now)",
                    firstStart, firstStart - now);
        }
        if (secondStart > now) {
            TimerManager.getInstance().scheduleAtTimestamp(this::triggerScheduled, secondStart);
            log.info("Scheduled randomized double-rate event #2 at epoch {} ({} ms from now)",
                    secondStart, secondStart - now);
        }
    }

    private long randomStartToday(int minHourInclusive, int maxHourExclusive) {
        Calendar cal = Calendar.getInstance();
        int hour = minHourInclusive + random.nextInt(maxHourExclusive - minHourInclusive);
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, random.nextInt(60));
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private long millisUntilMidnight() {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return Math.max(0L, cal.getTimeInMillis() - System.currentTimeMillis());
    }

    private void triggerScheduled() {
        if (!start(DEFAULT_DURATION_MS, "scheduled")) {
            log.info("Skipped scheduled double-rate event: another event is already active.");
        }
    }

    /**
     * Starts a 2x EXP / 2x drop event server-wide for the given duration.
     * @return false if an event is already active (no-op), true if started.
     */
    public synchronized boolean start(long durationMs, String trigger) {
        if (active) {
            return false;
        }
        active = true;
        endsAtMillis = System.currentTimeMillis() + durationMs;

        for (World world : Server.getInstance().getWorlds()) {
            savedRates.put(world.getId(), new int[]{world.getExpRate(), world.getDropRate()});
            world.setExpRate(world.getExpRate() * RATE_MULTIPLIER);
            world.setDropRate(world.getDropRate() * RATE_MULTIPLIER);
        }

        broadcastStart(durationMs);
        endTaskHandle = TimerManager.getInstance().schedule(this::endFromTimer, durationMs);
        log.info("Double-rate event started (trigger={}), ends in {} ms", trigger, durationMs);
        return true;
    }

    private void endFromTimer() {
        synchronized (this) {
            if (!active) return;
            stopInternal(true);
        }
    }

    /**
     * Force-ends an active event (e.g. via GM command).
     * @return false if no event is currently active.
     */
    public synchronized boolean stop() {
        if (!active) return false;
        if (endTaskHandle != null) {
            endTaskHandle.cancel(false);
            endTaskHandle = null;
        }
        stopInternal(false);
        return true;
    }

    private void stopInternal(boolean fromTimer) {
        for (World world : Server.getInstance().getWorlds()) {
            int[] rates = savedRates.get(world.getId());
            if (rates != null) {
                world.setExpRate(rates[0]);
                world.setDropRate(rates[1]);
            }
        }
        savedRates.clear();
        active = false;
        endsAtMillis = 0L;
        endTaskHandle = null;
        broadcastEnd();
        log.info("Double-rate event ended (source={}).", fromTimer ? "timer" : "manual");
    }

    private void broadcastStart(long durationMs) {
        long minutes = durationMs / 60_000L;
        String chat = "[Lumen Event] 2x EXP and 2x Drop rates are now active for " + minutes
                + " minutes! Enjoy!";
        String toast = "Double EXP & Drop event has started! (" + minutes + "m)";
        for (World world : Server.getInstance().getWorlds()) {
            world.broadcastPacket(PacketCreator.serverNotice(CHAT_NOTICE_TYPE, chat));
            world.broadcastPacket(PacketCreator.serverNotice(TOAST_NOTICE_TYPE, toast));
        }
    }

    private void broadcastEnd() {
        String chat = "[Lumen Event] 2x EXP and 2x Drop event has ended. Rates restored to normal.";
        String toast = "Double EXP & Drop event has ended.";
        for (World world : Server.getInstance().getWorlds()) {
            world.broadcastPacket(PacketCreator.serverNotice(CHAT_NOTICE_TYPE, chat));
            world.broadcastPacket(PacketCreator.serverNotice(TOAST_NOTICE_TYPE, toast));
        }
    }
}
