import agents.SystemPromptAssembler;
import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;

/**
 * JCLAW-40: core-memory auto-load into the cacheable prefix, plus the recall
 * exclusion + importance blend in {@code SystemPromptAssembler}.
 */
class SystemPromptCoreMemoryTest extends UnitTest {

    @BeforeEach
    void setup() {
        // Seeding a memory triggers Memory @PostPersist Lucene indexing; force the
        // index closed (LIKE-fallback recall) and serialize against the other
        // Lucene tests, mirroring MemoryStoreTest.
        LuceneTestSync.closedForTest();
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        memory.MemoryStoreFactory.reset();
    }

    @AfterEach
    void release() {
        LuceneTestSync.release();
    }

    private Agent newAgent(String name) {
        return AgentService.create(name, "openrouter", "gpt-4.1");
    }

    private void store(Agent agent, String text, String category, double importance) {
        // Partition on the immutable agent id, matching production (JCLAW-531).
        memory.MemoryStoreFactory.get().store(String.valueOf(agent.id), text, category, importance);
    }

    private static int countOccurrences(String haystack, String needle) {
        int c = 0;
        int i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            c++;
            i += needle.length();
        }
        return c;
    }

    @Test
    void coreMemoriesAppearInCacheablePrefix() {
        var agent = newAgent("spa-core-1");
        store(agent,"MARKER_CORE_FACT operator is the sole admin", "core", 0.9);

        var prompt = SystemPromptAssembler.assemble(agent, null, null, "web").systemPrompt();
        int coreIdx = prompt.indexOf("MARKER_CORE_FACT");
        int marker = prompt.indexOf(SystemPromptAssembler.CACHE_BOUNDARY_MARKER);

        assertTrue(prompt.contains("## Core Memories"), "core memories header present");
        assertTrue(coreIdx >= 0, "core memory text injected");
        assertTrue(coreIdx < marker, "core memories must sit in the cacheable prefix (before the boundary)");
        assertTrue(prompt.contains("not instructions"),
                "core memory block must carry the untrusted-data framing (JCLAW-535)");
        assertTrue(prompt.contains("prefer the core memory") && prompt.contains("USER.md"),
                "core memory block must instruct the model to prefer a core memory over a "
                        + "more general, conflicting profile field like USER.md's Location");
    }

    @Test
    void belowThresholdCoreMemoryIsNotAutoLoaded() {
        var agent = newAgent("spa-core-2");
        store(agent,"MARKER_LOW below the default threshold", "core", 0.5);  // < 0.8

        var prompt = SystemPromptAssembler.assemble(agent, null, null, "web").systemPrompt();
        assertFalse(prompt.contains("MARKER_LOW"), "below-threshold core memory must not auto-load");
    }

    @Test
    void nonCoreCategoryIsNotAutoLoaded() {
        var agent = newAgent("spa-core-3");
        store(agent,"MARKER_FACT a mere high-importance fact", "fact", 0.99);

        var prompt = SystemPromptAssembler.assemble(agent, null, null, "web").systemPrompt();
        assertFalse(prompt.contains("## Core Memories"), "a non-core memory must not appear in the core block");
    }

    @Test
    void maxCountCapsTheCoreBlock() {
        var agent = newAgent("spa-core-4");
        for (int i = 0; i < 50; i++) {
            store(agent,"COREFILL%02d ".formatted(i) + "x".repeat(80), "core", 0.9);
        }
        var prompt = SystemPromptAssembler.assemble(agent, null, null, "web").systemPrompt();
        assertEquals(20, countOccurrences(prompt, "COREFILL"),
                "memory.coreload.maxCount is the block's only bound — exactly 20 of the 50 load");
    }

    @Test
    void aVerboseCoreMemoryIsStillLoadedWhole() {
        // JCLAW-955/979: length is not a ranking signal, so a long core memory is neither
        // truncated nor allowed to displace the ones ranked below it.
        var agent = newAgent("spa-core-verbose");
        store(agent, "COREBIG " + "x".repeat(4000), "core", 0.99);
        store(agent, "CORESMALL", "core", 0.98);

        var prompt = SystemPromptAssembler.assemble(agent, null, null, "web").systemPrompt();

        assertTrue(prompt.contains("COREBIG " + "x".repeat(4000)),
                "a verbose core memory must be injected in full, not clipped to a budget");
        assertTrue(prompt.contains("CORESMALL"),
                "and must not consume a budget that starves the memory ranked after it");
    }

    @Test
    void coreMemoryIsExcludedFromPerTurnRecall() {
        var agent = newAgent("spa-core-5");
        store(agent,"MARKER_DUAL widget preferences are important to track", "core", 0.9);

        // The userMessage is a substring of the memory text so the LIKE-fallback
        // recall returns it; the exclusion must keep it from being duplicated.
        var prompt = SystemPromptAssembler.assemble(agent, "widget preferences", null, "web").systemPrompt();
        assertTrue(prompt.contains("## Core Memories"), "core block present");
        assertEquals(1, countOccurrences(prompt, "MARKER_DUAL"),
                "a core memory must appear once (in the core block), not again in recall");
    }

    // NB: a positive "recall finds a non-core memory" assertion would depend on
    // the JVM-global Lucene search dialect being "none" (the LIKE fallback),
    // which concurrent search-mode tests can flip (JCLAW-428) — that path is
    // already covered reliably by MemoryStoreTest.storeAndSearch. The exclusion
    // test above stays robust because the core memory appears exactly once
    // whether or not recall finds it.
}
