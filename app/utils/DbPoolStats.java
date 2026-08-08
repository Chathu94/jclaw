package utils;

import com.zaxxer.hikari.HikariDataSource;
import play.db.DB;

import java.util.Optional;

/**
 * JCLAW-772: point-in-time HikariCP pool occupancy. Nothing in the codebase could read
 * the pool before this, which is why the story's original acceptance criterion — "active
 * connections stay low under concurrent streams" — was not satisfiable as written.
 *
 * <p>{@code awaiting} is the number that actually signals trouble: a sustained non-zero
 * value means callers are blocking on {@code db.pool.timeout} for a connection, which is
 * what pool exhaustion looks like from the application side. {@code active} alone can sit
 * near {@code max} on a healthy busy system.
 */
public record DbPoolStats(int active, int idle, int total, int awaiting, int max) {

    /**
     * Empty when the pool is not a HikariCP one — the fork's own factory does the same
     * {@code instanceof} check, and a test harness may swap the DataSource entirely.
     * Callers render "unavailable" rather than failing.
     */
    public static Optional<DbPoolStats> snapshot() {
        if (!(DB.getDataSource() instanceof HikariDataSource hikari)) return Optional.empty();
        var pool = hikari.getHikariPoolMXBean();
        if (pool == null) return Optional.empty();
        return Optional.of(new DbPoolStats(
                pool.getActiveConnections(),
                pool.getIdleConnections(),
                pool.getTotalConnections(),
                pool.getThreadsAwaitingConnection(),
                hikari.getMaximumPoolSize()));
    }
}
