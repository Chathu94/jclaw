import com.google.gson.JsonObject;
import llm.LlmTypes.ChunkDelta;
import llm.LlmTypes.ProviderConfig;
import llm.LlmTypes.ReasoningDetail;
import llm.OpenRouterProvider;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.List;

/**
 * Branch coverage for OpenRouterProvider's protected reasoning hooks.
 * OpenRouterProvider is {@code final}, so we can't subclass — reflection
 * is the documented escape hatch.
 */
class OpenRouterProviderTest extends UnitTest {

    private final OpenRouterProvider provider = new OpenRouterProvider(
            new ProviderConfig("openrouter", "https://openrouter.ai/api/v1",
                    "sk-test", List.of()));

    private String extractReasoning(ChunkDelta delta) throws Exception {
        var m = OpenRouterProvider.class.getDeclaredMethod(
                "extractReasoningFromDelta", ChunkDelta.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, delta);
    }

    @Test
    void extractReasoningPrefersReasoningDetailsArray() throws Exception {
        var details = List.of(
                new ReasoningDetail("reasoning.text", "first thought"),
                new ReasoningDetail("reasoning.text", " plus more"));
        var delta = new ChunkDelta(null, null, null, "fallback", null, details);
        var result = extractReasoning(delta);
        assertEquals("first thought plus more", result,
                "non-null reasoningDetails must concatenate text fields and win over reasoning string");
    }

    @Test
    void extractReasoningReturnsNullWhenAllDetailTextsAreNull() throws Exception {
        // reasoningDetails present but every entry's text() is null — the
        // builder stays empty and isEmpty() → return null.
        var details = List.of(
                new ReasoningDetail("reasoning.signature", null),
                new ReasoningDetail("reasoning.text", null));
        var delta = new ChunkDelta(null, null, null, "ignored-fallback", null, details);
        assertNull(extractReasoning(delta),
                "all-null detail texts must yield null, not empty string");
    }

    @Test
    void extractReasoningFallsBackToReasoningStringWhenDetailsAbsent() throws Exception {
        var delta = new ChunkDelta(null, null, null, "from string field", null, null);
        assertEquals("from string field", extractReasoning(delta));
    }

    @Test
    void extractReasoningReturnsNullWhenBothFieldsAbsent() throws Exception {
        var delta = new ChunkDelta(null, null, null, null, null, null);
        assertNull(extractReasoning(delta));
    }

    // --- addReasoningParams / disableReasoning ---

    @Test
    void addReasoningParamsSetsObjectAndScalar() throws Exception {
        var request = new JsonObject();
        var m = OpenRouterProvider.class.getDeclaredMethod(
                "addReasoningParams", JsonObject.class, String.class);
        m.setAccessible(true);
        m.invoke(provider, request, "high");
        // Both the reasoning object and the scalar reasoning_effort field
        // must be set on the request.
        assertTrue(request.has("reasoning"), "reasoning object present");
        assertEquals("high", request.get("reasoning").getAsJsonObject()
                .get("effort").getAsString());
        assertEquals("high", request.get("reasoning_effort").getAsString());
    }

    @Test
    void disableReasoningSendsMinimalBecauseNoneIsRejected() throws Exception {
        // JCLAW-794: OpenRouter 400s on effort "none" for a growing set of models
        // ("Reasoning is mandatory for this endpoint and cannot be disabled") and ignores
        // it on the rest, so "none" either breaks the request or does nothing. "minimal"
        // was accepted by every model probed and still yields zero or near-zero reasoning
        // tokens, which is what the off-switch is for.
        var request = new JsonObject();
        var m = OpenRouterProvider.class.getDeclaredMethod(
                "disableReasoning", JsonObject.class);
        m.setAccessible(true);
        m.invoke(provider, request);

        assertEquals("minimal",
                request.get("reasoning").getAsJsonObject().get("effort").getAsString());
        assertFalse(request.has("reasoning_effort"),
                "the scalar is for the enable path; the object alone was verified sufficient");
    }

    @Test
    void disableReasoningStaysProviderSpecificRatherThanSharedWithOllama() throws Exception {
        // Guards the reason this is an override rather than a base-class change: Ollama
        // rejects "minimal" outright and accepts "none", so unifying the two would trade
        // this bug for its mirror image on the provider the default agent runs on.
        var ollama = new llm.OllamaProvider(new ProviderConfig(
                "ollama-cloud", "https://ollama.com/v1", "k", List.of()));
        var m = llm.OllamaProvider.class.getDeclaredMethod("disableReasoning", JsonObject.class);
        m.setAccessible(true);
        var request = new JsonObject();
        m.invoke(ollama, request);

        assertEquals("none", request.get("reasoning_effort").getAsString(),
                "Ollama's off-signal must stay \"none\" — it 400s on \"minimal\"");
    }

    // --- extractReasoningTokens ---

    private int extractReasoningTokens(JsonObject usage) throws Exception {
        var m = OpenRouterProvider.class.getDeclaredMethod(
                "extractReasoningTokens", JsonObject.class);
        m.setAccessible(true);
        return (int) m.invoke(provider, usage);
    }

    @Test
    void extractReasoningTokensReadsTopLevelField() throws Exception {
        var usage = new JsonObject();
        usage.addProperty("reasoning_tokens", 42);
        assertEquals(42, extractReasoningTokens(usage),
                "top-level reasoning_tokens wins");
    }

    @Test
    void extractReasoningTokensFallsBackToOpenAINestedFormat() throws Exception {
        // No top-level reasoning_tokens, but completion_tokens_details.reasoning_tokens
        // is present (OpenAI-proxied path). The fallback should return it.
        var usage = new JsonObject();
        var nested = new JsonObject();
        nested.addProperty("reasoning_tokens", 17);
        usage.add("completion_tokens_details", nested);
        assertEquals(17, extractReasoningTokens(usage));
    }

    @Test
    void extractReasoningTokensReturnsZeroWhenBothMissing() throws Exception {
        var usage = new JsonObject();
        assertEquals(0, extractReasoningTokens(usage));
    }
}
