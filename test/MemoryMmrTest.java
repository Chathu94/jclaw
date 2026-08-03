import memory.MemoryMmr;
import memory.MemorySimilarity;
import memory.MemoryStore.MemoryEntry;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JCLAW-923. The fixture is the shape that motivated the story: a live store holding the
 * same movie preference three times, where pure relevance ranking spends most of the
 * recall budget restating one fact.
 */
class MemoryMmrTest extends UnitTest {

    private static final String GENRES_A = "The user enjoys action, sci-fi, and thriller movies.";
    private static final String GENRES_B = "The user enjoys Action, Sci-Fi, and Thriller genres.";
    private static final String GENRES_C = "Tarun's preferred movie genres are Action, Sci-Fi, and Thriller.";
    private static final String NAS = "The user has a NAS at home with IP address 192.168.0.50.";
    private static final String RADARR = "The user accesses Radarr at radarr.abundent.com, not localhost.";

    private static MemoryEntry e(String id, String text) {
        return new MemoryEntry(id, "1", text, "fact", 0.5, Instant.now());
    }

    /** Descending scores, so the incoming order is already relevance-ranked. */
    private static final List<MemoryEntry> CANDIDATES = List.of(
            e("1", GENRES_A), e("2", GENRES_B), e("3", GENRES_C), e("4", NAS), e("5", RADARR));

    /**
     * A tight spread, because that is what real recall produces: the blend of a
     * top-normalised relevance, importance and a floored decay lands everything that
     * matched a query close together. The lambda sweep over the live corpus measured the
     * same shape — which is why diversity changes the selected set there rather than only
     * its order.
     */
    private static final Map<String, Double> SCORES =
            Map.of("1", 0.90, "2", 0.88, "3", 0.86, "4", 0.82, "5", 0.80);

    private static final List<String> GENRE_IDS = List.of("1", "2", "3");

    private static List<String> ids(List<MemoryEntry> got) {
        return got.stream().map(MemoryEntry::id).toList();
    }

    private static List<MemoryEntry> select(double lambda, int limit) {
        return MemoryMmr.select(CANDIDATES, x -> SCORES.get(x.id()), lambda, limit);
    }

    @Test
    void lambdaOneReproducesPureRelevanceOrdering() {
        // The AC that keeps this additive: with diversity disabled the block is byte-for-byte
        // what it was before the feature existed.
        assertEquals(List.of("1", "2", "3"), ids(select(1.0, 3)));
    }

    /** Mean pairwise similarity within a selection — the redundancy MMR exists to reduce. */
    private static double meanPairSimilarity(List<MemoryEntry> got) {
        double sum = 0;
        int pairs = 0;
        for (int i = 0; i < got.size(); i++) {
            for (int j = i + 1; j < got.size(); j++) {
                sum += MemorySimilarity.jaccard(
                        MemorySimilarity.tokenize(got.get(i).text()),
                        MemorySimilarity.tokenize(got.get(j).text()));
                pairs++;
            }
        }
        return pairs == 0 ? 0 : sum / pairs;
    }

    @Test
    void diversityLowersRedundancyWithinTheSelection() {
        // MMR's actual promise, asserted directly rather than through a hand-picked
        // expected list: for the same candidates and budget, the chosen set repeats
        // itself less. Measured the same way as the lambda sweep over the live corpus,
        // which put the default at 0.7 for removing ~74% of redundant pairs at a ~2.5%
        // relevance cost.
        double pure = meanPairSimilarity(select(1.0, 3));
        double diverse = meanPairSimilarity(select(0.7, 3));

        assertTrue(diverse < pure,
                "diverse selection should repeat itself less: %.4f vs %.4f".formatted(diverse, pure));
    }

    @Test
    void paraphrasesStopMonopolisingTheBudget() {
        // The story's motivating case: three ways of saying the same thing outrank
        // everything else, so pure relevance fills the block with one fact.
        var pure = ids(select(1.0, 3));
        var diverse = ids(select(0.7, 3));

        assertEquals(3, pure.stream().filter(GENRE_IDS::contains).count(),
                "precondition: pure relevance takes all three paraphrases");
        assertTrue(diverse.stream().filter(GENRE_IDS::contains).count() < 3,
                "diversity must leave room for something else, got: " + diverse);
    }

    @Test
    void aDistinctFactReachesTheBlock() {
        var diverse = ids(select(0.7, 3));
        assertTrue(diverse.contains("4") || diverse.contains("5"),
                "a fact pure relevance would have cut must appear, got: " + diverse);
    }

    @Test
    void unrelatedMemoriesKeepTheirRelevanceOrder() {
        // Diversity must not reshuffle things that were never redundant.
        var distinct = List.of(e("a", NAS), e("b", RADARR));
        var got = MemoryMmr.select(distinct, x -> "a".equals(x.id()) ? 0.9 : 0.5, 0.7, 2);
        assertEquals(List.of("a", "b"), ids(got));
    }

    @Test
    void limitIsHonouredAndNeverExceeded() {
        assertEquals(2, select(0.7, 2).size());
        assertEquals(CANDIDATES.size(), select(0.7, 99).size(),
                "asking for more than exists returns everything, not a padded list");
    }

    @Test
    void degenerateInputsReturnEmptyRatherThanThrow() {
        assertTrue(MemoryMmr.select(List.of(), x -> 1.0, 0.7, 5).isEmpty());
        assertTrue(select(0.7, 0).isEmpty());
        assertTrue(select(0.7, -1).isEmpty());
    }

    @Test
    void aZeroTopScoreDoesNotDivideByZero() {
        // Every candidate scored 0 — possible when decay floors and relevance are both
        // degenerate. Selection must still terminate and respect the limit.
        var got = MemoryMmr.select(CANDIDATES, x -> 0.0, 0.7, 2);
        assertEquals(2, got.size());
    }
}
