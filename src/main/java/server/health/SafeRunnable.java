package server.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a Runnable so an uncaught Throwable is logged instead of propagating.
 *
 * Critical for recurring scheduled tasks: {@code ScheduledExecutorService}'s
 * {@code scheduleAtFixedRate} / {@code scheduleWithFixedDelay} silently CANCEL a task
 * forever the first time it throws. Wrapping keeps the schedule alive — the "self-healing
 * timer" pattern.
 *
 * Note: the main game timer ({@code server.TimerManager}) already wraps its tasks this way
 * via its own LoggingSaveRunnable. Use this for ad-hoc {@code ScheduledExecutorService}
 * usages that don't.
 */
public final class SafeRunnable implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(SafeRunnable.class);

    private final Runnable delegate;
    private final String name;

    private SafeRunnable(Runnable delegate, String name) {
        this.delegate = delegate;
        this.name = name;
    }

    public static Runnable wrap(Runnable delegate, String name) {
        return new SafeRunnable(delegate, name);
    }

    @Override
    public void run() {
        try {
            delegate.run();
        } catch (Throwable t) {
            log.error("Scheduled task '{}' threw; schedule kept alive", name, t);
        }
    }
}
