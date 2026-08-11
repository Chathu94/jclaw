import memory.JpaMemoryStore;
import memory.MemoryAutoCapture;
import memory.MemoryForgetLog;
import memory.MemoryStoreFactory;
import models.Agent;
import models.Memory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import utils.CircuitBreaker;

/**
 * JCLAW-1056: a memory must come from the operator, not from the assistant answering them.
 *
 * <p>UAT asked "what did I tell you about my accountant", recall returned an unrelated
 * optometrist memory, and capture stored a restatement of it — every content token lifted
 * from the assistant turn. {@code EXTRACTION_INSTRUCTIONS} bars exactly this and the model
 * did it anyway, which is why the guard is deterministic rather than another prompt line.
 *
 * <p>The stakes are higher than the phrasing churn it was found causing: the assistant turn
 * carries tool output, so without this whatever a fetch returns can become a durable fact
 * about the operator.
 */
class MemoryAssistantSourceCaptureTest extends UnitTest {

    @BeforeEach
    void setup() {
        LuceneTestSync.openForTest();
        Fixtures.deleteDatabase();
        MemoryStoreFactory.reset();
        MemoryForgetLog.clearForTest();
        JpaMemoryStore.setEmbedderForTest(text -> {
            var v = new float[16];
            v[Math.floorMod(text == null ? 0 : text.hashCode(), v.length)] = 1f;
            return v;
        });
        MemoryStoreFactory.setForTest(new JpaMemoryStore(true, false));
    }

    @AfterEach
    void teardown() {
        JpaMemoryStore.setEmbedderForTest(null);
        MemoryStoreFactory.reset();
        MemoryForgetLog.clearForTest();
        LuceneTestSync.release();
    }

    private CircuitBreaker freshBreaker() {
        return new CircuitBreaker(20, 0.5, 5, 30_000L);
    }

    private Agent agent(String name) {
        var a = new Agent();
        a.name = name;
        a.modelProvider = "openrouter";
        a.modelId = "gpt-4.1";
        a.save();
        return a;
    }

    private static MemoryAutoCapture.Extractor extractorReturning(String... texts) {
        var memories = java.util.Arrays.stream(texts)
                .map("{\"text\":\"%s\",\"category\":\"entity\",\"importance\":0.6}"::formatted)
                .collect(java.util.stream.Collectors.joining(","));
        return _ -> "{\"memories\":[%s]}".formatted(memories);
    }

    @Test
    void aFactOnlyTheAssistantStatedIsNotCaptured() {
        var a = agent("assistant-sourced");

        MemoryAutoCapture.capture(String.valueOf(a.id), a.name,
                "What did I tell you about my accountant?",
                "I don't have any memories stored about your accountant. The only recalled "
                        + "memory is about your optometrist, Dr Kerans.",
                extractorReturning("The user sees Dr Kerans as their optometrist."),
                freshBreaker());

        assertEquals(0L, Memory.count("agent.id = ?1", a.id),
                "a fact the operator never stated was captured from the assistant's own words");
    }

    @Test
    void toolOutputSurfacedByTheAssistantIsNotCaptured() {
        // The reason this guard is worth having rather than leaving to consolidation: recall
        // output merely churns phrasing, but any tool result reaching the assistant turn can
        // otherwise be laundered into a durable fact about the operator.
        var a = agent("tool-sourced");

        MemoryAutoCapture.capture(String.valueOf(a.id), a.name,
                "Can you open that link and tell me what it says?",
                "The page states that the recommended retention period is ninety days.",
                extractorReturning("The recommended retention period is ninety days."),
                freshBreaker());

        assertEquals(0L, Memory.count("agent.id = ?1", a.id),
                "content fetched by a tool was stored as a durable memory");
    }

    @Test
    void aFactTheAssistantOnlyHelpedResolveIsStillCaptured() {
        // The guard must reject assistant-sourced SUBSTANCE, not assistant-sourced words. The
        // prompt supplies the assistant turn precisely so "he" can be resolved to "Arun", and
        // suppressing the whole turn would pass both tests above while breaking that.
        var a = agent("resolution");

        MemoryAutoCapture.capture(String.valueOf(a.id), a.name,
                "He goes by Bo now.", "Your son Arun? Noted.",
                extractorReturning("The user's son Arun goes by Bo."),
                freshBreaker());

        assertEquals(1L, Memory.count("agent.id = ?1", a.id),
                "a fact the user stated was dropped because the assistant resolved its subject");
        var kept = Memory.<Memory>find("agent.id = ?1", a.id).<Memory>first();
        assertTrue(kept.text.contains("Arun"), "expected the resolved fact, got: " + kept.text);
    }
}
