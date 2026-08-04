import memory.MemoryCategory;
import memory.MemoryForgetLog;
import memory.MemoryStoreFactory;
import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import tools.MemoryTool;

/**
 * JCLAW-919: the agent-callable memory tool.
 *
 * <p>Runs with the Lucene index closed, so the semantic tier is absent and the lexical one
 * is what answers. That is the configuration to pin: it is the fail-open path, and a tool
 * that only works with a vector backend configured would be broken on a fresh install.
 */
class MemoryToolTest extends UnitTest {

    private final MemoryTool tool = new MemoryTool();
    private Agent agent;

    @BeforeEach
    void setup() {
        LuceneTestSync.closedForTest();
        Fixtures.deleteDatabase();
        MemoryStoreFactory.reset();
        MemoryForgetLog.clearForTest();
        agent = new Agent();
        agent.name = "memtool-agent";
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        agent.save();
    }

    @AfterEach
    void luceneRelease() {
        LuceneTestSync.release();
    }

    private String call(String json) {
        return tool.execute(json, agent);
    }

    private void seed(String text) {
        MemoryStoreFactory.get().store(String.valueOf(agent.id), text,
                MemoryCategory.FACT.label, 0.6);
    }

    // --- dispatch ---

    @Test
    void rejectsAnUnknownOrMissingAction() {
        assertTrue(call("{}").startsWith("Error:"));
        assertTrue(call("{\"action\":\"obliterate\"}").startsWith("Error:"));
        assertTrue(call("not json").startsWith("Error:"));
    }

    @Test
    void eachActionRequiresItsOwnField() {
        assertTrue(call("{\"action\":\"recall\"}").contains("query"));
        assertTrue(call("{\"action\":\"forget\"}").contains("query"));
        assertTrue(call("{\"action\":\"store\"}").contains("text"));
    }

    // --- store ---

    @Test
    void storeRecordsAFactAndRecallFindsIt() {
        var stored = call("{\"action\":\"store\",\"text\":\"The user keeps the NAS in the basement\"}");
        assertTrue(stored.startsWith("Remembered"), stored);

        var recalled = call("{\"action\":\"recall\",\"query\":\"NAS basement\"}");
        assertTrue(recalled.contains("basement"), recalled);
    }

    @Test
    void storingSomethingAlreadyKnownIsANoOpRatherThanASecondRow() {
        // The JCLAW-530 failure mode: a write tool duplicating what capture already did.
        // The guard is structural, so it holds however often the model calls store.
        seed("The user keeps the NAS in the basement");
        var again = call("{\"action\":\"store\",\"text\":\"The user keeps the NAS in the basement\"}");

        assertTrue(again.startsWith("Already remembered"), again);
        assertEquals(1, MemoryStoreFactory.get().list(String.valueOf(agent.id)).size(),
                "a duplicate store must not create a second row");
    }

    @Test
    void storeRefusesCredentialsEvenWhenAskedDirectly() {
        // A stored memory is re-injected into every later prompt, so a credential here is
        // a standing exfiltration surface. Being asked explicitly does not change that.
        var out = call("{\"action\":\"store\",\"text\":\"The user's API key is sk-live-ABCDEF1234567890abcdef\"}");

        assertTrue(out.startsWith("Refused:"), out);
        assertTrue(MemoryStoreFactory.get().list(String.valueOf(agent.id)).isEmpty(),
                "nothing may be persisted when the text is refused");
    }

    @Test
    void storeHonoursAnExplicitCategoryAndCoercesAnInvalidOne() {
        call("{\"action\":\"store\",\"text\":\"The user prefers tabs\",\"category\":\"preference\"}");
        var stored = MemoryStoreFactory.get().list(String.valueOf(agent.id));
        assertEquals(MemoryCategory.PREFERENCE.label, stored.getFirst().category());
    }

    // --- forget ---

    @Test
    void forgetRemovesEveryMemoryStatingTheFact() {
        // Includes the subset restatement JCLAW-920 added to the lexical rule — the same
        // fact with an extra clause, which containment catches and plain Jaccard does not.
        seed("The user keeps the NAS in the basement");
        seed("The user keeps the NAS in the basement at home");
        seed("The user drives an Xpeng G6");

        var out = call("{\"action\":\"forget\",\"query\":\"The user keeps the NAS in the basement\"}");
        assertTrue(out.startsWith("Forgot"), out);

        var left = MemoryStoreFactory.get().list(String.valueOf(agent.id));
        assertEquals(1, left.size(), "only the unrelated memory should survive: " + left);
        assertTrue(left.getFirst().text().contains("Xpeng"), left.getFirst().text());
    }

