import jobs.EventLogCleanupJob;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/**
 * JCLAW-748: {@code jclaw.logs.retention.days} is operator-editable, and the
 * bare {@code Integer.parseInt} it used to feed threw out of every 24h tick on
 * a typo — silently stopping event-log retention for good.
 */
class EventLogCleanupJobTest extends UnitTest {

    @Test
    void nonNumericRetentionFallsBackToDefault() {
        int days = assertDoesNotThrow(() -> EventLogCleanupJob.resolveRetentionDays("thirty"),
                "a non-numeric retention value must not throw out of the daily sweep");
        assertEquals(30, days, "the fallback must be the 30-day default");
    }

    @Test
    void absentOrBlankRetentionFallsBackToDefault() {
        assertEquals(30, EventLogCleanupJob.resolveRetentionDays(null),
                "an unset key must use the default");
        assertEquals(30, EventLogCleanupJob.resolveRetentionDays("  "),
                "a blank value must use the default");
    }

    @Test
    void validRetentionIsHonoured() {
        assertEquals(7, EventLogCleanupJob.resolveRetentionDays("7"),
                "a valid value must be parsed as before");
        assertEquals(90, EventLogCleanupJob.resolveRetentionDays(" 90 "),
                "surrounding whitespace must not defeat a valid value");
    }
}
