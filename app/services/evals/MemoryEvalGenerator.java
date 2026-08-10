package services.evals;

import llm.LlmTypes.ChatMessage;
import llm.ProviderRegistry;
import memory.MemorySimilarity;
import memory.MemoryStoreFactory;
import models.Agent;
import models.Memory;
import services.EventLogger;
import services.SessionCompactor;
import services.Tx;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Builds a memory-recall eval suite from an agent's own corpus (JCLAW-529).
 *
 * <p>Synthesised rather than sourced. LOCOMO and LongMemEval score retrieval over their
 * own transcripts, which makes results comparable across systems but says nothing about
 * whether recall works on the memories this instance actually holds — and those are what
 * it has to serve. The trade is deliberate: relevance now, comparability later.
 *
 * <p>The generated artifact is personal data, so it is written only through
 * {@link MemoryEvalPaths}, never returned wholesale to a caller and never placed beside
 * the tracked suites.
 */
public final class MemoryEvalGenerator {

    private MemoryEvalGenerator() {}

    private static final String EVENT_CATEGORY = "memory";

    /** Every active memory of one agent, oldest first — the corpus a suite samples. */
    private static final String ACTIVE_MEMORIES_JPQL =
            "agent.id = ?1 AND supersededAt IS NULL ORDER BY id";

    /** Generous: the maxFacts ceiling, not this, is what decides a cluster is too broad. */
    private static final int MAX_SEMANTIC_NEIGHBOURS = 50;

    private static final String INSTRUCTIONS = """
            You write evaluation questions for a memory-retrieval system. Given one stored \
            fact about a user, write the single most natural question that user might ask \
            whose answer is that fact.

            Do not quote the fact. Reuse as few of its exact words as you can while keeping \
            the question answerable — a question that repeats the fact verbatim tests string \
            matching rather than retrieval. Write it as the user would type it.

            Output only the question, on one line, with no preamble and no quotation marks.""";

    /** Functional seam for the question call, mirroring {@code MemoryAutoCapture.Extractor}. */
    @FunctionalInterface
    public interface QuestionWriter {
        @SuppressWarnings("java:S112")
        String write(List<ChatMessage> messages) throws Exception;
    }

    /** A memory lifted out of its transaction, so the model calls hold no connection. */
    private record Row(Long id, String text) {}

    /**
     * How a coverage question's set of distinct facts is decided.
     *
     * @param by        {@code "lexical"} groups on shared content tokens; {@code "semantic"}
     *                  groups on embedding neighbours. This choice decides what the A/B can
     *                  conclude — see {@link #generateCoverage}
     * @param threshold lexical: minimum token Jaccard. semantic: minimum cosine
     * @param maxFacts  ceiling on distinct facts per question; past it the cluster is a
     *                  topic rather than a question and its retrieval is too diffuse to
     *                  compare rankers with
     */
    public record Clustering(String by, double threshold, int minFacts, int maxFacts) {
        public boolean semantic() {
            return "semantic".equals(by);
        }
    }

    /**
     * Generate a suite of at most {@code sampleSize} cases for {@code agent}.
     *
     * <p>Sampling is a deterministic stride across the corpus in id order rather than a
     * random draw: a suite is a measuring stick, and one that selects different memories
     * each time it is built cannot be compared with the run before it.
     */
    public static MemoryEvalSuite generate(Agent agent, String suiteId, int sampleSize,
                                           QuestionWriter writer) {
        var rows = Tx.run(() -> Memory.<Memory>find(
                        ACTIVE_MEMORIES_JPQL, agent.id).<Memory>fetch()
                .stream().map(m -> new Row(m.id, m.text)).toList());
        if (rows.isEmpty()) {
            return new MemoryEvalSuite(suiteId, "No memories to sample.", corpusFingerprint(rows), List.of());
        }

        int stride = Math.max(1, rows.size() / Math.max(1, sampleSize));
        var cases = new ArrayList<MemoryEvalCase>();
        for (int i = 0; i < rows.size() && cases.size() < sampleSize; i += stride) {
            var row = rows.get(i);
            String question;
            try {
                question = writer.write(List.of(
                        ChatMessage.system(INSTRUCTIONS),
                        ChatMessage.user(row.text()))).strip();
            } catch (Exception e) {
                EventLogger.warn(EVENT_CATEGORY,
                        "Eval question generation failed for memory %d: %s".formatted(row.id(), e.getMessage()));
                continue;
            }
            if (question.isBlank() || question.lines().count() > 1) continue;
            cases.add(new MemoryEvalCase("mem-" + row.id(), question, List.of(goldFor(row, rows))));
        }
        return new MemoryEvalSuite(suiteId,
                "Generated from %d memories of agent %s".formatted(rows.size(), agent.name),
                corpusFingerprint(rows), cases);
    }

