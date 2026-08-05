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
}