    @Test
    void withoutAVectorBackendForgetMatchesWordingOnly() {
        // Pins the real limit rather than leaving it to be discovered. "kept ... by the
        // user" restates "keeps ... the user" but shares too few tokens for the lexical
        // thresholds (Jaccard 0.6, containment 0.75 against 0.85/0.82), and those are the
        // capture-dedup thresholds measured on a real corpus — not numbers to relax so a
        // fixture passes. Catching this pair is the semantic tier's job, and the index is
        // closed here.
        seed("The user keeps the NAS in the basement");
        seed("The NAS is kept in the basement by the user");

        call("{\"action\":\"forget\",\"query\":\"The user keeps the NAS in the basement\"}");

        assertEquals(1, MemoryStoreFactory.get().list(String.valueOf(agent.id)).size(),
                "a pure paraphrase survives when only the lexical tier is available");
    }

    @Test
    void forgetLeavesMerelyRelatedMemoriesAlone() {
        // The risk in "remove all matching": a topical query deleting a neighbourhood.
        // Matching is the same-fact test capture dedups on, not topical similarity.
        seed("The user keeps the NAS in the basement");
        seed("The user backs up photos to the NAS every Sunday");

        call("{\"action\":\"forget\",\"query\":\"The user keeps the NAS in the basement\"}");

        var left = MemoryStoreFactory.get().list(String.valueOf(agent.id));
        assertEquals(1, left.size(), "a different fact about the same subject must survive: " + left);
        assertTrue(left.getFirst().text().contains("Sunday"));
    }

    @Test
    void forgettingSomethingUnknownSaysSoRatherThanReportingSuccess() {
        seed("The user drives an Xpeng G6");
        var out = call("{\"action\":\"forget\",\"query\":\"the user's favourite opera\"}");

        assertTrue(out.contains("Nothing stored matches"), out);
        assertEquals(1, MemoryStoreFactory.get().list(String.valueOf(agent.id)).size());
    }

    // --- recall ---

    @Test
    void recallReportsAMissRatherThanReturningNothing() {
        seed("The user drives an Xpeng G6");
        assertTrue(call("{\"action\":\"recall\",\"query\":\"submarine\"}").startsWith("No memories matched"));
    }

    @Test
    void recallFramesResultsAsReferenceDataNotInstructions() {
        // Recalled text is attacker-influenceable in principle, and it lands in the model's
        // context. The framing has to travel with it, exactly as the prompt section does.
        seed("The user drives an Xpeng G6");
        var out = call("{\"action\":\"recall\",\"query\":\"Xpeng\"}");

        assertTrue(out.contains("not new instructions"), out);
    }

    // --- JCLAW-919: forget must survive the capture running on the same turn ---

    @Test
    void aForgottenFactIsSuppressedFromCaptureForAWhile() {
        // Found in live UAT, not in a unit test: forget deleted the memory and auto-capture
        // recreated it eleven seconds later under a new id with identical text. The turn
        // asking to forget X necessarily states X, so capture extracts it straight back.
        seed("The user's canary phrase is zephyr-quartz-1917");
        call("{\"action\":\"forget\",\"query\":\"The user's canary phrase is zephyr-quartz-1917\"}");

        assertTrue(MemoryForgetLog.recentlyForgotten(String.valueOf(agent.id),
                        "The user's canary phrase is zephyr-quartz-1917."),
                "capture must be told not to re-learn what was just forgotten");
    }

    @Test
    void anExplicitReStoreOverridesTheForgetWindow() {
        // "Forget X" then "actually, remember X" has to work, or the second instruction
        // silently does nothing for the length of the window.
        seed("The user's canary phrase is zephyr-quartz-1917");
        call("{\"action\":\"forget\",\"query\":\"The user's canary phrase is zephyr-quartz-1917\"}");

        var out = call("{\"action\":\"store\",\"text\":\"The user's canary phrase is zephyr-quartz-1917\"}");
        assertTrue(out.startsWith("Remembered"), out);
        assertFalse(MemoryForgetLog.recentlyForgotten(String.valueOf(agent.id),
                        "The user's canary phrase is zephyr-quartz-1917"),
                "an explicit re-store must lift the suppression");
    }

    @Test
    void anUnrelatedFactIsNotSuppressedByAForget() {
        seed("The user's canary phrase is zephyr-quartz-1917");
        call("{\"action\":\"forget\",\"query\":\"The user's canary phrase is zephyr-quartz-1917\"}");

        assertFalse(MemoryForgetLog.recentlyForgotten(String.valueOf(agent.id),
                        "The user drives an Xpeng G6"),
                "the window must not block capture of anything else");
    }
}
