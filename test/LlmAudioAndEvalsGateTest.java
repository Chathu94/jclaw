import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.LlmAudio;
import services.tts.TtsSidecarManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * {@link LlmAudio}'s attachment-preparation path, plus the two small pieces of
 * {@link TtsSidecarManager} that are safe to touch without launching a Python sidecar.
 *
 * <p>Only small clips are used, deliberately: past
 * {@code LlmAudio.TRANSCODE_THRESHOLD_BYTES} the code shells out to ffmpeg, which is a
 * host dependency rather than something to fake. That branch is flagged, not covered.
 */
class LlmAudioAndEvalsGateTest extends UnitTest {

    private static Path clip(String name, byte[] bytes) throws Exception {
        var p = Files.createTempDirectory("llmaudio").resolve(name);
        Files.write(p, bytes);
        p.toFile().deleteOnExit();
        return p;
    }

    @Test
    void aSmallClipIsBase64EncodedInPlaceWithoutTranscoding() throws Exception {
        var raw = "fake wav payload".getBytes();
        var prepared = LlmAudio.prepare(clip("a.wav", raw), "audio/wav");

        assertNotNull(prepared);
        assertArrayEquals(raw, Base64.getDecoder().decode(prepared.base64()),
                "a clip under the transcode threshold must reach the model byte-identical");
        assertNotNull(prepared.format());
        assertFalse(prepared.format().isBlank());
    }

    @Test
    void theFormatIsDerivedRatherThanLeftAsTheRawMimeType() {
        // Providers take a short format token ("wav", "mp3"), not a MIME type.
        assertDoesNotThrow(() -> {
            var prepared = LlmAudio.prepare(clip("b.wav", "x".getBytes()), "audio/wav");
            assertFalse(prepared.format().contains("/"),
                    "expected a bare format token, got " + prepared.format());
        });
    }

    @Test
    void anUnrecognisedTypeStillCarriesTheAudioButYieldsNoFormatToken() throws Exception {
        // Pins observed behaviour, not a desired contract: an unknown MIME with an unknown
        // extension comes back with the bytes intact but an EMPTY format. Callers therefore
        // have to cope with a blank token — worth confirming against the provider adapters,
        // since sending format="" is not obviously better than falling back to a default.
        var prepared = LlmAudio.prepare(clip("c.bin", "x".getBytes()), "application/octet-stream");

        assertNotNull(prepared);
        assertEquals("x", new String(Base64.getDecoder().decode(prepared.base64())),
                "an unrecognised type must not cost the audio");
        assertTrue(prepared.format().isBlank(),
                "documenting today's behaviour: no format is inferred, rather than a default");
    }

    @Test
    void aNullMimeTypeIsToleratedByFallingBackToTheExtension() throws Exception {
        var prepared = LlmAudio.prepare(clip("d.mp3", "x".getBytes()), null);
        assertNotNull(prepared);
        assertNotNull(prepared.format());
    }

    @Test
    void theCachePathIsDerivedFromTheSourceAndIsStable() throws Exception {
        var src = clip("e.wav", "x".getBytes());

        var first = LlmAudio.cachePath(src);
        var second = LlmAudio.cachePath(src);

        assertEquals(first, second, "an unstable cache path would re-transcode every turn");
        assertNotEquals(src, first, "the cache must not overwrite the original attachment");
    }

    @Test
    void differentSourcesGetDifferentCachePaths() throws Exception {
        var a = LlmAudio.cachePath(clip("f.wav", "x".getBytes()));
        var b = LlmAudio.cachePath(clip("g.wav", "y".getBytes()));

        assertNotEquals(a, b, "two clips sharing a cache path would serve each other's audio");
    }

    @Test
    void preparingAMissingFileFailsRatherThanReturningEmptyAudio() {
        // Empty audio would reach the model as a valid-looking silent clip.
        assertThrows(Exception.class,
                () -> LlmAudio.prepare(Path.of("/nonexistent/none.wav"), "audio/wav"));
    }

    // ─── TtsSidecarManager: the surface that does not launch anything ────────

    @Test
    void theTtsSidecarIsNotRunningUntilSomethingStartsIt() {
        // ensureRunning() is deliberately not called here — it would launch a real Python
        // process on the test host.
        assertFalse(TtsSidecarManager.isRunning());
    }

    @Test
    void stoppingASidecarThatWasNeverStartedIsANoOp() {
        // Called from the JVM shutdown hook, which runs whether or not TTS was ever used.
        assertDoesNotThrow(TtsSidecarManager::stop);
        assertFalse(TtsSidecarManager.isRunning());
    }

    @Test
    void theSidecarIdentityIsStableBecauseConfigKeysAreBuiltFromIt() {
        assertEquals("tts", TtsSidecarManager.IDENTITY);
    }
}
