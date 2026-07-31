import models.Agent;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.LoadTestRunner;

/**
 * JCLAW-840: the loadtest harness can drive an existing agent by name so a run
 * ships a real tool array, which the two benchmark twins cannot — they pin their
 * surface to zero and one tool respectively.
 *
 * <p>What is covered here is the destructive half of that: driving a real agent
 * means overwriting its provider and model for the duration, and a restore that
 * silently fails leaves the operator's agent pointing at the wrong model. That
 * failure is invisible — no exception, no error, just a different model answering
 * until someone notices the bill. The parameter guards on the endpoint are not
 * covered because their failure mode is a confusing 400, which is loud and
 * self-correcting.
 */
class LoadTestAgentBindingTest extends UnitTest {

    private Agent newAgent(String name) {
        return AgentService.create(name, "ollama-cloud", "kimi-k2.6");
    }

    @Test
    void bindingOverwritesProviderAndModelAndReportsTheOriginals() {
        Fixtures.deleteDatabase();
        var agent = newAgent("bind-target");

        var setup = LoadTestRunner.applyBinding(agent, "openrouter", "anthropic/claude-sonnet-4.5");

        assertEquals("openrouter", agent.modelProvider, "provider should be overridden for the run");
        assertEquals("anthropic/claude-sonnet-4.5", agent.modelId, "model should be overridden for the run");
        assertEquals("ollama-cloud", setup.savedProvider(), "setup must carry the ORIGINAL provider");
        assertEquals("kimi-k2.6", setup.savedModel(), "setup must carry the ORIGINAL model");
        assertEquals("bind-target", setup.namedAgent());
        assertEquals(agent.id.longValue(), setup.agentId());
    }

    @Test
    void restorePutsBackExactlyWhatBindingReplaced() {
        Fixtures.deleteDatabase();
        var agent = newAgent("restore-target");
        var originalProvider = agent.modelProvider;
        var originalModel = agent.modelId;

        var setup = LoadTestRunner.applyBinding(agent, "openrouter", "anthropic/claude-sonnet-4.5");
        assertNotEquals(originalModel, agent.modelId, "guard: binding must actually have changed something");

        LoadTestRunner.applyRestore(agent, setup);

        assertEquals(originalProvider, agent.modelProvider, "provider must be handed back");
        assertEquals(originalModel, agent.modelId, "model must be handed back");
    }

    @Test
    void restoreSurvivesAReloadSoItIsNotJustInMemory() {
        Fixtures.deleteDatabase();
        var agent = newAgent("persist-target");

        var setup = LoadTestRunner.applyBinding(agent, "openrouter", "anthropic/claude-sonnet-4.5");
        LoadTestRunner.applyRestore(agent, setup);

        // The in-memory assertions above would pass even if save() never ran, so
        // re-read by name: the operator's agent is what is on disk, not what is
        // in this thread's entity.
        var reloaded = Agent.findByName("persist-target");
        assertEquals("ollama-cloud", reloaded.modelProvider);
        assertEquals("kimi-k2.6", reloaded.modelId);
    }

    @Test
    void bindingIsIdempotentUnderRepeatedRestore() {
        Fixtures.deleteDatabase();
        var agent = newAgent("double-restore");

        var setup = LoadTestRunner.applyBinding(agent, "openrouter", "anthropic/claude-sonnet-4.5");
        LoadTestRunner.applyRestore(agent, setup);
        // A run that throws restores in its finally; a caller that also restores
        // explicitly must not corrupt the agent by applying the same setup twice.
        LoadTestRunner.applyRestore(agent, setup);

        assertEquals("ollama-cloud", agent.modelProvider);
        assertEquals("kimi-k2.6", agent.modelId);
    }
}
