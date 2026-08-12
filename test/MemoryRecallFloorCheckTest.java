import jobs.MemoryRecallFloorCheckJob;
import memory.MemoryStore;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.List;

/**
 * JCLAW-942: the recall floor is compared against a cosine scale that belongs to the
 * embedding model, so a floor swept for one model rejects every vector hit under another
 * and recall silently degrades to keyword-only. These pin the diagnosis, because the job
 * that carries it is skipped in test mode.
 */
class MemoryRecallFloorCheckTest extends UnitTest {

    /** Only {@code bestQueryCosine} matters here; everything else is unreachable. */
    private static MemoryStore storeReturning(double cosine) {
        return new MemoryStore() {
            @Override
            public double bestQueryCosine(String agentId, String query) {
                return cosine;
            }

            @Override
            public String store(String a, String t, String c, double i) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<MemoryEntry> search(String a, String q, int l) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void delete(String id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<MemoryEntry> list(String a) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int deleteAll(String a) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void aFloorAboveTheModelsScaleIsReported() {
        // The shipped 0.60 default against an arctic-embed corpus that tops out near 0.35.
        var problem = MemoryRecallFloorCheckJob.diagnose(
                storeReturning(0.34), "1", "The user's flight is at 08:00", 0.60);

        assertNotNull(problem, "a floor no stored memory can reach must be reported");
        assertTrue(problem.contains("0.340"), "the observed cosine is what makes it diagnosable");
        assertTrue(problem.contains("0.600"), "and the floor it failed against");
    }

    @Test
    void aCorrectlySweptFloorIsSilent() {
        assertNull(MemoryRecallFloorCheckJob.diagnose(
                storeReturning(0.34), "1", "The user's flight is at 08:00", 0.26),
                "0.26 is the swept floor for this scale — nothing to report");
    }

    @Test
    void anUnavailableVectorLegIsNotAFloorProblem() {
        // NaN means disabled, no provider or empty index. Reporting it as a misconfigured
        // floor would send the operator to re-sweep a threshold that is not the cause.
        assertNull(MemoryRecallFloorCheckJob.diagnose(
                storeReturning(Double.NaN), "1", "The user's flight is at 08:00", 0.60));
    }
}
