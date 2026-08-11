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
 * <p>Now also the two later shapes the same UAT sweep found on the same path: an instruction
 * to drive the tool (JCLAW-1051), and a fact the request only presupposed (JCLAW-1055).
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

    private static MemoryAutoCapture.Extractor extractorReturning(String... texts) {
        var memories = java.util.Arrays.stream(texts)
                .map("{\"text\":\"%s\",\"category\":\"preference\",\"importance\":0.7}"::formatted)
                .collect(java.util.stream.Collectors.joining(","));
        var json = "{\"memories\":[%s]}".formatted(memories);
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
    void anInstructionToDriveTheToolIsNotStoredEither() {
        // JCLAW-1051. Both texts are verbatim from UAT, which stored them as `preference`
        // memories; the first then ranked ABOVE the real fact when recalling that topic.
        // Neither carries a removal verb, so the forget guard above does not see them.
        var a = agent("tool-note");

        MemoryAutoCapture.capture(String.valueOf(a.id), a.name,
                "Use your memory tool's recall action to search for anything about schooling.",
                "The recall action returned nothing for that term.",
                extractorReturning("The user wants the assistant to use the recall action of its "
                        + "memory tool to search for anything about schooling."),
                freshBreaker());

        assertEquals(0L, Memory.count("agent.id = ?1", a.id),
                "an instruction to operate the memory tool was stored as a durable memory");
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

    @Test
    void whatTheRequestOnlyPresupposesIsNotStoredAsAFact() {
        // JCLAW-1055, verbatim from UAT: a forget against a store holding no dentist. Forget
        // correctly reported nothing to remove, and capture wrote down the request's
        // presupposition as an entity. Neither guard above sees it - the text carries no
        // removal verb and names no tool, because it is not a note about the request at all.
        var a = agent("presupposition");

        MemoryAutoCapture.capture(String.valueOf(a.id), a.name,
                "Use your memory tool to forget my dentist's name.",
                "I have no stored memory of your dentist's name, so there was nothing to remove.",
                extractorReturning("The user has a dentist."),
                freshBreaker());

        assertEquals(0L, Memory.count("agent.id = ?1", a.id),
                "the request's presupposition was stored as a fact - an existence claim about "
                        + "the operator that they never made");
    }

    @Test
    void aFactStatedAlongsideTheRequestIsStillCaptured() {
        // The AC that stops the fix from suppressing real content: refusing the whole turn
        // would pass the test above and lose the name the operator actually gave.
        var a = agent("presupposition-mixed");

        MemoryAutoCapture.capture(String.valueOf(a.id), a.name,
                "Forget my dentist's name, it's Dr Vela.", "Done.",
                extractorReturning("The user has a dentist.", "The user's dentist is Dr Vela."),
                freshBreaker());

        assertEquals(1L, Memory.count("agent.id = ?1", a.id),
                "expected exactly the asserted fact to survive");
        var kept = Memory.<Memory>find("agent.id = ?1", a.id).<Memory>first();
        assertTrue(kept.text.contains("Vela"), "expected the stated fact, got: " + kept.text);
    }
}
