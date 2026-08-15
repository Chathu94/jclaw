import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.test.UnitTest;
import utils.LogFootprint;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Archive purge behind the Maintenance "Delete archives" control (JCLAW-1057).
 *
 * <p>Runs against a temp directory, never {@code logs/}. Exercising the real one would
 * delete this working tree's actual archives on every test run, which is both data loss
 * and a test that changes its own environment.
 */
class LogFootprintTest extends UnitTest {

    private void write(Path dir, String name, int bytes) throws Exception {
        Files.write(dir.resolve(name), new byte[bytes]);
    }

    /**
     * The whole contract in one case: archives go, everything else stays. The live file
     * is the one that matters — log4j2 holds it open, and unlinking it leaves the
     * instance writing to an inode nobody can read until the next rollover.
     */
    @Test
    void deletesArchivesAndLeavesEveryOtherFileAlone(@TempDir Path dir) throws Exception {
        write(dir, "application.log", 500);
        write(dir, "application-test.log", 40);
        write(dir, "application-2026-06-06-1.log.gz", 100);
        write(dir, "application-2026-06-07-1.log.gz", 300);
        write(dir, "autotest-883.log", 70);

        var purged = LogFootprint.purgeArchives(dir.toFile());

        assertEquals(2, purged.deleted(), "both archives should have gone");
        assertEquals(400, purged.freedBytes(), "freed bytes should total the archives removed");
        assertTrue(dir.resolve("application.log").toFile().isFile(),
                "the appender's open file must survive");
        assertTrue(dir.resolve("application-test.log").toFile().isFile(),
                "a non-archive .log is not this control's to delete");
        assertTrue(dir.resolve("autotest-883.log").toFile().isFile(),
                "an ad-hoc log is counted in the total but not deleted");
        assertFalse(dir.resolve("application-2026-06-06-1.log.gz").toFile().exists());
    }

    /** Pre-%i archives carry no index and must still be reclaimable. */
    @Test
    void deletesArchivesWrittenBeforeTheIndexedNamingLanded(@TempDir Path dir) throws Exception {
        write(dir, "application-2026-06-06.log.gz", 128);

        assertEquals(1, LogFootprint.purgeArchives(dir.toFile()).deleted(),
                "a date-only archive predates %i but is still an archive");
    }

    @Test
    void reportsNothingRemovedWhenThereAreNoArchives(@TempDir Path dir) throws Exception {
        write(dir, "application.log", 10);

        var purged = LogFootprint.purgeArchives(dir.toFile());
        assertEquals(0, purged.deleted());
        assertEquals(0, purged.freedBytes());
        assertTrue(dir.resolve("application.log").toFile().isFile());
    }

    /** A missing directory is a fresh install, not a failure. */
    @Test
    void treatsAnAbsentDirectoryAsEmpty() {
        var purged = LogFootprint.purgeArchives(new File("/nonexistent-logs-dir-jclaw-test"));
        assertEquals(0, purged.deleted());
        assertEquals(0, purged.freedBytes());
    }
}
