package server.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically scans the JVM for deadlocks and logs the full stack traces of the
 * threads involved, so the actual lock cycle can be found and the ordering fixed.
 *
 * Detection + diagnostics ONLY — it deliberately does NOT try to "resolve" deadlocks
 * by force-releasing locks. A {@link java.util.concurrent.locks.ReentrantLock} can only
 * be unlocked by its owning thread; forcibly clearing a held lock would admit multiple
 * threads into the critical section the lock protects and corrupt shared state, which is
 * strictly worse than the deadlock. The cure is correct lock ordering at the site the
 * dump below points to.
 */
public final class DeadlockMonitor {
    private static final Logger log = LoggerFactory.getLogger(DeadlockMonitor.class);
    private static final DeadlockMonitor instance = new DeadlockMonitor();

    private static final long CHECK_INTERVAL_SECONDS = 30;

    private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    private ScheduledExecutorService executor;

    private DeadlockMonitor() {}

    public static DeadlockMonitor getInstance() {
        return instance;
    }

    public synchronized void start() {
        if (executor != null && !executor.isShutdown()) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DeadlockMonitor");
            t.setDaemon(true);
            return t;
        });
        // Wrap the task so an unexpected throw can never cancel the recurring schedule.
        executor.scheduleWithFixedDelay(SafeRunnable.wrap(this::check, "deadlock-check"),
                CHECK_INTERVAL_SECONDS, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("Deadlock monitor started ({}s interval)", CHECK_INTERVAL_SECONDS);
    }

    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /** Scan once; logs a full dump and returns true if a deadlock is present. */
    public boolean check() {
        long[] ids = threadMXBean.findDeadlockedThreads();
        if (ids == null || ids.length == 0) {
            return false;
        }

        ThreadInfo[] infos = threadMXBean.getThreadInfo(ids, true, true);
        StringBuilder sb = new StringBuilder(1024);
        sb.append("DEADLOCK DETECTED — ").append(ids.length)
          .append(" thread(s) stuck. This will not self-resolve; fix the lock ordering at the cycle below.\n");
        for (ThreadInfo info : infos) {
            if (info == null) {
                continue;
            }
            sb.append('\n').append(info.getThreadName())
              .append(" (id=").append(info.getThreadId()).append(") state=").append(info.getThreadState());
            if (info.getLockName() != null) {
                sb.append("\n  waiting on: ").append(info.getLockName());
            }
            if (info.getLockOwnerName() != null) {
                sb.append("\n  held by: ").append(info.getLockOwnerName())
                  .append(" (id=").append(info.getLockOwnerId()).append(')');
            }
            for (StackTraceElement ste : info.getStackTrace()) {
                sb.append("\n    at ").append(ste);
            }
            sb.append('\n');
        }
        log.error(sb.toString());
        return true;
    }
}
