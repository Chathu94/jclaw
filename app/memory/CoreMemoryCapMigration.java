package memory;

import com.google.gson.JsonParser;
import llm.LlmTypes.ChatMessage;
import llm.ProviderRegistry;
import models.Agent;
import models.Memory;
import services.ConfigService;
import services.EventLogger;
import services.SessionCompactor;
import services.Tx;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Recategorise core memories beyond the cap (JCLAW-981).
 *
 * <p>Operator-triggered rather than automatic. The tool-side rule guarantees no core
 * memory is written past {@code memory.coreload.maxCount}, but it cannot guarantee the
 * agent asks before storing something elsewhere — that half is instruction to a model. A
 * button the operator presses is enforceable in a way a mid-conversation prompt is not,
 * so this is the deliberate path for bringing an over-cap corpus back in line.
 *
 * <p>The new category comes from the owning agent's own model rather than a blind demotion
 * to {@code fact}. These are memories the operator once thought core, so flattening them
 * all into one bucket destroys the distinction between a preference, a decision and an
 * entity — and that distinction is what recall's importance blend and the admin UI both
 * read. Classification is per-agent because memories are partitioned per-agent, so the
 * model that knows the corpus is the one already configured for it.
 *
 * <p>Fail-safe, not fail-open: a memory whose classification fails is left as core rather
 * than guessed at. A wrong bucket is a silent, permanent mislabel of something the operator
 * marked important; staying over the cap is visible and re-runnable.
 */
public final class CoreMemoryCapMigration {

    private CoreMemoryCapMigration() {}

    private static final String EVENT_CATEGORY = "memory";

    /** The buckets a demoted memory may land in — everything except core itself. */
    private static final List<String> TARGETS = MemoryCategory.labels().stream()
            .filter(l -> !l.equals(MemoryCategory.CORE.label))
            .toList();

    private static final String INSTRUCTIONS = """
            You are recategorising an agent's long-term memories. Each was previously filed as
            "core" — always loaded into context — but the core tier is full, so each must move to
            the bucket that fits it best.

            Categories:
            - preference: how the user likes things done
            - decision: a choice made and (if given) its rationale
            - entity: attributes of a specific named person, place, project, system, or account
            - lesson: something learned, often from a correction or mistake
            - fact: a stable factual statement, and the fallback when none of the others fit

            Reply with ONLY a JSON array of category strings, one per memory, in the same order
            you received them. No prose, no code fences.
            """;

    /** Canned classification for tests, mirroring {@link MemoryReranker#setRankCallForTest}. */
    @FunctionalInterface
    public interface Classifier {
        List<String> classify(Agent agent, List<String> texts);
    }

    private static volatile Classifier classifierOverride;

    /** Test-only: install (or clear with {@code null}) a canned classifier. */
    public static void setClassifierForTest(Classifier override) {
        classifierOverride = override;
    }

    /** Test-only: run the sweep inline, so a test need not poll the virtual thread. */
    public static void runForTest() {
        migrate();
    }

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final AtomicInteger processed = new AtomicInteger();
    private static final AtomicInteger total = new AtomicInteger();
    private static volatile String lastError;

    /**
     * What the Limits panel polls. {@code overCap} is what turns the button on, and is not
     * derivable from {@code running} — a corpus can be over the cap with nothing in flight.
     */
    /**
     * One agent's core-memory usage against the cap.
     *
     * @param core the agent's live core count — the number the cap governs
     */
    public record AgentCore(String agentId, String agentName, long core, boolean overCap) {}

    public record Status(boolean running, int processed, int total,
                         long liveCore, int cap, boolean overCap, String error,
                         List<AgentCore> agents) {}

    public static Status status() {
        int cap = cap();
        var agents = Tx.run(() -> coreByAgent(cap));
        // Sorted desc, so the head is the agent the cap actually binds.
        long live = agents.isEmpty() ? 0 : agents.getFirst().core();
        return new Status(running.get(), processed.get(), total.get(),
                live, cap, live > cap, lastError, agents);
    }

    private static int cap() {
        return ConfigService.getInt("memory.coreload.maxCount", 20);
    }

    /**
     * Live core counts per agent, busiest first, for every agent holding at least one.
     *
     * <p>The cap is per agent, because the core block is assembled per agent — a total
     * across the instance describes nothing {@link #migrate()} can act on, and reporting
     * one made the panel show "over the limit" for a corpus already in line (20 core on
     * one agent plus 1 on another read as 21 against a cap of 20, with no agent over it
     * and nothing for a pass to select).
     *
     * <p>One grouped query rather than a count per agent. The previous loop ran
     * {@code Agent.findAll()} and counted each one — 489 queries per poll on this
     * instance, 92 ms for an endpoint the Settings panel polls, against 0.18 ms for the
     * aggregate. Agents holding no core memory are absent rather than listed as zero,
     * which is what keeps the payload two rows instead of 489.
     */
    private static List<AgentCore> coreByAgent(int cap) {
        List<?> rows = play.db.jpa.JPA.em().createQuery(
                        "select m.agent.id, m.agent.name, count(m) from Memory m "
                                + "where m.category = :core and m.supersededAt is null "
                                + "group by m.agent.id, m.agent.name order by count(m) desc")
                .setParameter("core", MemoryCategory.CORE.label)
                .getResultList();
        var out = new ArrayList<AgentCore>(rows.size());
        for (Object row : rows) {
            var cols = (Object[]) row;
            long n = ((Number) cols[2]).longValue();
            out.add(new AgentCore(String.valueOf(cols[0]), (String) cols[1], n, n > cap));
        }
        return out;
    }

