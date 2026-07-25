import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import llm.LlmProvider;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ChatRequest;
import llm.LlmTypes.ChunkDelta;
import llm.LlmTypes.ProviderConfig;
import llm.OllamaProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Direct tests for {@link OllamaProvider}: thinking-mode mapping (incl. the
 * disable path with belt-and-braces {@code think:false} fallback), reasoning
 * extraction from streaming deltas, the top-level {@code reasoning_tokens}
 * extraction shape, and the {@code keep_alive} cache directive.
 *
 * <p>The native-Ollama discovery flow ({@code /api/tags} + {@code /api/show},
 * JCLAW-118) lives in {@code services.ModelDiscoveryService} and is covered by
 * {@code ModelDiscoveryServiceTest}; not duplicated here.
 */
class OllamaProviderTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
    }

    /** keep_alive is keyed per provider — see OllamaProvider.applyCacheDirectives. */
    private static final String KEEP_ALIVE_CLOUD = "provider.ollama-cloud.keepAlive";
    private static final String KEEP_ALIVE_LOCAL = "provider.ollama-local.keepAlive";

    @AfterEach
    void teardown() {
        ConfigService.delete(KEEP_ALIVE_CLOUD);
        ConfigService.delete(KEEP_ALIVE_LOCAL);
        ConfigService.clearCache();
    }

    private static OllamaProvider provider() {
        return new OllamaProvider(new ProviderConfig(
                "ollama-cloud", "https://ollama.com/v1", "ollama-key", List.of()));
    }

    private static OllamaProvider localProvider() {
        return new OllamaProvider(new ProviderConfig(
                "ollama-local", "http://localhost:11434/v1", "ollama-local", List.of()));
    }

    private static JsonObject serialize(LlmProvider p, ChatRequest req) throws Exception {
        Method m = LlmProvider.class.getDeclaredMethod("serializeRequest", ChatRequest.class);
        m.setAccessible(true);
        var json = (String) m.invoke(p, req);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /**
     * Reflection accessor for the protected {@code extractReasoningFromDelta}
     * template method. Tests live in the default package; the method's
     * package-private visibility on {@code llm} forces this dance.
     */
    private static String extractReasoning(LlmProvider p, ChunkDelta delta) throws Exception {
        Method m = LlmProvider.class.getDeclaredMethod("extractReasoningFromDelta", ChunkDelta.class);
        m.setAccessible(true);
        return (String) m.invoke(p, delta);
    }

    private static ChatRequest withThinking(String thinkingMode) {
        return new ChatRequest("kimi-k2.5", List.of(ChatMessage.user("hi")),
                null, false, null, thinkingMode);
    }

    // =====================
    // addReasoningParams — thinking enabled
    // =====================

    @Test
    void addReasoningParamsEmitsReasoningEffortField() throws Exception {
        var body = serialize(provider(), withThinking("low"));
        assertEquals("low", body.get("reasoning_effort").getAsString(),
                "Ollama maps thinkingMode 1:1 onto reasoning_effort");
    }

    @Test
    void addReasoningParamsThreadsAllAcceptedValues() throws Exception {
        for (var level : List.of("low", "medium", "high")) {
            var body = serialize(provider(), withThinking(level));
            assertEquals(level, body.get("reasoning_effort").getAsString(),
                    "level must round-trip verbatim: " + level);
        }
    }

    @Test
    void addReasoningParamsDoesNotEmitThinkFieldWhenEnabled() throws Exception {
        // When reasoning IS enabled, the belt-and-braces "think:false" fallback
        // must NOT appear — that field is exclusive to the disable path.
        var body = serialize(provider(), withThinking("medium"));
        assertFalse(body.has("think"),
                "think: field must only appear on the disable path");
    }

    // =====================
    // disableReasoning — thinkingMode null/blank
    // =====================

    @Test
    void disableReasoningEmitsBothNoneAndThinkFalse() throws Exception {
        // When thinkingMode is null, disableReasoning fires and must send BOTH:
        //   reasoning_effort: "none"   (the documented /v1 off signal)
        //   think: false               (belt-and-braces for the native shim)
        var body = serialize(provider(), withThinking(null));
        assertEquals("none", body.get("reasoning_effort").getAsString(),
                "reasoning_effort must be 'none' on disable");
        assertTrue(body.has("think"));
        assertFalse(body.get("think").getAsBoolean(),
                "think: false must be sent as a fallback for default-thinking models");
    }

    @Test
    void disableReasoningTriggersOnBlankThinkingMode() throws Exception {
        var body = serialize(provider(), withThinking(""));
        assertEquals("none", body.get("reasoning_effort").getAsString());
        assertFalse(body.get("think").getAsBoolean());
    }

    // =====================
    // extractReasoningFromDelta
    // =====================

    @Test
    void extractReasoningFromDeltaReadsTopLevelReasoningString() throws Exception {
        var p = provider();
        var delta = new ChunkDelta("assistant", null, null, "thinking step 1", null);
        assertEquals("thinking step 1", extractReasoning(p, delta));
    }

    @Test
    void extractReasoningFromDeltaReturnsNullWhenAbsent() throws Exception {
        var p = provider();
        var delta = new ChunkDelta("assistant", "regular content", null, null, null);
        assertNull(extractReasoning(p, delta),
                "extractor must surface the absence of reasoning as null");
    }

    // =====================
    // extractReasoningTokens — Ollama shape (top-level)
    // =====================

    @Test
    void extractReasoningTokensReadsTopLevelField() {
        var p = provider();
        var usage = p.parseUsage(JsonParser.parseString("""
                {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15,
                 "reasoning_tokens": 99}
                """).getAsJsonObject());
        assertEquals(99, usage.reasoningTokens(),
                "Ollama reports reasoning_tokens at the top level of usage");
    }

    @Test
    void extractReasoningTokensReturnsZeroWhenAbsent() {
        var p = provider();
        var usage = p.parseUsage(JsonParser.parseString("""
                {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
                """).getAsJsonObject());
        assertEquals(0, usage.reasoningTokens());
    }

    @Test
    void extractReasoningTokensReturnsZeroWhenNull() {
        var p = provider();
        var usage = p.parseUsage(JsonParser.parseString("""
                {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15,
                 "reasoning_tokens": null}
                """).getAsJsonObject());
        assertEquals(0, usage.reasoningTokens());
    }

    // =====================
    // applyCacheDirectives — keep_alive
    // =====================

    @Test
    void applyCacheDirectivesEmitsKeepAliveDefaultFiveMinutes() throws Exception {
        // Config key absent → default to "5m" (constant inside the provider),
        // deliberately matching Ollama's own OLLAMA_KEEP_ALIVE default so JClaw
        // doesn't pin every model it touches past the daemon's own policy.
        var body = serialize(provider(), withThinking("medium"));
        assertEquals("5m", body.get("keep_alive").getAsString(),
                "keep_alive must default to 5m when the provider's keepAlive config is unset");
    }

    @Test
    void applyCacheDirectivesHonorsConfigOverride() throws Exception {
        // Deliberately not the default value, so this asserts the config is read
        // rather than passing on the fallback path.
        ConfigService.set(KEEP_ALIVE_CLOUD, "30m");
        var body = serialize(provider(), withThinking("medium"));
        assertEquals("30m", body.get("keep_alive").getAsString(),
                "keep_alive must follow the provider's keepAlive config when set");
    }

    @Test
    void applyCacheDirectivesIgnoresBlankConfigValue() throws Exception {
        // A blank config value should fall back to the default rather than
        // sending an empty string that the Ollama scheduler would reject.
        ConfigService.set(KEEP_ALIVE_CLOUD, "   ");
        var body = serialize(provider(), withThinking("medium"));
        assertEquals("5m", body.get("keep_alive").getAsString());
    }

    @Test
    void applyCacheDirectivesKeysKeepAlivePerProvider() throws Exception {
        // The key is provider.<name>.keepAlive, not a shared global: residency
        // belongs to the daemon behind a provider, so a long pin on a local box
        // must not leak onto ollama-cloud (or any second local daemon).
        ConfigService.set(KEEP_ALIVE_LOCAL, "-1");
        assertEquals("5m", serialize(provider(), withThinking("medium"))
                        .get("keep_alive").getAsString(),
                "ollama-cloud must not pick up ollama-local's keepAlive");
        assertEquals("-1", serialize(localProvider(), withThinking("medium"))
                        .get("keep_alive").getAsString(),
                "ollama-local must read its own keepAlive key");
    }

    // =====================
    // Sanity: factory routes "ollama"-named configs to OllamaProvider
    // =====================

    @Test
    void forConfigRoutesOllamaSubstringToOllamaProvider() {
        var ollamaCloud = LlmProvider.forConfig(new ProviderConfig(
                "ollama-cloud", "https://ollama.com/v1", "k", List.of()));
        assertInstanceOf(OllamaProvider.class, ollamaCloud,
                "any provider name containing 'ollama' must route to OllamaProvider");

        var localOllama = LlmProvider.forConfig(new ProviderConfig(
                "my-ollama-mirror", "http://localhost:11434/v1", "", List.of()));
        assertInstanceOf(OllamaProvider.class, localOllama);
    }
}
