package tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientException;

/**
 * Retries a self-contained DB operation on TRANSIENT failures (MySQL deadlock victim
 * 1213 / lock-wait-timeout 1205 / transient connection errors) with linear backoff.
 *
 * The operation MUST be safe to re-run: a single self-contained unit (one statement, or a
 * transaction that fully rolls back on failure). Do NOT wrap multi-step logic that may
 * partially commit — retrying it would double-apply. On exhausted retries the supplied
 * default is returned (the error is logged), so callers degrade gracefully instead of
 * propagating the SQLException.
 */
public final class DatabaseRetryHelper {
    private static final Logger log = LoggerFactory.getLogger(DatabaseRetryHelper.class);
    private static final int MAX_RETRIES = 3;
    private static final long BASE_BACKOFF_MS = 100;

    private DatabaseRetryHelper() {}

    /** A DB operation that may throw SQLException (so the retry layer can see and classify it). */
    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection con) throws SQLException;
    }

    public static <T> T executeWithRetry(SqlFunction<T> operation, T defaultValue) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try (Connection con = DatabaseConnection.getConnection()) {
                return operation.apply(con);
            } catch (SQLException e) {
                if (!isRetryable(e) || attempt == MAX_RETRIES - 1) {
                    log.error("DB operation failed (attempt {}/{}), giving up", attempt + 1, MAX_RETRIES, e);
                    return defaultValue;
                }
                log.warn("Retryable DB error (attempt {}/{}): {} — retrying", attempt + 1, MAX_RETRIES, e.getMessage());
                sleep(BASE_BACKOFF_MS * (attempt + 1));
            }
        }
        return defaultValue;
    }

    static boolean isRetryable(SQLException e) {
        if (e instanceof SQLTransientException) {
            return true;
        }
        int code = e.getErrorCode();
        if (code == 1213 || code == 1205) {   // ER_LOCK_DEADLOCK / ER_LOCK_WAIT_TIMEOUT
            return true;
        }
        String state = e.getSQLState();
        // 40001 = serialization failure (deadlock); 08xxx = connection exceptions.
        return state != null && (state.equals("40001") || state.startsWith("08"));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
