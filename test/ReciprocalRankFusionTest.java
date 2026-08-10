import memory.JpaMemoryStore;
import memory.ReciprocalRankFusion;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.List;

/**
 * JCLAW-555: pure unit coverage for the shared RRF helper. The fusion contract
 * (rank-based, top-normalized) is what both vector backends rely on — Lucene FTS + KNN
 * here, ts_rank + pgvector on Postgres (JCLAW-527). Memory recall overrides k; JCLAW-938
 * and {@code JpaMemoryStore.DEFAULT_RECALL_RRF_K} say why.
 */
class ReciprocalRankFusionTest extends UnitTest {

    @Test
    void idInBothListsOutranksSingleListIds() {
        // 2 is rank-2 in list A and rank-1 in list B: 1/62 + 1/61.
        // 1 is rank-1 in A only: 1/61. 3 is rank-2 in B only: 1/62.
        var fused = ReciprocalRankFusion.fuse(ReciprocalRankFusion.DEFAULT_K,
                List.of(1L, 2L), List.of(2L, 3L));
        assertEquals(3, fused.size());
        assertEquals(2L, fused.get(0).id(), "the id present in both lists must fuse to the top");
        assertEquals(1L, fused.get(1).id(), "rank-1-in-one-list beats rank-2-in-one-list");
        assertEquals(3L, fused.get(2).id());
    }

    @Test
    void scoresAreTopNormalized() {
        var fused = ReciprocalRankFusion.fuse(ReciprocalRankFusion.DEFAULT_K,
                List.of(1L, 2L), List.of(2L, 3L));
        assertEquals(1.0, fused.get(0).score(), 1e-9, "top hit must normalize to 1.0");
        // score(1) / score(2) = (1/61) / (1/61 + 1/62)
        double expected = (1.0 / 61) / (1.0 / 61 + 1.0 / 62);
        assertEquals(expected, fused.get(1).score(), 1e-9);
        assertTrue(fused.get(2).score() < fused.get(1).score());
    }

    @Test
    void negativeKCannotProduceNonFiniteScores() {
        // JCLAW-970: rrfK is operator-writable through POST /api/config. At k = -1 the i = 0
        // term was 1.0/0 = Infinity, and normalizing against it gave the top hit
        // Infinity/Infinity = NaN and every other hit 0.0 — NaN then sorts largest in
        // rankRecall, pinning one memory to the top of every recall for good.
        var fused = ReciprocalRankFusion.fuse(-1, List.of(1L, 2L), List.of(2L, 3L));

        assertEquals(3, fused.size());
        for (var r : fused) {
            assertTrue(Double.isFinite(r.score()),
                    "score must stay finite for a negative k, got " + r.score() + " for id " + r.id());
        }
        assertEquals(2L, fused.getFirst().id(), "the id in both lists must still fuse to the top");
    }

    @Test
    void emptyInputYieldsEmptyOutput() {
        assertTrue(ReciprocalRankFusion.fuse(ReciprocalRankFusion.DEFAULT_K, List.of(), List.of()).isEmpty());
    }

    @Test
    void singleListPreservesOrder() {
        var fused = ReciprocalRankFusion.fuse(ReciprocalRankFusion.DEFAULT_K, List.of(7L, 5L, 9L));
        assertEquals(List.of(7L, 5L, 9L), fused.stream().map(ReciprocalRankFusion.Ranked::id).toList());
    }

    @Test
    void equalScoresTieBreakDeterministicallyById() {
        // 4 and 2 each appear once at rank 1 of their own list — identical score.
        var fused = ReciprocalRankFusion.fuse(ReciprocalRankFusion.DEFAULT_K, List.of(4L), List.of(2L));
        assertEquals(2L, fused.get(0).id(), "ties order by ascending id for stable output");
        assertEquals(4L, fused.get(1).id());
    }

    @Test
    void kDecidesHowMuchRelevanceSpreadReachesTheBlend() {
        // JCLAW-938. Memory recall feeds these normalized scores into
        // (0.7 x relevance) + (0.3 x importance) as a magnitude. At the textbook k=60 the
        // top two hits differ by under 2%, so relevance arrives flat and importance —
        // which spans 0.2 to 0.9 on a real corpus — decides the order instead. The
        // recall-tuned k has to leave a gap the blend can actually see.
        var oneLeg = List.of(10L, 20L, 30L);
        double gapAt60 = gapBetweenTopTwo(ReciprocalRankFusion.DEFAULT_K, oneLeg);
        double gapAt5 = gapBetweenTopTwo(JpaMemoryStore.DEFAULT_RECALL_RRF_K, oneLeg);

        assertTrue(gapAt60 < 0.02,
                "k=60 compresses relevance to near-constant, which is the defect: " + gapAt60);
        assertTrue(gapAt5 > 5 * gapAt60,
                "the recall k must spread relevance materially more than k=60 does: "
                        + gapAt5 + " vs " + gapAt60);
    }

    private static double gapBetweenTopTwo(int k, List<Long> ids) {
        var fused = ReciprocalRankFusion.fuse(k, ids);
        return fused.get(0).score() - fused.get(1).score();
    }
}