    /**
     * Start a migration on a virtual thread. Returns the reason it could not start, or
     * {@code null} when it did — single-flight, because two passes would classify the same
     * overflow twice and the second would act on a stale snapshot.
     */
    public static String start() {
        var s = status();
        if (!s.overCap()) {
            return "No agent is over the core-memory cap of %d — there is nothing to migrate."
                    .formatted(s.cap());
        }
        if (!running.compareAndSet(false, true)) {
            return "A core-memory migration is already running.";
        }
        lastError = null;
        processed.set(0);
        total.set(0);
        Thread.ofVirtual().name("core-memory-migration").start(CoreMemoryCapMigration::run);
        return null;
    }

    private static void run() {
        try {
            migrate();
        } catch (Exception e) {
            lastError = e.getMessage();
            EventLogger.warn(EVENT_CATEGORY,
                    "Core-memory migration failed: %s".formatted(e.getMessage()));
        } finally {
            running.set(false);
        }
    }

    private static void migrate() {
        int cap = cap();
        for (Agent agent : Tx.run(() -> List.copyOf(Agent.<Agent>findAll()))) {
            var agentId = String.valueOf(agent.id);
            // Importance 0 so this sees every core row, matching countLiveCore — one below
            // the load threshold still occupies a slot.
            List<Memory> core = Tx.run(() ->
                    Memory.findCore(agentId, 0.0, Integer.MAX_VALUE));
            if (core.size() <= cap) continue;

            // findCore's own order — importance, then recency. The survivors are therefore
            // exactly the memories the operator has been seeing in the prompt; any other
            // order silently swaps the always-loaded set.
            var overflow = core.subList(cap, core.size());
            total.addAndGet(overflow.size());
            var assigned = classify(agent, overflow.stream().map(m -> m.text).toList());

            for (int i = 0; i < overflow.size(); i++) {
                var category = assigned.get(i);
                if (category == null) continue;   // left as core, to be retried
                var id = overflow.get(i).id;
                Tx.run(() -> {
                    Memory row = Memory.findById(id);
                    if (row != null) {
                        row.category = category;
                        row.save();
                    }
                });
                processed.incrementAndGet();
            }
            EventLogger.info(EVENT_CATEGORY, agent.name, null,
                    "Core-memory migration: %d kept, %d recategorised of %d over the cap"
                            .formatted(cap, processed.get(), overflow.size()));
        }
    }

    /**
     * One valid non-core category per text, or null for an entry that was not classified.
     *
     * <p>Nulls rather than a {@code fact} fallback: see the fail-safe note on the class.
     *
     * <p>{@link #sanitise} runs on the model's answer AND on a test stub's. The seam
     * replaces the model call only — putting it any wider would let a test assert against
     * its own stub rather than against what production does with that stub, which is
     * exactly how the "a model answering core" case first passed while the guard was
     * unreachable.
     */
    private static List<String> classify(Agent agent, List<String> texts) {
        var answers = classifierOverride != null
                ? classifierOverride.classify(agent, texts)
                : askModel(agent, texts);
        return sanitise(answers, texts.size());
    }

    /** Raw, unvalidated category strings from the agent's model — empty when it cannot run. */
    private static List<String> askModel(Agent agent, List<String> texts) {
        var provider = ProviderRegistry.get(agent.modelProvider);
        if (provider == null || agent.modelId == null || agent.modelId.isBlank()) {
            EventLogger.warn(EVENT_CATEGORY,
                    "Agent %s has no usable model, so its core overflow stays core".formatted(agent.name));
            return List.of();
        }
        var numbered = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            numbered.append(i).append(": ").append(texts.get(i)).append('\n');
        }
        try {
            var reply = SessionCompactor.firstChoiceText(provider.chat(agent.modelId,
                    List.of(ChatMessage.system(INSTRUCTIONS), ChatMessage.user(numbered.toString())),
                    List.of(), 1024, null, null));
            var arr = JsonParser.parseString(strip(reply)).getAsJsonArray();
            var out = new ArrayList<String>(arr.size());
            for (var el : arr) out.add(el.getAsString());
            return out;
        } catch (Exception e) {
            EventLogger.warn(EVENT_CATEGORY,
                    "Core-memory classification failed for %s, leaving its overflow as core: %s"
                            .formatted(agent.name, e.getMessage()));
            return List.of();
        }
    }

    /**
     * Map raw answers onto valid targets, padded to {@code size} with nulls.
     *
     * <p>A model that answers {@code core} would defeat the migration entirely, and models
     * do return values outside the set they are given (JCLAW-927) — so {@code core} is
     * coerced away here rather than trusted not to appear.
     */
    private static List<String> sanitise(List<String> answers, int size) {
        var out = new ArrayList<String>(size);
        for (int i = 0; i < size; i++) out.add(null);
        for (int i = 0; i < Math.min(answers.size(), size); i++) {
            var raw = MemoryCategory.normalize(answers.get(i));
            if (raw == null) continue;
            out.set(i, TARGETS.contains(raw) ? raw : MemoryCategory.coerceForCapture(raw));
        }
        return out;
    }

    /** Models fence JSON despite being told not to. */
    private static String strip(String s) {
        if (s == null) return "[]";
        var t = s.strip();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl >= 0) t = t.substring(nl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.strip();
    }
}
