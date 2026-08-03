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
     * Select up to {@code limit} entries balancing score against novelty.
     *
     * @param ranked  candidates, best-scoring first
     * @param score   the blended recall score; normalised internally against the top
     *                score so {@code lambda} weighs comparable magnitudes whatever the
     *                blend produced
     * @param lambda  1.0 keeps the incoming order exactly; lower values trade relevance
     *                for novelty
     */
    public static List<MemoryEntry> select(List<MemoryEntry> ranked,
            ToDoubleFunction<MemoryEntry> score, double lambda, int limit) {
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
                        - (1 - lambda) * maxSimilarity(tokens.get(i), selectedTokens);
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

    private static double maxSimilarity(Set<String> candidate, List<Set<String>> selected) {
        double max = 0;
        for (var s : selected) {
            max = Math.max(max, MemorySimilarity.jaccard(candidate, s));
        }
        return max;
    }
}
