package services.evals;

import java.util.ArrayList;
import java.util.List;

/**
 * Scores a {@link MemoryEvalSuite} against retrieval results (JCLAW-529).
 *
 * <p>Pure: the caller supplies each case's ranked retrieval, so scoring is deterministic
 * and testable without a store, an index or a model. The retrieval itself comes from
 * {@code SystemPromptAssembler.recall} (JCLAW-937), which is the same pipeline the system
 * prompt uses — so a score describes production, not a reimplementation of it.
 */
public final class MemoryEvalScorer {

    private MemoryEvalScorer() {}

    /**
     * One case's outcome.
     *
     * @param rank 1-based position of the first gold memory in the ranked retrieval, or
     *             0 when none was retrieved at all
     */
    public record CaseResult(String caseId, int rank, int goldCount, int retrievedCount) {
        public boolean hit(int k) {
            return rank > 0 && rank <= k;
        }
    }

    /**
     * Aggregate over a suite.
     *
     * @param recallAt1  fraction of cases whose top retrieval was already correct
     * @param recallAt5  fraction answered within five
     * @param recallAt10 fraction answered within ten
     * @param mrr        mean reciprocal rank — rewards putting the answer higher, which
     *                   recall@k alone cannot see
     * @param missed     cases where no gold memory was retrieved at any depth; these are
     *                   the retrieval failures, as distinct from ranking ones
     */
    public record Report(String suiteId, String fingerprint, int cases,
                         double recallAt1, double recallAt5, double recallAt10,
                         double mrr, List<String> missed, List<CaseResult> perCase) {}

    /** Rank of the first gold id in {@code retrieved}, 1-based; 0 when absent. */
    public static int firstGoldRank(List<Long> retrieved, List<Long> gold) {
        for (int i = 0; i < retrieved.size(); i++) {
            if (gold.contains(retrieved.get(i))) return i + 1;
        }
        return 0;
    }

    /**
     * @param retrievals ranked memory ids per case, in the same order as
     *                   {@code suite.cases()} — best-first, and long enough to answer the
     *                   deepest k being reported, or recall@10 is measuring the limit
     *                   rather than the retrieval
     */
    public static Report score(MemoryEvalSuite suite, List<List<Long>> retrievals) {
        if (retrievals.size() != suite.cases().size()) {
            throw new IllegalArgumentException(
                    "Expected one retrieval per case: %d cases, %d retrievals"
                            .formatted(suite.cases().size(), retrievals.size()));
        }
        var perCase = new ArrayList<CaseResult>(suite.cases().size());
        var missed = new ArrayList<String>();
        double rrSum = 0;
        int at1 = 0;
        int at5 = 0;
        int at10 = 0;
        for (int i = 0; i < suite.cases().size(); i++) {
            var c = suite.cases().get(i);
            var retrieved = retrievals.get(i);
            int rank = firstGoldRank(retrieved, c.goldMemoryIds());
            var result = new CaseResult(c.id(), rank, c.goldMemoryIds().size(), retrieved.size());
            perCase.add(result);
            if (rank == 0) {
                missed.add(c.id());
                continue;
            }
            rrSum += 1.0 / rank;
            if (result.hit(1)) at1++;
            if (result.hit(5)) at5++;
            if (result.hit(10)) at10++;
        }
        int n = Math.max(1, suite.cases().size());
        return new Report(suite.id(), suite.fingerprint(), suite.cases().size(),
                (double) at1 / n, (double) at5 / n, (double) at10 / n, rrSum / n,
                List.copyOf(missed), List.copyOf(perCase));
    }
}
