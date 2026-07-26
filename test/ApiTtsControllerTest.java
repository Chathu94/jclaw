import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;

/**
 * Functional coverage for the read-aloud API (JCLAW-789/793). Exercises the
 * auth gate, the Settings state snapshot, and the request-validation paths —
 * all of which return WITHOUT spawning the sidecar or loading the JVM engine,
 * so the suite stays hermetic (no uv, no model downloads, no network).
 */
class ApiTtsControllerTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
    }

    private void login() {
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\":\"admin\",\"password\":\"changeme\"}"));
    }

    @Test
    void stateRequiresAuth() {
        assertEquals(401, GET("/api/tts/state").status.intValue());
    }

    @Test
    void synthesizeRequiresAuth() {
        var resp = POST("/api/tts/synthesize", "application/json", "{\"text\":\"hello\"}");
        assertEquals(401, resp.status.intValue());
    }

    @Test
    void stateSnapshotsBothEngines() {
        login();
        var resp = GET("/api/tts/state");
        assertIsOk(resp);
        assertContentType("application/json", resp);
        var body = getContent(resp);
        // Selected engine + both backends present, so the Settings panel can render the selector.
        assertTrue(body.contains("\"engine\""), body);
        assertTrue(body.contains("\"sidecar\""), body);
        assertTrue(body.contains("\"jvm\""), body);
    }

    @Test
    void synthesizeRejectsMissingText() {
        login();
        assertEquals(400, POST("/api/tts/synthesize", "application/json", "{}").status.intValue());
    }

    @Test
    void synthesizeRejectsBlankText() {
        login();
        assertEquals(400,
                POST("/api/tts/synthesize", "application/json", "{\"text\":\"   \"}").status.intValue());
    }

    @Test
    void streamRequiresAuth() {
        var resp = POST("/api/tts/stream", "application/json", "{\"text\":\"hello\"}");
        assertEquals(401, resp.status.intValue());
    }

    @Test
    void streamRejectsMissingText() {
        // Validation returns before the SSE stream opens, so no engine is touched.
        login();
        assertEquals(400, POST("/api/tts/stream", "application/json", "{}").status.intValue());
    }

    @Test
    void streamRejectsBlankText() {
        login();
        assertEquals(400,
                POST("/api/tts/stream", "application/json", "{\"text\":\"   \"}").status.intValue());
    }

    private static final Gson GSON = new Gson();

    /** Longer than the 5000-char default cap. */
    private static String longSpeakableText() {
        return "This is an ordinary sentence in a long answer. ".repeat(150); // ~7050 chars
    }

    /**
     * Run {@code body} with the sidecar forced unavailable, so a request that gets
     * PAST validation fails fast at the engine instead of spawning uv and pulling a
     * model. Keeps the "passes validation" assertions hermetic, which is the whole
     * premise of this class — the default engine is the sidecar, so this is the one
     * switch that stops the suite reaching the network.
     */
    private static void withoutSidecar(Runnable body) {
        services.UvProbe.setForTest(new services.UvProbe.ProbeResult(false, "forced off in test"));
        try {
            body.run();
        } finally {
            services.UvProbe.setForTest(null);
        }
    }

    @Test
    void streamAcceptsTextBeyondTheOneShotCap() {
        // JCLAW-880: the cap used to live here too and refused input this endpoint is
        // built to handle — it sentence-chunks below the guard and streams each chunk.
        // A 5.7k-character reply is a long answer, not abuse. Not-400 is the assertion
        // that matters: the request is no longer turned away for its length.
        login();
        withoutSidecar(() -> {
            var body = GSON.toJson(java.util.Map.of("text", longSpeakableText()));
            var resp = POST("/api/tts/stream", "application/json", body);
            assertNotEquals(400, resp.status.intValue());
        });
    }

    @Test
    void synthesizeStillRejectsTextBeyondTheCap() {
        // The cap stays on the one-shot path: it holds the JVM-wide sidecar lock for
        // the whole synthesis with nothing to interleave and no way to cancel. This
        // one is rejected during validation, so no engine is touched either way.
        login();
        var body = GSON.toJson(java.util.Map.of("text", longSpeakableText()));
        assertEquals(400, POST("/api/tts/synthesize", "application/json", body).status.intValue());
    }

    @Test
    void synthesizeMeasuresTheCapAgainstSpeakableTextNotRawMarkdown() {
        // A message whose bulk is a code fence strips to almost nothing before being
        // spoken. Counting the raw markdown rejected messages for length they would
        // never have uttered.
        login();
        withoutSidecar(() -> {
            var text = "Here is the answer.\n\n```\n" + "x".repeat(6000) + "\n```\n";
            var resp = POST("/api/tts/synthesize", "application/json",
                    GSON.toJson(java.util.Map.of("text", text)));
            assertNotEquals(400, resp.status.intValue());
        });
    }

    @Test
    void downloadRejectsUnknownModel() {
        login();
        assertEquals(400,
                POST("/api/tts/models/not-a-real-model/download", "application/json", "{}").status.intValue());
    }

    @Test
    void downloadOfSidecarModelIsManagedNoDownload() {
        login();
        // A sidecar model is pulled by the sidecar on first use, not here — the
        // endpoint reports "managed" and triggers no download.
        var resp = POST("/api/tts/models/qwen3-0.6b/download", "application/json", "{}");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("managed"), getContent(resp));
    }
}
