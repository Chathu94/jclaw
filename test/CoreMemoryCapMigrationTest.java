import memory.CoreMemoryCapMigration;
import memory.MemoryCategory;
import memory.MemoryStoreFactory;
import models.Agent;
import models.Memory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;

import java.util.ArrayList;
import java.util.List;

/**
 * JCLAW-981: the operator-triggered pass that brings an over-cap corpus back in line.
 *
 * <p>Operator-triggered because the tool-side rule can refuse a core write past the cap but
 * cannot make the agent ask before filing something elsewhere — that half is instruction to
 * a model, so the enforceable path is a button rather than a prompt.
 *
 * <p>Classification is stubbed through {@code setClassifierForTest}: what matters here is
 * which memories are selected, that the survivors are the ones the prompt was already
 * loading, and that an unclassified memory is left alone rather than guessed at.
 */
class CoreMemoryCapMigrationTest extends UnitTest {

    private Agent agent;

    @BeforeEach
    void setup() {
        LuceneTestSync.closedForTest();
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        MemoryStoreFactory.reset();
        agent = new Agent();
        agent.name = "core-cap-agent";
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        agent.save();
    }

    @AfterEach
    void release() {
        CoreMemoryCapMigration.setClassifierForTest(null);
        LuceneTestSync.release();
    }

    /** A second agent, so the per-agent cap can be told apart from a global sum. */
    private Agent otherAgent() {
        var a = new Agent();
        a.name = "core-cap-other-agent";
        a.modelProvider = "openrouter";
        a.modelId = "gpt-4.1";
        a.save();
        return a;
    }

    private void storeCoreFor(Agent a, String text, double importance) {
        MemoryStoreFactory.get().store(String.valueOf(a.id), text,
                MemoryCategory.CORE.label, importance);
    }

    private void storeCore(String text, double importance) {
        MemoryStoreFactory.get().store(String.valueOf(agent.id), text,
                MemoryCategory.CORE.label, importance);
    }

    private long liveCore() {
        return Memory.countLiveCore(String.valueOf(agent.id));
    }

    private static int cap() {
        return ConfigService.getInt("memory.coreload.maxCount", 20);
    }

    /** Answers with the same category for everything, which is enough to pin selection. */
    private static void classifyAllAs(String category) {
        CoreMemoryCapMigration.setClassifierForTest((a, texts) -> {
            var out = new ArrayList<String>();
            for (int i = 0; i < texts.size(); i++) out.add(category);
            return out;
        });
    }

    @Test
    void statusReportsTheCorpusAgainstTheCap() {
        storeCore("The only core fact", 0.9);
        var s = CoreMemoryCapMigration.status();
        assertEquals(1L, s.liveCore());
        assertEquals(cap(), s.cap());
        assertFalse(s.overCap(), "one memory is not over a cap of %d".formatted(cap()));
        assertFalse(s.running());
    }

    @Test
    void refusesToStartWhenNothingIsOverTheCap() {
        storeCore("The only core fact", 0.9);
        var refusal = CoreMemoryCapMigration.start();
        assertNotNull(refusal, "starting a pointless migration must be refused, not silently run");
        assertTrue(refusal.contains("nothing to migrate"), refusal);
    }

    @Test
    void keepsTheCapAndRecategorisesTheRest() {
        for (int i = 0; i < cap() + 5; i++) storeCore("Core fact %02d".formatted(i), 0.9);
        assertEquals(cap() + 5L, liveCore(), "seed must actually exceed the cap");
        classifyAllAs(MemoryCategory.ENTITY.label);

        CoreMemoryCapMigration.runForTest();

        assertEquals(cap(), liveCore(), "exactly the cap survives as core");
        assertEquals(5, MemoryStoreFactory.get().list(String.valueOf(agent.id)).stream()
                        .filter(m -> MemoryCategory.ENTITY.label.equals(m.category())).count(),
                "the overflow is recategorised, not deleted");
    }

