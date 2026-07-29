import agents.ToolRegistry;
import models.Agent;
import models.AgentToolConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.AgentService;
import services.Tx;
import services.evals.EvalCapture;
import services.evals.EvalCase;
import services.evals.EvalCheck;
import services.evals.EvalSuite;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Per-suite calibration of the eval agent (JCLAW-883): a suite declares the tools
 * it needs and capture grants exactly those before a sweep.
 *
 * <p>Without it a suite's pass rate depends on whatever the last sweep or the last
 * click in the agent editor left behind, which looks identical to a quality signal.
 *
 * <p>Everything here seeds and reads through {@link #commitInFreshTx} because
 * {@code EvalCapture.calibrate} writes on its own committed transaction. Seeding
 * the agent in the ambient test transaction instead fails with a foreign-key
 * violation — the calibrating thread cannot see an uncommitted row. That is the
 * same visibility boundary the production bug fell foul of from the other side:
 * {@code Tx.run} joined the request's transaction, so the grants stayed invisible
 * to the sweep's virtual threads.
 */
class EvalCalibrationTest extends UnitTest {

    /**
     * JCLAW-894: these tests assert exactly which tools an agent is offered, which
     * reads the process-global registry. A concurrently-running class that
     * republishes it changes the answer — ToolSystemTest's four-tool stub does not
     * even contain datetime, which the first assertion below expects.
     */
    @BeforeEach
    void lockRegistry() {
        ToolRegistrySync.canonicalForTest();
    }

    @AfterEach
    void unlockRegistry() {
        ToolRegistrySync.release();
    }

    /** Runs {@code block} in its own committed transaction — the established seeding idiom. */
    private static <T> T commitInFreshTx(Supplier<T> block) {
        var ref = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                ref.set(Tx.run(block::get));
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (err.get() != null) throw new IllegalStateException(err.get());
        return ref.get();
    }

    private static EvalSuite suiteNeeding(String... tools) {
        return new EvalSuite("calibration-fixture", "fixture", List.of(tools),
                List.of(new EvalCase("a-case", "hello", "a case",
                        List.of(EvalCheck.of(EvalCheck.Kind.CONTAINS_ALL, List.of("hi"))))));
    }

    private static Long seedAgent(String name) {
        return commitInFreshTx(() -> AgentService.create(name, "openrouter", "gpt-4.1").id);
    }

    private static void calibrate(EvalSuite suite, Long agentId) {
        commitInFreshTx(() -> {
            EvalCapture.calibrate(suite, (Agent) Agent.findById(agentId));
            return null;
        });
    }

    private static List<String> grantedTo(Long agentId) {
        return commitInFreshTx(() -> AgentToolConfig.findByAgent((Agent) Agent.findById(agentId)).stream()
                .filter(c -> c.enabled)
                .map(c -> c.toolName)
                .sorted()
                .toList());
    }

    private static List<String> offeredTo(Long agentId) {
        return commitInFreshTx(() -> ToolRegistry.getToolDefsForAgent((Agent) Agent.findById(agentId)).stream()
                .map(d -> d.function().name())
                .sorted()
                .toList());
    }

    private static void drop(Long agentId) {
        commitInFreshTx(() -> {
            var agent = (Agent) Agent.findById(agentId);
            if (agent != null) {
                AgentToolConfig.delete("agent = ?1", agent);
                agent.delete();
            }
            return null;
        });
    }

    @Test
    void grantsExactlyWhatTheSuiteDeclaresAndRevokesTheRest() {
        var id = seedAgent(Agent.EVALTEST_AGENT_NAME);
        try {
            calibrate(suiteNeeding("datetime", "web_search"), id);
            assertEquals(List.of("datetime", "web_search"), grantedTo(id));

            // A second suite needing less must REVOKE, not accumulate — otherwise a
            // sweep inherits reach from whichever suite ran before it.
            calibrate(suiteNeeding("datetime"), id);
            assertEquals(List.of("datetime"), grantedTo(id));

            // And the granted tool is genuinely reachable, not merely recorded.
            assertEquals(List.of("datetime"), offeredTo(id));
        } finally {
            drop(id);
        }
    }

    @Test
    void aSuiteDeclaringNoToolsLeavesTheAgentWithNone() {
        var id = seedAgent(Agent.EVALTEST_AGENT_NAME);
        try {
            calibrate(suiteNeeding("datetime"), id);
            calibrate(suiteNeeding(), id);

            assertTrue(grantedTo(id).isEmpty());
            assertTrue(offeredTo(id).isEmpty(),
                    "grounding and structured-output declare nothing and must reach nothing");
        } finally {
            drop(id);
        }
    }

    @Test
    void anAgentTheOperatorConfiguredIsNeverRewritten() {
        // Pointing capture at your own agent must not rewrite its configuration. The
        // suite's cases then fail naming the missing tool, which is the correct
        // outcome for an agent this code does not own.
        var id = seedAgent("operators-own-agent");
        try {
            calibrate(suiteNeeding("datetime", "web_search"), id);
            assertTrue(grantedTo(id).isEmpty(), "no grants were written to a non-eval agent");
        } finally {
            drop(id);
        }
    }
}
