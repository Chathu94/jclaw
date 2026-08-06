import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.tts.TtsSidecarClient;
import tools.LoadTestSleepTool;

import java.time.Duration;

/**
 * Two small, previously untested pieces: the loadtest harness's no-op sleep tool and the
 * TTS sidecar client's failure path.
 *
 * <p>Neither needs a sidecar. The TTS cases point the client at a closed loopback port, so
 * the connection is refused immediately and the case tests the error contract rather than a
 * timeout — a black-holed address would sit on the 1800-second configured timeout.
 */
class LoadTestSleepToolAndTtsClientTest extends UnitTest {

    // ─── LoadTestSleepTool ───────────────────────────────────────────────────

    private final LoadTestSleepTool tool = new LoadTestSleepTool();

    @Test
    void theSleepToolDeclaresAnMsParameterThatIsNotRequired() {
        // The harness calls it with no arguments to measure baseline dispatch cost, so a
        // required parameter would break the very thing it exists for.
        var params = tool.parameters();
        var props = (java.util.Map<?, ?>) params.get(SCHEMA_KEY_PROPERTIES);
        assertTrue(props.containsKey("ms"), props.toString());
        assertTrue(((java.util.List<?>) params.get(SCHEMA_KEY_REQUIRED)).isEmpty(),
                "ms must be optional");
    }

    private static final String SCHEMA_KEY_PROPERTIES = "properties";
    private static final String SCHEMA_KEY_REQUIRED = "required";

    @Test
    void theSleepToolIsParallelSafeBecauseItHoldsNoState() {
        assertEquals("loadtest_sleep", tool.name());
        assertTrue(tool.parallelSafe());
        assertFalse(tool.shortDescription().isBlank());
        assertFalse(tool.description().isBlank());
        assertFalse(tool.actions().isEmpty());
    }

    @Test
    void executingWithAnExplicitZeroReturnsPromptlyAndReportsTheDuration() {
        var before = LoadTestSleepTool.invocations();
        var started = System.nanoTime();

        var out = tool.execute("{\"ms\":0}", null);

        // The exact duration, not just "non-blank": DEFAULT_MS is 200, comfortably inside a
        // 2000 ms budget, so a version that ignored the argument entirely passed the timing
        // assertion alone.
        assertEquals("slept 0ms", out);
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 2000,
                "a zero sleep must not block the harness");
        // Relative, not absolute: the counter is process-global and the concurrent lane
        // may also be invoking this tool.
        assertTrue(LoadTestSleepTool.invocations() > before, "every invocation must be counted");
    }

    @Test
    void aNegativeDurationIsClampedRatherThanThrowing() {
        var started = System.nanoTime();
        var out = tool.execute("{\"ms\":-5000}", null);

        assertEquals("slept 0ms", out, "a negative sleep clamps to zero");
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 2000,
                "clamping must not mean waiting");
    }

    @Test
    void malformedArgumentsFallBackToTheDefaultInsteadOfFailing() {
        // Harness-caller only, so a bad payload degrades rather than erroring out of a run.
        // The default (200ms) is what a fallback must produce — asserting only non-null let
        // a version that ignored the ms argument on EVERY path through.
        assertEquals("slept 200ms", tool.execute("not json at all", null));
        assertEquals("slept 200ms", tool.execute("{\"ms\":null}", null));
        assertEquals("slept 200ms", tool.execute("{}", null));
    }

    // ─── TtsSidecarClient ────────────────────────────────────────────────────

    /** Points at a closed loopback port so the connect is refused immediately. */
    private static TtsSidecarClient clientAgainstNothing() {
        var http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofMillis(500))
                .readTimeout(Duration.ofSeconds(2))
                .build();
        // Port 9 is discard/unassigned on loopback; nothing accepts there.
        return new TtsSidecarClient("http://127.0.0.1:9", http);
    }

    @Test
    void synthesizeSurfacesASidecarFailureRatherThanReturningEmptyAudio() {
        // Silent empty audio would reach the caller as a valid-looking zero-length clip;
        // the read-aloud path needs to know the sidecar is down.
        var client = clientAgainstNothing();

        var thrown = assertThrows(services.tts.TtsException.class,
                () -> client.synthesize("hello there", "kokoro", null, "wav"));
        assertNotNull(thrown.getMessage());
    }

    @Test
    void theFourArgOverloadDelegatesToTheFiveArgOneAndFailsIdentically() {
        var client = clientAgainstNothing();

        var four = assertThrows(services.tts.TtsException.class,
                () -> client.synthesize("hello", "kokoro", null, "wav"));
        var five = assertThrows(services.tts.TtsException.class,
                () -> client.synthesize("hello", "kokoro", null, "wav", null));

        assertEquals(four.getClass(), five.getClass(),
                "the convenience overload must not change the failure contract");
    }

    @Test
    void anOptionalReferenceClipDoesNotChangeTheFailureContract() {
        var client = clientAgainstNothing();

        assertThrows(services.tts.TtsException.class,
                () -> client.synthesize("hello", "chatterbox", "narrator", "wav", "/tmp/ref.wav"));
    }
}