    @Test
    void keepsTheHighestRankedMemoriesAsCore() {
        // findCore orders by importance then recency, and the migration must keep that
        // order — any other choice silently swaps which memories load every turn.
        for (int i = 0; i < cap(); i++) storeCore("Important core fact %02d".formatted(i), 0.95);
        storeCore("MARKER_LOW an afterthought", 0.30);
        classifyAllAs(MemoryCategory.FACT.label);

        CoreMemoryCapMigration.runForTest();

        var demoted = MemoryStoreFactory.get().list(String.valueOf(agent.id)).stream()
                .filter(m -> m.text().contains("MARKER_LOW")).findFirst().orElseThrow();
        assertEquals(MemoryCategory.FACT.label, demoted.category(),
                "the lowest-importance memory is the one that loses core");
    }

    @Test
    void anUnclassifiedMemoryStaysCoreRatherThanBeingGuessedAt() {
        // Fail-safe, not fail-open: a wrong bucket is a silent permanent mislabel of
        // something the operator marked important, where staying over the cap is visible
        // and the button can simply be pressed again.
        for (int i = 0; i < cap() + 1; i++) storeCore("Core fact %02d".formatted(i), 0.9);
        CoreMemoryCapMigration.setClassifierForTest((a, texts) -> {
            var out = new ArrayList<String>();
            for (int i = 0; i < texts.size(); i++) out.add(null);
            return out;
        });

        CoreMemoryCapMigration.runForTest();

        assertEquals(cap() + 1L, liveCore(), "nothing may be recategorised on a failed classification");
    }

    @Test
    void aModelAnsweringCoreCannotPutAMemoryBack() {
        // The classifier is a model, and models return values outside the set they are
        // given (JCLAW-927). "core" is the one answer that would defeat the migration.
        for (int i = 0; i < cap() + 1; i++) storeCore("Core fact %02d".formatted(i), 0.9);
        classifyAllAs(MemoryCategory.CORE.label);

        CoreMemoryCapMigration.runForTest();

        assertEquals(cap(), liveCore(), "an answer of 'core' must be coerced away, not honoured");
    }

    @Test
    void leavesACorpusUnderTheCapAlone() {
        storeCore("The only core fact", 0.9);
        classifyAllAs(MemoryCategory.FACT.label);
        CoreMemoryCapMigration.runForTest();
        assertEquals(1, liveCore());
    }

    @Test
    void isSafeToRunAgainOnceTheCorpusIsInLine() {
        for (int i = 0; i < cap() + 3; i++) storeCore("Core fact %02d".formatted(i), 0.9);
        classifyAllAs(MemoryCategory.FACT.label);

        CoreMemoryCapMigration.runForTest();
        CoreMemoryCapMigration.runForTest();

        assertEquals(cap(), liveCore(), "a second pass has nothing left to do");
        assertNull(CoreMemoryCapMigration.status().error());
    }

    @Test
    void listsTheOverflowInPromptOrderSoSurvivorsAreThoseAlreadyLoaded() {
        // Guards the selection itself rather than its side effect: the migration must ask
        // the model about the overflow, never about a memory that stays core.
        for (int i = 0; i < cap(); i++) storeCore("KEEP core fact %02d".formatted(i), 0.95);
        storeCore("MOVE lower ranked", 0.20);
        var seen = new ArrayList<String>();
        CoreMemoryCapMigration.setClassifierForTest((a, texts) -> {
            seen.addAll(texts);
            return List.of(MemoryCategory.FACT.label);
        });

        CoreMemoryCapMigration.runForTest();

        assertEquals(1, seen.size(), "only the overflow is classified");
        assertTrue(seen.getFirst().contains("MOVE"), "and it is the lowest-ranked memory: " + seen);
    }

    // --- the cap is per agent, not a total across the instance ---