    private static final String COVERAGE_INSTRUCTIONS = """
            You write evaluation questions for a memory-retrieval system. Given several \
            related facts about a user, write the single broad question that would need \
            ALL of them to be answered well.

            Ask about the shared subject at the level the whole group covers, not about any \
            one fact. Do not quote the facts or enumerate them. Write it as the user would \
            type it.

            Output only the question, on one line, with no preamble and no quotation marks.""";

    /**
     * Generate coverage cases: broad questions whose answer needs several distinct facts.
     *
     * <p>This is what measures whether a block answers a question rather than merely
     * containing a hit. A single-fact suite cannot: a block holding one fact three times
     * and a block holding three different facts score identically on recall, because both
     * contain "the" answer.
     *
     * <p><b>The clustering signal decides what the suite can conclude, so pick it against
     * the comparison being run.</b> A gold grouping built on the same signal a ranker
     * scores on settles the comparison before it runs. Grouping on token Jaccard to
     * evaluate a ranker that penalises token Jaccard produced exactly that: a clean
     * monotone decline that restated the clustering choice rather than measuring anything.
     * Semantic clustering groups on embedding cosine, so use it against any lexical
     * ranker, and lexical clustering against a purely semantic one.
     *
     * <p>Neither signal is fully independent of retrieval, because recall is hybrid: the
     * lexical one correlates with its keyword leg and the semantic one with its vector leg,
     * and each inflates absolute coverage accordingly. That bias applies to both arms of an
     * A/B equally, so a suite compares two rankers honestly while an absolute coverage
     * number from it means little.
     */
    public static MemoryEvalSuite generateCoverage(Agent agent, String suiteId, int maxCases,
                                                   Clustering clustering, QuestionWriter writer) {
        var rows = Tx.run(() -> Memory.<Memory>find(
                        ACTIVE_MEMORIES_JPQL, agent.id).<Memory>fetch()
                .stream().map(m -> new Row(m.id, m.text)).toList());
        var cases = new ArrayList<MemoryEvalCase>();
        var used = new java.util.HashSet<Long>();

        for (var seed : rows) {
            if (cases.size() >= maxCases) break;
            if (used.contains(seed.id())) continue;
            var cluster = clusterAround(agent, seed, rows, clustering);
            var groups = distinctFacts(cluster);
            // Fewer than three distinct facts is not a coverage question — there is
            // nothing for the budget to have to choose between. Past a ceiling it stops being a
            // question too: measured on this corpus, clusters of 13 and 21 facts produced
            // "what is JClaw and how do I use it in my work?" — a topic, whose retrieval is
            // diffuse enough to add noise to a comparison rather than signal.
            if (groups.size() < clustering.minFacts() || groups.size() > clustering.maxFacts()) continue;

            String question;
            try {
                question = writer.write(List.of(
                        ChatMessage.system(COVERAGE_INSTRUCTIONS),
                        ChatMessage.user(cluster.stream().map(Row::text)
                                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b)))).strip();
            } catch (Exception e) {
                EventLogger.warn(EVENT_CATEGORY,
                        "Coverage question generation failed near memory %d: %s".formatted(seed.id(), e.getMessage()));
                continue;
            }
            if (question.isBlank() || question.lines().count() > 1) continue;

            cases.add(new MemoryEvalCase("cov-" + seed.id(), question, groups));
            cluster.forEach(r -> used.add(r.id()));
        }
        return new MemoryEvalSuite(suiteId,
                "Coverage suite: %d clusters over %d memories of agent %s"
                        .formatted(cases.size(), rows.size(), agent.name),
                corpusFingerprint(rows), cases);
    }

    private static final String BRIDGE_INSTRUCTIONS = """
            You write evaluation questions for a memory-retrieval system. You are given two \
            stored facts about a user: a RELATION fact, which says how the user is connected \
            to someone or something, and a TARGET fact, which states something about that \
            same subject without repeating the connection.

            Write the single question the user would ask that refers to the subject through \
            the RELATION — the way they would actually say it, not by name — and whose answer \
            is the TARGET fact. Do not name the subject. Do not quote either fact.

            Output only the question, on one line, with no preamble and no quotation marks.""";

    /**
     * Words that state how the user is connected to a subject. A memory carrying one of
     * these can bridge a question to a memory that names only the subject.
     */
    private static final Set<String> RELATION_WORDS = Set.of(
            "son", "sons", "daughter", "daughters", "child", "children", "kid", "kids",
            "wife", "husband", "spouse", "partner", "mother", "father", "parents",
            "brother", "sister", "sibling", "siblings", "friend", "colleague", "manager",
            "employer", "company", "team", "client", "landlord", "neighbour", "neighbor",
            "doctor", "dog", "cat", "pet", "laptop", "phone", "car", "house", "apartment");

    /** A token in this few memories is an entity rather than vocabulary. */
    private static final int RARE_TOKEN_MAX_DOCS = 3;

    /**
     * Generate bridge cases: the query asks through a relation, the gold memory names only
     * the entity (JCLAW-529).
     *
     * <p>This is the case class {@link #generate} structurally cannot reach. That mode
     * writes each question from the gold memory's own text, so query and answer always
     * share vocabulary — while the failure this measures is precisely a question whose
     * words appear nowhere in the memory that answers it. The bridging relation lives in a
     * <em>different</em> row, and pairing the two is the whole job.
     *
     * <p><b>Pairs on rare shared content tokens, deliberately not on capitalisation.</b>
     * The second-hop retrieval leg seeds itself from
     * {@code JpaMemoryStore.entityNames}, which is a capitalisation rule; generating gold
     * with that same rule would select for pairs the hop can already bridge and report the
     * hop's own heuristic back as a score. Rarity is independent of it, so a case survives
     * or fails on retrieval rather than on agreeing with the fix.
     */
    public static MemoryEvalSuite generateBridge(Agent agent, String suiteId, int maxCases,
                                                 QuestionWriter writer) {
        var rows = Tx.run(() -> Memory.<Memory>find(
                        ACTIVE_MEMORIES_JPQL, agent.id).<Memory>fetch()
                .stream().map(m -> new Row(m.id, m.text)).toList());
        var docFreq = new java.util.HashMap<String, Integer>();
        for (var r : rows) {
            for (var t : MemorySimilarity.contentTokens(r.text())) docFreq.merge(t, 1, Integer::sum);
        }

        var cases = new ArrayList<MemoryEvalCase>();
        var usedTargets = new java.util.HashSet<Long>();
        for (var relation : rows) {
            if (cases.size() >= maxCases) break;
            var relationTokens = MemorySimilarity.contentTokens(relation.text());
            if (relationTokens.stream().noneMatch(RELATION_WORDS::contains)) continue;

            for (var target : rows) {
                if (cases.size() >= maxCases) break;
                if (target.id().equals(relation.id()) || usedTargets.contains(target.id())) continue;
                var targetTokens = MemorySimilarity.contentTokens(target.text());
                // The target must NOT already carry the relation, or there is no gap to bridge
                // and the case would measure ordinary recall under a bridge case's name.
                if (targetTokens.stream().anyMatch(RELATION_WORDS::contains)) continue;
                if (relationTokens.stream().noneMatch(t ->
                        targetTokens.contains(t) && docFreq.getOrDefault(t, 0) <= RARE_TOKEN_MAX_DOCS)) {
                    continue;
                }

                String question;
                try {
                    question = writer.write(List.of(
                            ChatMessage.system(BRIDGE_INSTRUCTIONS),
                            ChatMessage.user("RELATION: %s\nTARGET: %s"
                                    .formatted(relation.text(), target.text())))).strip();
                } catch (Exception e) {
                    EventLogger.warn(EVENT_CATEGORY,
                            "Bridge question generation failed for memory %d: %s"
                                    .formatted(target.id(), e.getMessage()));
                    continue;
                }
                if (question.isBlank() || question.lines().count() > 1) continue;
                // The writer is told not to name the subject and mostly obeys; when it does
                // not, the query reaches the gold by name and the case is ordinary recall
                // wearing a bridge label. Measured 1 in 11 on a real corpus — rare enough to
                // miss by inspection, common enough to move a suite of this size.
                if (namesItsOwnGold(question, target.text())) continue;

                cases.add(new MemoryEvalCase("bridge-" + target.id(), question,
                        List.of(goldFor(target, rows))));
                usedTargets.add(target.id());
            }
        }
        return new MemoryEvalSuite(suiteId,
                "Bridge suite: %d relation-phrased questions over %d memories of agent %s"
                        .formatted(cases.size(), rows.size(), agent.name),
                corpusFingerprint(rows), cases);
    }

    /**
     * Whether {@code question} names an entity the gold memory also names.
     *
     * <p>Uses the retrieval leg's own name rule, which is safe here in a way it would not
     * be for pairing: this only ever <em>removes</em> cases, and the ones it removes are
     * the ones the keyword leg could already answer. It cannot manufacture a case the hop
     * happens to be good at.
     */
    static boolean namesItsOwnGold(String question, String goldText) {
        return memory.JpaMemoryStore.entityNames(goldText).stream().anyMatch(question::contains);
    }

    /**
     * Distinct-fact counts for the clusters {@code threshold} would produce, without
     * writing any questions.
     *
     * <p>The threshold is the one number in coverage generation that has to be measured
     * rather than picked: too high and every cluster is a set of paraphrases that
     * collapses to a single fact, too low and unrelated memories are declared part of one
     * question. Sweeping it through the full generator would spend a model call per
     * surviving cluster per sweep point.
     */
    public static List<Integer> clusterSizes(Agent agent, Clustering clustering) {
        var rows = Tx.run(() -> Memory.<Memory>find(
                        ACTIVE_MEMORIES_JPQL, agent.id).<Memory>fetch()
                .stream().map(m -> new Row(m.id, m.text)).toList());
        var sizes = new ArrayList<Integer>();
        var used = new java.util.HashSet<Long>();
        for (var seed : rows) {
            if (used.contains(seed.id())) continue;
            var cluster = clusterAround(agent, seed, rows, clustering);
            sizes.add(distinctFacts(cluster).size());
            cluster.forEach(r -> used.add(r.id()));
        }
        return sizes;
    }

    /** Memories related to the seed, by whichever signal {@code clustering} names. */
    private static List<Row> clusterAround(Agent agent, Row seed, List<Row> all, Clustering clustering) {
        return clustering.semantic()
                ? semanticCluster(agent, seed, all, clustering.threshold())
                : lexicalCluster(seed, all, clustering.threshold());
    }

    private static List<Row> lexicalCluster(Row seed, List<Row> all, double threshold) {
        var seedTokens = MemorySimilarity.contentTokens(seed.text());
        var cluster = new ArrayList<Row>();
        cluster.add(seed);
        for (var other : all) {
            if (other.id().equals(seed.id())) continue;
            if (MemorySimilarity.jaccard(seedTokens, MemorySimilarity.contentTokens(other.text())) >= threshold) {
                cluster.add(other);
            }
        }
        return cluster;
    }

    /** Embedding neighbours of the seed, restricted to the corpus rows already in hand. */
    private static List<Row> semanticCluster(Agent agent, Row seed, List<Row> all, double minCosine) {
        var byId = all.stream().collect(java.util.stream.Collectors.toMap(Row::id, r -> r, (a, b) -> a));
        var ids = MemoryStoreFactory.get().semanticNeighbours(
                String.valueOf(agent.id), seed.text(), MAX_SEMANTIC_NEIGHBOURS, minCosine);
        var cluster = new ArrayList<Row>();
        cluster.add(seed);
        for (var id : ids) {
            var row = byId.get(id);
            if (row != null && !id.equals(seed.id())) cluster.add(row);
        }
        return cluster;
    }

    /**
     * Collapse a cluster into distinct facts: each group holds one fact and the
     * paraphrases restating it, so covering a fact once is enough and a corpus that
     * repeats itself does not inflate the target.
     */
    private static List<List<Long>> distinctFacts(List<Row> cluster) {
        var groups = new ArrayList<List<Row>>();
        for (var row : cluster) {
            var tokens = MemorySimilarity.Tokens.of(row.text());
            var match = groups.stream().filter(g -> MemorySimilarity.isDuplicate(
                    tokens, MemorySimilarity.Tokens.of(g.getFirst().text()), 0.85, 0.82, 0.5)).findFirst();
            if (match.isPresent()) {
                match.get().add(row);
            } else {
                var g = new ArrayList<Row>();
                g.add(row);
                groups.add(g);
            }
        }
        return groups.stream().map(g -> g.stream().map(Row::id).toList()).toList();
    }

    /**
     * Every memory that answers the question as well as the source does: the source plus
     * any near-duplicate of it.
     *
     * <p>Without this the harness would score a correct retrieval as a miss whenever the
     * corpus holds a fact more than once, penalising exactly what dedup is for. Uses the
     * same duplicate test capture uses, so what counts as the same fact here is what
     * counts as the same fact there.
     */
    private static List<Long> goldFor(Row source, List<Row> all) {
        var gold = new ArrayList<Long>();
        gold.add(source.id());
        var sourceTokens = MemorySimilarity.Tokens.of(source.text());
        for (var other : all) {
            if (other.id().equals(source.id())) continue;
            if (MemorySimilarity.isDuplicate(sourceTokens, MemorySimilarity.Tokens.of(other.text()),
                    0.85, 0.82, 0.5)) {
                gold.add(other.id());
            }
        }
        return gold;
    }

    /**
     * Identifies the corpus a suite was built from, so a report can say whether it is
     * still describing the same store. Ids and count only — never the texts, which must
     * not travel in an artifact any further than they already do.
     */
    private static String corpusFingerprint(List<Row> rows) {
        long sum = 0;
        for (var r : rows) sum = sum * 31 + r.id();
        return "%d:%08x".formatted(rows.size(), sum & 0xFFFFFFFFL);
    }

    /** Production question writer: the agent's own model, which is local on this install. */
    public static QuestionWriter writerFor(Agent agent) {
        var provider = ProviderRegistry.get(agent.modelProvider);
        if (provider == null) return null;
        return msgs -> SessionCompactor.firstChoiceText(
                provider.chat(agent.modelId, msgs, List.of(), 120, null, null));
    }
}
