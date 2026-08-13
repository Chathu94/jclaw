import memory.MemoryAutoCapture;
import models.Agent;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.LoadTestRunner;

import java.util.List;

/**
 * JCLAW-534: per-agent memory auto-capture resolution. Capture is on by default;
 * the extractor model is the agent's default unless an explicit override is set.
 */
class AgentMemorySettingsTest extends UnitTest {

    private static Agent agent() {
        var a = new Agent();
        a.name = "mem-agent";
        a.modelProvider = "openrouter";
        a.modelId = "gpt-4.1";
        return a;
    }

    @Test
    void autocaptureEnabledByDefault() {
        assertTrue(agent().memoryAutocaptureEnabled, "auto-capture must be on by default");
    }

    @Test
    void modelInheritsAgentDefaultWhenNoOverride() {
        var a = agent();
        assertEquals("openrouter", a.autocaptureProviderEffective());
        assertEquals("gpt-4.1", a.autocaptureModelEffective());
    }

    @Test
    void explicitOverrideWins() {
        var a = agent();
        a.memoryAutocaptureProvider = "ollama-cloud";
        a.memoryAutocaptureModel = "deepseek-v4-flash";
        assertEquals("ollama-cloud", a.autocaptureProviderEffective());
        assertEquals("deepseek-v4-flash", a.autocaptureModelEffective());
    }

    @Test
    void blankOverrideFallsBackToAgentDefault() {
        var a = agent();
        a.memoryAutocaptureProvider = "";
        a.memoryAutocaptureModel = "  ";
        assertEquals("openrouter", a.autocaptureProviderEffective());
        assertEquals("gpt-4.1", a.autocaptureModelEffective());
    }

    @Test
    void subagentDetectedByParent() {
        // JCLAW-539: auto-capture skips subagents (agents with a parent).
        var root = agent();
        assertFalse(root.isSubagent(), "a root agent has no parent");
        var child = agent();
        child.parentAgent = root;
        assertTrue(child.isSubagent(), "a spawned subagent has a parent");
    }

    @Test
    void captureEligibleForRootEnabledAgent() {
        // JCLAW-539 + JCLAW-534: a root agent with capture enabled is eligible —
        // this is the branch captureAsync takes for operator-facing turns.
        assertTrue(MemoryAutoCapture.captureEligible(agent()));
    }

    @Test
    void captureSkipsSubagents() {
        // JCLAW-539: the eligibility gate excludes a subagent even when capture
        // is enabled, so captureAsync no-ops for delegated (subagent) turns.
        var child = agent();
        child.parentAgent = agent();
        assertTrue(child.memoryAutocaptureEnabled, "guard: capture is on by default");
        assertFalse(MemoryAutoCapture.captureEligible(child),
                "a subagent must be excluded from auto-capture");
    }

    @Test
    void captureSkipsDisabledRootAgent() {
        // JCLAW-534: a root agent with capture turned off is not eligible.
        var a = agent();
        a.memoryAutocaptureEnabled = false;
        assertFalse(MemoryAutoCapture.captureEligible(a));
    }

    /**
     * JCLAW-942: benchmark turns are generated traffic, so distilling them stores a
     * memory of a load test — one run left five rows that then rank against real
     * recalls. ToolRegistry already withholds the memory tool from these agents; this
     * is the passive half.
     */
    @Test
    void captureSkipsTheBenchmarkAgents() {
        for (var name : List.of(LoadTestRunner.LOADTEST_AGENT_NAME,
                LoadTestRunner.LOADTEST_TOOLS_AGENT_NAME)) {
            var a = agent();
            a.name = name;
            assertTrue(a.memoryAutocaptureEnabled, "guard: capture is on by default");
            assertFalse(MemoryAutoCapture.captureEligible(a),
                    name + " must be excluded from auto-capture");
        }
    }
}
