package jobs;

import mcp.McpGrantKeyMigration;
import play.Play;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.EventLogger;

import java.sql.SQLException;

/**
 * JCLAW-983: re-key existing per-agent MCP grants by server id at
 * {@code @OnApplicationStart}. All logic lives in {@link McpGrantKeyMigration#run()};
 * this class is the thin boot hook that skips test mode and lets a test drive the
 * migration directly.
 *
 * <p>{@code @NoTransaction} because the migration relaxes a NOT NULL on its own raw
 * connection before opening a transaction for the backfill, mirroring
 * {@link CascadeFkMigrationJob}. Order against {@link McpStartupJob} does not matter: a
 * row that has not been converted yet still carries the name it was keyed by, and
 * {@code AgentToolConfig.handle()} reads that name when no server id is set.
 */
@OnApplicationStart
@NoTransaction
public class McpGrantKeyMigrationJob extends Job<Void> {

    @Override
    public void doJob() {
        if (Play.runningInTestMode()) {
            return;
        }
        try {
            McpGrantKeyMigration.run();
        } catch (SQLException e) {
            EventLogger.error("system", "MCP grant re-key migration failed: " + e.getMessage());
            throw new IllegalStateException("MCP grant re-key migration failed", e);
        }
    }
}
