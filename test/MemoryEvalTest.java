import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.evals.MemoryEvalCase;
import services.evals.MemoryEvalPaths;
import services.evals.MemoryEvalScorer;
import services.evals.MemoryEvalSuite;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JCLAW-529: scoring, and the containment guard that keeps generated suites out of the
 * repository.
 *
 * <p>Every fixture here is invented. Real suites are generated from the operator's corpus
 * and are personal data; none of it belongs in a tracked test file, which is the same
 * reason {@code evals/local/} is ignored.
 */
class MemoryEvalTest extends UnitTest {

    private static MemoryEvalSuite suite(MemoryEvalCase... cases) {
        return new MemoryEvalSuite("recall", "fixture", "3:0000000a", List.of(cases));
    }

    private static MemoryEvalCase c(String id, long... gold) {
        var g = new java.util.ArrayList<Long>();
        for (var x : gold) g.add(x);
        return new MemoryEvalCase(id, "question for " + id, g);
    }

    // --- scoring ---

    @Test
    void rankIsTheFirstGoldPositionOneBased() {
        assertEquals(1, MemoryEvalScorer.firstGoldRank(List.of(7L, 8L, 9L), List.of(7L)));
        assertEquals(3, MemoryEvalScorer.firstGoldRank(List.of(7L, 8L, 9L), List.of(9L)));
        assertEquals(0, MemoryEvalScorer.firstGoldRank(List.of(7L, 8L), List.of(99L)),
                "absent gold is rank 0, not rank 1");
    }

    @Test
    void anyGoldCounts() {
        // A corpus holding a fact twice has two correct retrievals. Scoring only the
        // source id would mark the paraphrase a miss and punish dedup for working.
        assertEquals(2, MemoryEvalScorer.firstGoldRank(List.of(5L, 6L, 7L), List.of(6L, 7L)));
    }

    @Test
    void perfectRetrievalScoresOneAcrossTheBoard() {
        var r = MemoryEvalScorer.score(suite(c("a", 1L), c("b", 2L)),
                List.of(List.of(1L, 9L), List.of(2L, 9L)));

        assertEquals(1.0, r.recallAt1(), 1e-9);
        assertEquals(1.0, r.mrr(), 1e-9);
        assertTrue(r.missed().isEmpty());
    }

    @Test
    void mrrSeparatesRankingQualityThatRecallAtKCannotSee() {
        // Both retrieve the answer within five, so recall@5 is 1.0 for each. Only MRR
        // distinguishes putting it first from putting it fourth.
        var top = MemoryEvalScorer.score(suite(c("a", 1L)), List.of(List.of(1L, 2L, 3L, 4L)));
        var deep = MemoryEvalScorer.score(suite(c("a", 1L)), List.of(List.of(2L, 3L, 4L, 1L)));

        assertEquals(top.recallAt5(), deep.recallAt5(), 1e-9);
        assertEquals(1.0, top.mrr(), 1e-9);
        assertEquals(0.25, deep.mrr(), 1e-9);
    }

    @Test
    void aRetrievalMissIsReportedSeparatelyFromABadRank() {
        var r = MemoryEvalScorer.score(suite(c("found", 1L), c("lost", 42L)),
                List.of(List.of(1L), List.of(7L, 8L)));

        assertEquals(List.of("lost"), r.missed(), "a miss is a retrieval failure, not a ranking one");
        assertEquals(0.5, r.recallAt10(), 1e-9);
        assertEquals(0.5, r.mrr(), 1e-9, "a missed case contributes zero, not a penalty");
    }

    @Test
    void mismatchedRetrievalCountIsRejectedRatherThanScoredWrong() {
        assertThrows(IllegalArgumentException.class,
                () -> MemoryEvalScorer.score(suite(c("a", 1L), c("b", 2L)), List.of(List.of(1L))));
    }

    // --- fingerprint ---

    @Test
    void fingerprintTracksWhatDecidesScoringAndIgnoresProse() {
        var base = suite(c("a", 1L));
        var reworded = new MemoryEvalSuite("recall", "totally different prose", "3:0000000a", base.cases());
        assertEquals(base.fingerprint(), reworded.fingerprint(),
                "description changes no verdict, so it must not invalidate a baseline");

        assertNotEquals(base.fingerprint(), suite(c("a", 2L)).fingerprint(),
                "changing a gold answer changes how the suite scores");
    }

    // --- the containment guard (JCLAW-529 privacy constraint) ---

    @Test
    void generatedSuitesLandInTheIgnoredLocalDirectory() {
        assertTrue(MemoryEvalPaths.suiteFile("recall").startsWith(MemoryEvalPaths.localDir()));
    }

    @Test
    void traversalOutOfTheLocalDirectoryIsRefused() {
        // The repository is mirrored publicly, so a suite written next to the tracked
        // ones would be published. The ignore rule only protects the expected location;
        // this is what stops a caller reaching a different one.
        for (var bad : List.of("../suites/recall", "../../etc/passwd", "/tmp/escape", "a/b")) {
            assertThrows(IllegalArgumentException.class, () -> MemoryEvalPaths.suiteFile(bad),
                    "should refuse: " + bad);
        }
    }

    @Test
    void onlyPlainSlugsAreAcceptedAsSuiteIds() {
        assertThrows(IllegalArgumentException.class, () -> MemoryEvalPaths.suiteFile(null));
        assertThrows(IllegalArgumentException.class, () -> MemoryEvalPaths.suiteFile(""));
        assertThrows(IllegalArgumentException.class, () -> MemoryEvalPaths.suiteFile("Recall"));
        assertThrows(IllegalArgumentException.class, () -> MemoryEvalPaths.suiteFile("recall.json"));
    }
}
