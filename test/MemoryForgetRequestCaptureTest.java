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
 * JCLAW-1048: a forget must not leave behind a memory of having been asked.
 *
 * <p>The turn that asks to forget something is itself extractable — "the user wants X
 * forgotten" — and that stores as a durable, well-keyed memory. UAT found the agent then
 * reading it as standing policy and refusing the whole subject, so an operator who asked to
 * forget one fact got a permanent gag order on it instead.
 *
 * <p>{@link MemoryForgetLog#recentlyForgotten} cannot catch this: it tests whether a
 * candidate <em>restates the deleted fact</em>, and this restates the <em>request</em>. The
 * offending note repeated roughly "marlow"/"eats" out of the forgotten fact's eight content
 * tokens, far below that rule's 0.6 floor.
 */
class MemoryForgetRequestCaptureTest extends UnitTest {

    private static final String FORGOTTEN_FACT =
            "The user's beagle Marlow eats grain-free food after a vet appointment.";

    @BeforeEach
    void setup() {
        LuceneTestSync.openForTest();
        Fixtures.deleteDatabase();
        MemoryStoreFactory.reset();
        MemoryForgetLog.clearForTest();
        // A basis vector per distinct text: identical text embeds identically (so dedup still
        // works) while unrelated texts land orthogonal, which a constant vector would not.
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

    private static MemoryAutoCapture.Extractor extractorReturning(String text) {
        var json = "{\"memories\":[{\"text\":\"%s\",\"category\":\"preference\",\"importance\":0.7}]}"
                .formatted(text);
        return _ -> json;
    }

    /** Store the fact, then forget it exactly as {@code MemoryTool.forget} does. */
    private void storeThenForget(Agent a) {
        var m = new Memory();
        m.agent = a;
        m.text = FORGOTTEN_FACT;
        m.category = "core";
        m.importance = 0.9;
        m.save();
        MemoryForgetLog.noteForgotten(String.valueOf(a.id), m.text);
        m.delete();
    }

    @Test
    void theRequestToForgetIsNotItselfStored() {
        var a = agent("forget-note");
        storeThenForget(a);

        MemoryAutoCapture.capture(String.valueOf(a.id), a.name,
                "Use your memory tool to forget everything you know about what Marlow eats.",
                "Done - I no longer have any stored memory about what Marlow eats.",
                extractorReturning("The user wants the assistant to forget everything it knows "
                        + "about what Marlow eats."),
                freshBreaker());

        assertEquals(0L, Memory.count("agent.id = ?1", a.id),
                "the forget request was stored as a memory - it will be recalled on the very "
                        + "subject it was meant to erase");
    }

    @Test
    void aLaterFactAboutTheSameSubjectIsStillCaptured() {
        // The guard must reject the shape of the note, not blacklist its subject. Without this
        // the obvious fix - suppress anything mentioning Marlow - would pass the test above.
        var a = agent("forget-subject");
        storeThenForget(a);

        MemoryAutoCapture.capture(String.valueOf(a.id), a.name,
                "Forget what Marlow eats.", "Done.",
                extractorReturning("The user wants the assistant to forget everything it knows "
                        + "about what Marlow eats."),
                freshBreaker());
        assertEquals(0L, Memory.count("agent.id = ?1", a.id), "precondition: the note is suppressed");

        // A later turn states a genuinely new fact about the same subject.
        MemoryForgetLog.clearForTest();
        MemoryAutoCapture.capture(String.valueOf(a.id), a.name,
                "Marlow turned four last week.", "Happy birthday to Marlow!",
                extractorReturning("The user's beagle Marlow turned four years old."),
                freshBreaker());

        assertEquals(1L, Memory.count("agent.id = ?1", a.id),
                "a new fact about the same subject must still be captured - the guard rejects "
                        + "the shape of a forget note, not the topic");
        var kept = Memory.<Memory>find("agent.id = ?1", a.id).<Memory>first();
        assertTrue(kept.text.contains("four"), "expected the new fact, got: " + kept.text);
    }
}
