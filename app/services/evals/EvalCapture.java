package services.evals;

import agents.AgentExecutionSink;
import agents.AgentRunner;
import com.google.gson.JsonParser;
import models.Agent;
import services.AttachmentService;
import services.EventLogger;
import utils.LatencyTrace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives a live agent through an {@link EvalSuite} and records what it produced,
 * in the same file format {@link EvalRunner} replays (JCLAW-883).
 *
 * <p>Capture and scoring stay separate on purpose. Scoring is pure and offline —
 * {@code ./jclaw.sh evals} boots no framework — while driving an agent needs JPA,
 * a provider and the tool registry. Keeping the recorded file as the boundary
 * means a sweep can be scored now, re-scored later against a changed suite, or
 * diffed against a baseline, without paying for the model twice.
 *
 * <h2>Why this costs the serving path nothing</h2>
 * <ul>
 *   <li><b>No conversation.</b> Turns run through {@link AgentRunner#runForTask},
 *       which manufactures a transient stub {@link models.Conversation} that is
 *       never persisted. There is no history row to clean up afterwards, which is
 *       a stronger guarantee than creating a throwaway conversation and deleting
 *       it — nothing exists to leak if the sweep dies midway.</li>
 *   <li><b>No memory writes.</b> {@code MemoryAutoCapture.captureAsync} fires from
 *       the two chat entrypoints, not from {@code runForTask}, so an eval sweep
 *       cannot teach the agent anything about its own eval questions.</li>
 *   <li><b>No latency samples.</b> The per-case {@link LatencyTrace} exists only
 *       to count model calls; {@link LatencyTrace#end()} is never called, so
 *       nothing reaches {@link utils.LatencyStats}. Hundreds of eval turns landing
 *       in the request-path histograms would corrupt the baseline JCLAW-833
 *       measures against. Belt and braces: {@code runForTask} never marks
 *       {@code PROLOGUE_DONE}, and {@code end()} discards any trace without it.</li>
 *   <li><b>Nothing runs unless asked.</b> The only caller is an operator-triggered
 *       endpoint; a normal turn never reaches this class.</li>
 * </ul>
 */
public final class EvalCapture {

    private static final String EVENT_CATEGORY = "evals";

    private EvalCapture() {}

    /**
     * A recorded run, shaped exactly like the file {@code --responses} consumes so
     * the two paths cannot drift: whatever this writes, the offline scorer reads.
     */
    public record Capture(String suite, int version, Map<String, EvalScorer.Response> responses) {}

    /**
     * One agent turn, as capture needs it: a prompt in, the final assistant text out,
     * everything else routed through the sink.
     *
     * <p>A parameter rather than a static test seam so a test can substitute a turn
     * without mutating process-global provider state — this suite runs its classes
     * concurrently, and a global the tests flip would make them order-dependent.
     */
    @FunctionalInterface
    public interface TurnRunner {
        String run(Agent agent, String prompt, AgentExecutionSink sink) throws Exception;
    }

    /**
     * Run every case in {@code suite} against {@code agent}, at most
     * {@code maxConcurrency} in front of the model at once.
     *
     * <p>A case whose turn throws is recorded with its reason rather than dropped,
     * so the scorer can report "the agent errored" distinctly from "the agent
     * answered wrongly" — a sweep during a provider outage should read as
     * unmeasured, not as a quality collapse.
     */
    public static Capture run(EvalSuite suite, Agent agent, int maxConcurrency) {
        return run(suite, agent, maxConcurrency,
                (a, prompt, sink) -> AgentRunner.runForTask(a, prompt, sink).content());
    }

    /**
     * Variant that takes the turn as a parameter — see {@link TurnRunner}. Public
     * because Play's tests live in the default package, and because substituting the
     * turn is a legitimate thing for a caller to want; the production overload above
     * is simply the one that supplies the real agent run.
     */
    public static Capture run(EvalSuite suite, Agent agent, int maxConcurrency, TurnRunner turns) {
        var captured = EvalRunner.mapCasesBounded(suite.cases(), maxConcurrency,
                testCase -> Map.entry(testCase.id(), captureOne(testCase, agent, turns)));

        // LinkedHashMap so the recorded file keeps suite order and two captures of
        // the same suite diff line by line, matching how the report already behaves.
        var responses = new LinkedHashMap<String, EvalScorer.Response>();
        captured.forEach(e -> responses.put(e.getKey(), e.getValue()));
        return new Capture(suite.id(), suite.version(), responses);
    }

    private static EvalScorer.Response captureOne(EvalCase testCase, Agent agent, TurnRunner turns) {
        // Counts this turn's model calls (JCLAW-882) without ever being ended — see
        // the class comment on why an eval turn must not reach LatencyStats. The
        // binding must be open BEFORE the turn starts: LlmProvider counts at its
        // dispatch point by reading the calling thread's binding, so a turn invoked
        // outside it would report zero calls and silently unscore max_llm_calls.
        var trace = LatencyTrace.forTurn("eval", null);
        var sink = new CaptureSink();
        try (var _ = LatencyTrace.bind(trace)) {
            var content = turns.run(agent, testCase.input(), sink);
            return new EvalScorer.Response(content, sink.toolsCalled(), trace.llmCallCount());
        } catch (Exception e) {
            EventLogger.warn(EVENT_CATEGORY,
                    "Eval case '%s' errored: %s".formatted(testCase.id(), e));
            return EvalScorer.Response.failed(e.toString());
        }
    }

    /**
     * Write target that keeps nothing. {@link AgentRunner#runForTask} routes every
     * persistence call through its sink, so supplying one that only observes is what
     * makes a sweep leave no rows behind — the same seam {@code TaskRunSink} uses to
     * redirect task fires away from the chat schema.
     *
     * <p>Its one job beyond discarding is recording tool names in call order, which
     * is what the {@code tool_called} / {@code tool_not_called} checks score against.
     */
    private static final class CaptureSink implements AgentExecutionSink {

        // ParallelToolExecutor commits results from the turn's thread, but tool
        // dispatch itself fans out across virtual threads; synchronize so a
        // multi-tool round cannot lose a name to a torn ArrayList write.
        private final List<String> tools = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void appendUserMessage(String content, List<AttachmentService.Input> attachments) {
            // The eval question is the input we already hold; nothing to record.
        }

        @Override
        public void appendAssistantMessage(String content, String toolCalls, String usageJson,
                                           String reasoning, boolean truncated) {
            if (toolCalls != null) recordToolName(toolCalls);
        }

        @Override
        public void appendToolResult(String toolCallId, String result, String structuredJson) {
            // The checks assert on which tools ran, not what they returned.
        }

        @Override
        public String executionLabel() {
            return "eval-capture";
        }

        /**
         * Pull {@code function.name} out of the single serialized
         * {@code LlmTypes.ToolCall} that {@code ParallelToolExecutor} commits per
         * call. A shape we cannot parse is skipped rather than thrown: losing one
         * tool name costs a check its evidence, while throwing would lose the whole
         * case's answer along with it.
         */
        private void recordToolName(String toolCallJson) {
            try {
                var fn = JsonParser.parseString(toolCallJson).getAsJsonObject().getAsJsonObject("function");
                if (fn == null) return;
                var name = fn.get("name");
                if (name != null && !name.isJsonNull()) tools.add(name.getAsString());
            } catch (RuntimeException e) {
                EventLogger.warn(EVENT_CATEGORY, "Unparseable tool call in eval capture: " + e);
            }
        }

        List<String> toolsCalled() {
            return List.copyOf(tools);
        }
    }
}
