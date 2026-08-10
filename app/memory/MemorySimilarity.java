package memory;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Near-duplicate detection for capture-time dedup (JCLAW-922). Deterministic and
 * dialect-free — no embedding round-trip, no search backend — so the NOOP decides
 * identically on H2 and Postgres, and with vector memory on or off. That matters
 * because the decision runs inside the plan transaction, where a blocking call is
 * forbidden.
 *
 * <p>Two independent tests, either of which marks a pair duplicate:
 *
 * <ul>
 *   <li><b>Token Jaccard</b> — the original JCLAW-39 metric, unchanged, for
 *       near-identical restatements.</li>
 *   <li><b>Content containment</b> — the overlap coefficient over
 *       boilerplate-stripped tokens, gated on a length ratio. Catches what Jaccard
 *       structurally cannot: a restatement that is a subset of an existing memory,
 *       where the length difference alone drags Jaccard below the threshold.</li>
 * </ul>
 */
public final class MemorySimilarity {

    private MemorySimilarity() {}

    /**
     * Stripped before the containment test only. Auto-capture writes every memory
     * in the third person ("The user ...", per {@code EXTRACTION_INSTRUCTIONS}), so
     * these tokens are near-universal and inflate overlap between unrelated facts.
     */
    static final Set<String> BOILERPLATE = Set.of(
            "the", "user", "s", "a", "an", "and", "or", "to", "of", "for", "in", "is",
            "are", "that", "with", "it", "as", "on", "by", "be", "this", "their",
            "they", "them", "has", "have", "had", "was", "were", "at", "from", "not",
            "but", "which", "who", "when", "where", "assistant", "also", "its");

    /** Both token views of one text, so the candidates-by-pool scan tokenizes once per row. */
    public record Tokens(Set<String> raw, Set<String> content) {
        public static Tokens of(String text) {
            return new Tokens(tokenize(text), contentTokens(text));
        }
    }

    public static Set<String> tokenize(String text) {
        var set = new HashSet<String>();
        if (text == null) return set;
        for (var tok : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!tok.isBlank()) set.add(tok);
        }
        return set;
    }

    public static Set<String> contentTokens(String text) {
        var set = tokenize(text);
        set.removeAll(BOILERPLATE);
        return set;
    }

    public static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int inter = intersectionSize(a, b);
        int union = a.size() + b.size() - inter;
        return union == 0 ? 0.0 : (double) inter / union;
    }

    /** Overlap coefficient — the fraction of the smaller set the larger one covers. */
    public static double containment(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        return (double) intersectionSize(a, b) / Math.min(a.size(), b.size());
    }

    private static double lengthRatio(Set<String> a, Set<String> b) {
        int max = Math.max(a.size(), b.size());
        return max == 0 ? 1.0 : (double) Math.min(a.size(), b.size()) / max;
    }

    private static int intersectionSize(Set<String> a, Set<String> b) {
        var smaller = a.size() <= b.size() ? a : b;
        var larger = a.size() <= b.size() ? b : a;
        int n = 0;
        for (var t : smaller) {
            if (larger.contains(t)) n++;
        }
        return n;
    }

    /**
     * Whether two memory texts state the same fact.
     *
     * @param minLengthRatio floor on {@code min/max} content-token count, required
     *        for the containment test to fire. Without it a short memory swallowed
     *        by a long unrelated one reads as a full subset: a ten-item recurring-task
     *        digest covers every content token of "recurring reminder to pay salaries
     *        on the last Friday of every month at 5:00 PM" (containment 0.91) while
     *        sharing no subject with it. Both false positives found while validating
     *        this rule against a 1248-row store had that shape and sat below a 0.1
     *        ratio; every true duplicate sat above 0.5.
     */
    public static boolean isDuplicate(Tokens a, Tokens b, double jaccardThreshold,
            double containmentThreshold, double minLengthRatio) {
        if (jaccard(a.raw(), b.raw()) >= jaccardThreshold) {
            return true;
        }
        return containment(a.content(), b.content()) >= containmentThreshold
                && lengthRatio(a.content(), b.content()) >= minLengthRatio;
    }
}
