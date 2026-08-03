package memory;

import memory.MemoryStore.MemoryEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * Maximal Marginal Relevance selection for recall (JCLAW-923).
 *
 * <p>Recall had no diversity control at all: fusion, importance and decay all reward a
 * memory for matching the query, and nothing penalises it for repeating one already
 * chosen. A store that has accumulated paraphrases — this one holds the same movie
 * preference three times — can therefore spend the whole {@code memory.recall.limit}
 * budget restating one fact while other relevant memories are cut.
 *
 * <p>Carbonell and Goldstein (1998): pick greedily, each time maximising
 * {@code λ·relevance − (1−λ)·maxSimilarityToAlreadyPicked}. λ=1 is pure relevance, which
 * is exactly today's behaviour, so the feature degrades to the status quo rather than to
 * something new.
 *
 * <p>Similarity is token Jaccard via {@link MemorySimilarity}, not embedding cosine.
 * Deliberate: this runs on every turn inside prompt assembly, where an embedding call per
 * candidate pair would put network latency on the critical path, and the redundancy being
 * removed here is the kind that is already visible in the wording.
 */
public final class MemoryMmr {

    private MemoryMmr() {}

    /**
     * How aggressively to trade relevance for novelty.
     *
     * @param lambda          1.0 keeps the incoming order exactly; lower values trade
     *                        relevance for novelty
     * @param redundancyFloor similarity below which two memories are treated as carrying
     *                        different information, and no penalty applies at all
     */
    public record Settings(double lambda, double redundancyFloor) {}

    /**
     * Select up to {@code limit} entries balancing score against novelty.
     *
     * @param ranked candidates, best-scoring first
     * @param score  the blended recall score; normalised internally against the top score
     *               so {@code lambda} weighs comparable magnitudes whatever the blend
     *               produced
     */
    public static List<MemoryEntry> select(List<MemoryEntry> ranked,
            ToDoubleFunction<MemoryEntry> score, Settings settings, int limit) {
        double lambda = settings.lambda();
        if (limit <= 0 || ranked.isEmpty()) return List.of();
        if (lambda >= 1.0 || ranked.size() <= 1) {
            return ranked.stream().limit(limit).toList();
        }

        double top = score.applyAsDouble(ranked.getFirst());
        var remaining = new ArrayList<>(ranked);
        var tokens = new ArrayList<Set<String>>(remaining.size());
        for (var e : remaining) tokens.add(MemorySimilarity.tokenize(e.text()));

        var selected = new ArrayList<MemoryEntry>(Math.min(limit, remaining.size()));
        var selectedTokens = new ArrayList<Set<String>>();
        while (selected.size() < limit && !remaining.isEmpty()) {
            int best = 0;
            double bestValue = -Double.MAX_VALUE;
            for (int i = 0; i < remaining.size(); i++) {
                double relevance = top > 0 ? score.applyAsDouble(remaining.get(i)) / top : 0.0;
                double value = lambda * relevance
                        - (1 - lambda) * maxSimilarity(tokens.get(i), selectedTokens,
                                settings.redundancyFloor());
                if (value > bestValue) {
                    bestValue = value;
                    best = i;
                }
            }
            selected.add(remaining.remove(best));
            selectedTokens.add(tokens.remove(best));
        }
        return List.copyOf(selected);
    }

    /**
     * Redundancy against what is already chosen, counted only above {@code floor}.
     *
     * <p>The floor is what makes this target duplicates rather than topics. A plain
     * linear penalty punishes every candidate in proportion to its overlap, so the ones
     * it actually displaces are the cheap ones at the tail — measured on a live store, a
     * query about film preferences kept all three restatements of one genre list (they
     * score too high to displace) and instead evicted "loves animated movies" and "avoids
     * family films" in favour of memories unrelated to the query. Those distinct facts sat
     * at 0.09-0.14 similarity to what was already shown while the true paraphrases sat at
     * 0.27-0.53, so ignoring everything below the floor separates them cleanly.
     */
    private static double maxSimilarity(Set<String> candidate, List<Set<String>> selected, double floor) {
        double max = 0;
        for (var s : selected) {
            double sim = MemorySimilarity.jaccard(candidate, s);
            if (sim >= floor) max = Math.max(max, sim);
        }
        return max;
    }
}