    @Test
    void twoAgentsEachWithinTheCapAreNotOverIt() {
        // Reported from the running instance: 20 core on one agent plus 1 on another read
        // as "21 stored, 20 allowed" and lit the migrate button, but migrate() skips any
        // agent at or under the cap, so every pass moved nothing and the panel stayed over
        // the limit. status() was summing across agents while migrate() enforced per agent.
        for (int i = 0; i < cap(); i++) storeCore("main core fact " + i, 0.9);
        storeCoreFor(otherAgent(), "the other agent's single core fact", 0.9);

        var s = CoreMemoryCapMigration.status();
        assertEquals(cap(), s.liveCore(),
                "liveCore must describe the agent the cap governs, not the instance total");
        assertFalse(s.overCap(),
                "no agent exceeds the cap, so nothing is over it");
    }

    @Test
    void aCorpusNoMigrationCanFixIsNotOfferedOne() {
        // The user-visible half of the same bug: the button was enabled for a state the
        // pass could never change.
        for (int i = 0; i < cap(); i++) storeCore("main core fact " + i, 0.9);
        storeCoreFor(otherAgent(), "the other agent's single core fact", 0.9);

        var refusal = CoreMemoryCapMigration.start();
        assertNotNull(refusal, "a migration that cannot move anything must be refused");
        assertTrue(refusal.contains("nothing to migrate"), refusal);
    }

    @Test
    void oneAgentOverTheCapIsStillDetectedWhenAnotherIsUnderIt() {
        // Guards the opposite error: a max that ignored agents past the first, or a fix
        // that made overCap unreachable with more than one agent.
        storeCore("the quiet agent's only core fact", 0.9);
        var busy = otherAgent();
        for (int i = 0; i < cap() + 3; i++) storeCoreFor(busy, "busy core fact " + i, 0.9);

        var s = CoreMemoryCapMigration.status();
        assertEquals(cap() + 3L, s.liveCore(), "the worst agent is what the panel must report");
        assertTrue(s.overCap(), "an agent past the cap must still be detected");
    }

    // --- the per-agent breakdown ---

    @Test
    void statusNamesEachAgentHoldingCoreMemoriesBusiestFirst() {
        // A single number cannot say where the pressure is: "20 of 20" is the same
        // reading whether one agent is at the cap or five are spread beneath it.
        storeCore("main core fact", 0.9);
        var busy = otherAgent();
        for (int i = 0; i < 3; i++) storeCoreFor(busy, "busy core fact " + i, 0.9);

        var rows = CoreMemoryCapMigration.status().agents();
        assertEquals(2, rows.size(), "both agents holding core memories must appear: " + rows);
        assertEquals(busy.name, rows.getFirst().agentName(), "busiest agent first");
        assertEquals(3L, rows.getFirst().core());
        assertEquals(agent.name, rows.get(1).agentName());
        assertEquals(1L, rows.get(1).core());
    }

    @Test
    void agentsWithNoCoreMemoriesAreAbsentRatherThanListedAsZero() {
        // What keeps the payload small: this instance has 489 agents and two of them hold
        // a core memory. Listing zeros would make a polled endpoint carry the agent table.
        storeCore("the only core fact", 0.9);
        otherAgent();

        var rows = CoreMemoryCapMigration.status().agents();
        assertEquals(1, rows.size(), "only the agent holding a core memory belongs: " + rows);
        assertEquals(agent.name, rows.getFirst().agentName());
    }

    @Test
    void theBreakdownFlagsWhichAgentIsOverTheCap() {
        storeCore("the quiet agent's only core fact", 0.9);
        var busy = otherAgent();
        for (int i = 0; i < cap() + 2; i++) storeCoreFor(busy, "busy core fact " + i, 0.9);

        var rows = CoreMemoryCapMigration.status().agents();
        assertTrue(rows.getFirst().overCap(), "the agent past the cap must be marked");
        assertFalse(rows.get(1).overCap(), "the one within it must not be");
    }

    @Test
    void anEmptyCorpusReportsNoAgentsAndIsNotOverTheCap() {
        var s = CoreMemoryCapMigration.status();
        assertTrue(s.agents().isEmpty());
        assertEquals(0L, s.liveCore(), "no core memories means zero, not a stale maximum");
        assertFalse(s.overCap());
    }
}
