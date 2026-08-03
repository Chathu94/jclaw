package services.evals;

import java.util.List;

/**
 * One recall case: a question, and every memory that would be a correct answer to it
 * (JCLAW-529).
 *
 * @param goldMemoryIds all acceptable answers, not just the memory the question was
 *        generated from. A corpus that holds the same fact three times has three correct
 *        retrievals, and scoring only the source id would mark a perfectly good recall as
 *        a miss — punishing exactly the behaviour dedup and diversity selection produce.
 */
public record MemoryEvalCase(String id, String query, List<Long> goldMemoryIds) {

    public MemoryEvalCase {
        goldMemoryIds = goldMemoryIds == null ? List.of() : List.copyOf(goldMemoryIds);
    }
}
