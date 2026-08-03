package services.evals;

import llm.LlmTypes.ChatMessage;
import llm.ProviderRegistry;
import memory.MemorySimilarity;
import models.Agent;
import models.Memory;
import services.EventLogger;
import services.SessionCompactor;
import services.Tx;

import java.util.ArrayList;
import java.util.List;

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
     * Generate a suite of at most {@code sampleSize} cases for {@code agent}.
     *
     * <p>Sampling is a deterministic stride across the corpus in id order rather than a
     * random draw: a suite is a measuring stick, and one that selects different memories
     * each time it is built cannot be compared with the run before it.
     */
    public static MemoryEvalSuite generate(Agent agent, String suiteId, int sampleSize,
                                           QuestionWriter writer) {
        var rows = Tx.run(() -> Memory.<Memory>find(
                        "agent.id = ?1 AND supersededAt IS NULL ORDER BY id", agent.id).<Memory>fetch()
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
     * <p>This is what measures a diversity pass. A single-fact suite cannot: a block
     * holding one fact three times and a block holding three different facts score
     * identically on recall, because both contain "the" answer.
     *
     * <p>Clusters are built on content-token overlap rather than on embeddings, so the
     * vector pipeline under test does not get to define the groups it will then be scored
     * on retrieving. That independence is partial, not clean: recall is hybrid, and
     * lexical clustering does correlate with its keyword leg, which inflates absolute
     * coverage. It biases both arms of an A/B equally, so the suite is fit for comparing
     * two rankers and not for claiming an absolute coverage number.
     */
    public static MemoryEvalSuite generateCoverage(Agent agent, String suiteId, int maxCases,
                                                   double clusterThreshold, QuestionWriter writer) {
        var rows = Tx.run(() -> Memory.<Memory>find(
                        "agent.id = ?1 AND supersededAt IS NULL ORDER BY id", agent.id).<Memory>fetch()
                .stream().map(m -> new Row(m.id, m.text)).toList());
        var cases = new ArrayList<MemoryEvalCase>();
        var used = new java.util.HashSet<Long>();

        for (var seed : rows) {
            if (cases.size() >= maxCases) break;
            if (used.contains(seed.id())) continue;
            var cluster = clusterAround(seed, rows, clusterThreshold);
            var groups = distinctFacts(cluster);
            // Fewer than three distinct facts is not a coverage question — there is
            // nothing for a diversity pass to trade off.
            if (groups.size() < 3) continue;

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
    public static List<Integer> clusterSizes(Agent agent, double threshold) {
        var rows = Tx.run(() -> Memory.<Memory>find(
                        "agent.id = ?1 AND supersededAt IS NULL ORDER BY id", agent.id).<Memory>fetch()
                .stream().map(m -> new Row(m.id, m.text)).toList());
        var sizes = new ArrayList<Integer>();
        var used = new java.util.HashSet<Long>();
        for (var seed : rows) {
            if (used.contains(seed.id())) continue;
            var cluster = clusterAround(seed, rows, threshold);
            sizes.add(distinctFacts(cluster).size());
            cluster.forEach(r -> used.add(r.id()));
        }
        return sizes;
    }

    /** Memories topically related to the seed, by boilerplate-stripped token overlap. */
    private static List<Row> clusterAround(Row seed, List<Row> all, double threshold) {
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
     * corpus holds a fact more than once, penalising exactly what dedup and diversity
     * selection are for. Uses the same duplicate test capture uses, so what counts as the
     * same fact here is what counts as the same fact there.
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
