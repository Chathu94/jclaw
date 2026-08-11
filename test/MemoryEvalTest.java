import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.evals.MemoryEvalCase;
import services.evals.MemoryEvalPaths;
import services.evals.MemoryEvalScorer;
import services.evals.MemoryEvalSuite;

import java.util.List;

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

    /** A single-fact case: one gold group holding every memory that states that fact. */
    private static MemoryEvalCase c(String id, long... gold) {
        var g = new java.util.ArrayList<Long>();
        for (var x : gold) g.add(x);
        return new MemoryEvalCase(id, "question for " + id, List.of(g));
    }

    /** A coverage case: one group per distinct fact the question needs. */
    private static MemoryEvalCase cov(String id, List<List<Long>> groups) {
        return new MemoryEvalCase(id, "broad question for " + id, groups);
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

    // --- coverage (JCLAW-529: what recall structurally cannot see) ---

    @Test
    void coverageSeesTheDifferenceRecallCannot() {
        // The whole reason coverage exists. Both retrievals put a gold memory first, so
        // recall and MRR are identical and perfect for each. Only one of them actually
        // answers a question that needs three facts.
        var suite = suite(cov("a", List.of(List.of(1L), List.of(2L), List.of(3L))));
        var duplicates = MemoryEvalScorer.score(suite, List.of(List.of(1L, 91L, 92L)));
        var distinct = MemoryEvalScorer.score(suite, List.of(List.of(1L, 2L, 3L)));

        assertEquals(duplicates.recallAt1(), distinct.recallAt1(), 1e-9);
        assertEquals(duplicates.mrr(), distinct.mrr(), 1e-9);

        assertEquals(1.0 / 3, duplicates.coverageAtK(), 1e-9);
        assertEquals(1.0, distinct.coverageAtK(), 1e-9);
    }

    @Test
    void aParaphraseCoversItsFactOnceRatherThanTwice() {
        // Memories 1 and 2 state the same fact. Retrieving both must not score as
        // covering two facts, or a corpus that repeats itself would look well-covered
        // while answering half the question.
        var suite = suite(cov("a", List.of(List.of(1L, 2L), List.of(3L))));
        var r = MemoryEvalScorer.score(suite, List.of(List.of(1L, 2L)));

        assertEquals(1, r.perCase().getFirst().groupsCovered());
        assertEquals(0.5, r.coverageAtK(), 1e-9);
    }

    @Test
    void groupsCoveredCountsGroupsNotMemories() {
        assertEquals(2, MemoryEvalScorer.groupsCovered(
                List.of(5L, 6L, 7L), List.of(List.of(5L, 6L), List.of(7L), List.of(8L))));
        assertEquals(0, MemoryEvalScorer.groupsCovered(List.of(1L), List.of(List.of(2L))));
    }

    @Test
    void meanGoldGroupsReportsHowDemandingTheSuiteIs() {
        // Without this a coverage number is unreadable: 0.5 across two-fact questions and
        // 0.5 across ten-fact questions describe very different retrievals.
        var r = MemoryEvalScorer.score(
                suite(cov("a", List.of(List.of(1L), List.of(2L))),
                        cov("b", List.of(List.of(3L), List.of(4L), List.of(5L), List.of(6L)))),
                List.of(List.of(1L), List.of(3L)));

        assertEquals(3.0, r.meanGoldGroups(), 1e-9);
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

        // Same ids, different grouping: one fact stated twice versus two facts. Coverage
        // reads those differently, so a baseline taken under one must not appear to apply
        // to the other.
        assertNotEquals(
                suite(cov("a", List.of(List.of(1L, 2L)))).fingerprint(),
                suite(cov("a", List.of(List.of(1L), List.of(2L)))).fingerprint());
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

    // --- case shapes (JCLAW-943) ---

    private static MemoryEvalCase shaped(String id, String shape, long gold) {
        return new MemoryEvalCase(id, "question for " + id, shape, List.of(List.of(gold)));
    }

    @Test
    void anUnstampedCaseReadsAsSingle() {
        // Suites written before shapes existed have no shape field, so gson leaves it null.
        // Without the normalization they would group under a null shape and vanish from the
        // breakdown rather than counting as what they are.
        assertEquals(MemoryEvalCase.SHAPE_SINGLE, new MemoryEvalCase("a", "q", List.of()).shape());
        assertEquals(MemoryEvalCase.SHAPE_SINGLE, new MemoryEvalCase("a", "q", null, List.of()).shape());
        assertEquals(MemoryEvalCase.SHAPE_SINGLE, new MemoryEvalCase("a", "q", "  ", List.of()).shape());
    }

    @Test
    void theBreakdownSplitsByShapeAndReconcilesWithTheAggregate() {
        var s = suite(shaped("s1", MemoryEvalCase.SHAPE_SINGLE, 1L),
                shaped("s2", MemoryEvalCase.SHAPE_SINGLE, 2L),
                shaped("t1", MemoryEvalCase.SHAPE_TEMPORAL, 3L),
                shaped("h1", MemoryEvalCase.SHAPE_MULTIHOP, 4L));
        // singles both rank 1; temporal ranks 2; multihop misses entirely
        var report = MemoryEvalScorer.score(s, List.of(
                List.of(1L), List.of(2L), List.of(99L, 3L), List.of(98L)));

        var byShape = report.byShape().stream()
                .collect(java.util.stream.Collectors.toMap(
                        MemoryEvalScorer.ShapeReport::shape, r -> r));
        assertEquals(3, byShape.size());
        assertEquals(1.0, byShape.get(MemoryEvalCase.SHAPE_SINGLE).recallAt1(), 1e-9);
        assertEquals(0.0, byShape.get(MemoryEvalCase.SHAPE_TEMPORAL).recallAt1(), 1e-9);
        assertEquals(1.0, byShape.get(MemoryEvalCase.SHAPE_TEMPORAL).recallAt5(), 1e-9);
        assertEquals(1, byShape.get(MemoryEvalCase.SHAPE_MULTIHOP).missed());

        // The whole point of the breakdown: 0.5 aggregate recall@1 hides that one shape is
        // perfect and another is zero.
        assertEquals(0.5, report.recallAt1(), 1e-9);
        int casesAcrossShapes = report.byShape().stream()
                .mapToInt(MemoryEvalScorer.ShapeReport::cases).sum();
        assertEquals(report.cases(), casesAcrossShapes, "every case must appear in exactly one shape");
        assertEquals(report.missed().size(), report.byShape().stream()
                .mapToInt(MemoryEvalScorer.ShapeReport::missed).sum());
    }

    @Test
    void aShapedSuiteRefusesComparisonWithAnUnshapedBaseline() {
        // AC4. Same ids, same queries, same golds - only the shape differs, and that is
        // enough to make the two suites measure different things.
        var before = suite(shaped("c1", MemoryEvalCase.SHAPE_SINGLE, 1L));
        var after = suite(shaped("c1", MemoryEvalCase.SHAPE_TEMPORAL, 1L));
        assertNotEquals(before.fingerprint(), after.fingerprint());
    }

    @Test
    void aSuiteOfOnlySingleCasesKeepsItsOldFingerprint() {
        // The other half of AC4, and the reason shape is folded in conditionally: nothing
        // about a pre-existing single-fact suite moved, so an existing baseline must stay
        // valid rather than being invalidated by a field that carries no information for it.
        var stamped = suite(shaped("c1", MemoryEvalCase.SHAPE_SINGLE, 1L));
        var unstamped = suite(new MemoryEvalCase("c1", "question for c1", List.of(List.of(1L))));
        assertEquals(unstamped.fingerprint(), stamped.fingerprint());
    }
}
