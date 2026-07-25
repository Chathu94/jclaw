package llm;

import com.google.gson.JsonObject;
import llm.LlmTypes.ChunkDelta;
import llm.LlmTypes.ProviderConfig;

/**
 * Standard OpenAI-compatible provider. Handles direct OpenAI API and any
 * provider that follows the OpenAI chat completions spec without extensions.
 *
 * <p>Also the catch-all: {@code ProviderRegistry} routes every provider it does
 * not recognize here, so this class is what actually serves LM Studio, vLLM,
 * SGLang, Groq and Azure.
 *
 * Reasoning: sends {@code reasoning_effort} as a top-level request parameter,
 * and streams thinking from {@code reasoning_content} on the delta (JCLAW-850).
 * Usage: reads {@code completion_tokens_details.reasoning_tokens} from the response.
 */
public final class OpenAiProvider extends LlmProvider {

    public OpenAiProvider(ProviderConfig config) {
        super(config);
    }

    @Override
    protected void addReasoningParams(JsonObject request, String thinkingMode) {
        request.addProperty("reasoning_effort", thinkingMode);
    }

    @Override
    protected int extractReasoningTokens(JsonObject usageObj) {
        // OpenAI nests reasoning tokens under completion_tokens_details; the
        // shared top-then-nested chain resolves to that (no top-level field).
        return readReasoningTokens(usageObj);
    }

    @Override
    protected String extractReasoningFromDelta(ChunkDelta delta) {
        // JCLAW-850: ProviderRegistry routes every unrecognized provider here,
        // which is how LM Studio, vLLM, SGLang and Groq are served. They stream
        // thinking as `reasoning_content` on the delta — verified against a live
        // LM Studio capture:
        //   "delta":{"role":"assistant","reasoning_content":"Thinking"}
        // Without this override the base returned null, so onReasoning never
        // fired and a reasoning model showed a silent multi-second gap followed
        // by a bare answer. Token counts were unaffected; they come off usage.
        //
        // Fall back to the plain `reasoning` string, which some OpenAI-compatible
        // servers emit instead. OpenAI proper streams no reasoning at all, so
        // both fields are absent there and this stays null.
        if (delta.reasoningContent() != null) return delta.reasoningContent();
        return delta.reasoning();
    }
}
