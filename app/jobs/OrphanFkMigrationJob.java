package jobs;

import play.Play;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.EventLogger;

import java.sql.SQLException;

/**
 * JCLAW-984: constrain the parent references that shipped as plain id columns, at
 * {@code @OnApplicationStart}. All logic lives in {@link OrphanFkMigrator#run()}; this class
 * is the thin boot hook that skips test mode and lets a test drive the migrator directly.
 *
 * <p>{@code @NoTransaction} because the DDL must not run inside a JPA transaction — the
 * migrator manages its own raw connection and commits it, mirroring
 * {@link CascadeFkMigrationJob}. Order against that job does not matter: this one only ever
 * creates a foreign key that is absent, and that one only ever alters one that is present.
 */
@OnApplicationStart
@NoTransaction
public class OrphanFkMigrationJob extends Job<Void> {

    @Override
    public void doJob() {
        if (Play.runningInTestMode()) {
            return;
        }
        try {
            OrphanFkMigrator.run();
        } catch (SQLException e) {
            EventLogger.error("system", "Orphan FK migration failed: " + e.getMessage());
            throw new IllegalStateException("Orphan FK migration failed", e);
        }
    }
}
