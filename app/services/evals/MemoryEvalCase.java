package services.evals;

import java.util.List;

/**
 * One recall case: a question, and the memories that answer it (JCLAW-529).
 *
 * @param goldGroups one group per <em>distinct fact</em> the question should surface,
 *        each listing every memory that states that fact.
 *
 *        <p>Two levels, because two different things are being measured. Within a group,
 *        any member counts: a corpus holding one fact three times has three correct
 *        retrievals, and scoring only the source would mark a paraphrase a miss and
 *        penalise exactly what dedup produces. Across groups, each one is a separate
 *        thing the answer needs — which is what makes coverage measurable at all.
 *
 *        <p>A single-fact case is simply a case with one group, so the coverage metric
 *        degrades to plain recall rather than being a second, parallel scheme.
 */
public record MemoryEvalCase(String id, String query, List<List<Long>> goldGroups) {

    public MemoryEvalCase {
        goldGroups = goldGroups == null ? List.of() : goldGroups.stream().map(List::copyOf).toList();
    }

    /** Every acceptable memory id, flattened — for "did anything correct come back". */
    public List<Long> allGoldIds() {
        return goldGroups.stream().flatMap(List::stream).toList();
    }
}
