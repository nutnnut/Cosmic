package tools;

import java.util.concurrent.locks.Lock;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Helpers for acquiring locks with a bounded timeout, so a deadlock or pathological
 * contention degrades into a logged skip at the call site instead of a permanently
 * parked thread. This is deadlock AVOIDANCE (bounded waits) — not forced release.
 */
public final class LockTimeouts {
    private LockTimeouts() {}

    /**
     * Try to acquire every lock, in the given order, within one total deadline. On success
     * ALL locks are held and the caller MUST release them (in reverse order). On failure
     * (timeout or interrupt) any partially-acquired locks are released and {@code false} is
     * returned — the caller then holds nothing and must not enter its unlock block.
     *
     * Reentrant-safe: re-acquiring a lock the current thread already holds succeeds
     * immediately, and the matching release on failure only undoes this call's acquisitions.
     */
    public static boolean tryLockAll(long totalTimeoutMs, Lock... locks) {
        long deadline = System.nanoTime() + MILLISECONDS.toNanos(totalTimeoutMs);
        int acquired = 0;
        try {
            for (Lock lock : locks) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0 || !lock.tryLock(remaining, NANOSECONDS)) {
                    releaseBack(locks, acquired);
                    return false;
                }
                acquired++;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            releaseBack(locks, acquired);
            return false;
        }
    }

    private static void releaseBack(Lock[] locks, int count) {
        for (int i = count - 1; i >= 0; i--) {
            locks[i].unlock();
        }
    }
}
