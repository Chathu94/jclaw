import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;
import services.tts.TtsEngine;
import services.tts.TtsJvmEngine;
import services.tts.TtsRouter;

import java.lang.reflect.Method;

/**
 * Tests for {@link TtsRouter}'s engine selection and the JCLAW-861 voice-mode
 * fallback.
 *
 * <p>The fallback decision is tested rather than an end-to-end synthesis: both
 * engines need real weights and, for the sidecar, a live Python subprocess, so
 * exercising them here would make the suite depend on machine state. What the
 * fallback gets wrong if it is wrong are the two guards — direction and weight
 * availability — and those are pure decisions.
 */
class TtsRouterTest extends UnitTest {

    @BeforeEach
    void clearEngine() {
        ConfigService.delete("tts.engine");
        ConfigService.clearCache();
    }

    @AfterEach
    void cleanup() {
        ConfigService.delete("tts.engine");
        ConfigService.clearCache();
    }

    private static TtsEngine fallbackFor(TtsEngine primary) throws Exception {
        Method m = TtsRouter.class.getDeclaredMethod("fallbackFor", TtsEngine.class);
        m.setAccessible(true);
        return (TtsEngine) m.invoke(null, primary);
    }

    @Test
    void fallbackIsOneDirectional() throws Exception {
        // JVM -> SIDECAR would fall back to the LESS reliable engine: the sidecar
        // needs uv, a Python env, a subprocess that can die independently, and
        // possibly a network fetch. Falling back to it is backwards, so the JVM
        // engine has no alternative at all.
        assertNull(fallbackFor(TtsEngine.JVM),
                "JVM must not fall back to the sidecar");
    }

    @Test
    void sidecarFallsBackOnlyWhenJvmWeightsAreAlreadyPresent() throws Exception {
        // Gated on weights being extracted already: TtsJvmEngine.synthesize would
        // otherwise download hundreds of megabytes on demand, and stalling a live
        // turn on a cold download is worse than failing it promptly.
        //
        // Asserted as an equivalence rather than a fixed expectation, because
        // whether the model is on disk is a property of the machine running the
        // suite, not of the code under test.
        var present = TtsJvmEngine.isModelPresent(TtsRouter.modelFor(TtsEngine.JVM));
        var alt = fallbackFor(TtsEngine.SIDECAR);
        if (present) {
            assertEquals(TtsEngine.JVM, alt,
                    "with weights on disk the sidecar must have somewhere to fall back to");
        } else {
            assertNull(alt,
                    "without weights the fallback must decline rather than trigger a download mid-turn");
        }
    }

    @Test
    void engineSelectionRoundTripsThroughConfig() {
        ConfigService.set("tts.engine", TtsEngine.JVM.id());
        ConfigService.clearCache();
        assertEquals(TtsEngine.JVM, TtsRouter.currentEngine());

        ConfigService.set("tts.engine", TtsEngine.SIDECAR.id());
        ConfigService.clearCache();
        assertEquals(TtsEngine.SIDECAR, TtsRouter.currentEngine());
    }

    @Test
    void unsetEngineFallsBackToTheDefault() {
        assertEquals(TtsEngine.DEFAULT, TtsRouter.currentEngine(),
                "an unset tts.engine must resolve to the documented default");
    }

    @Test
    void modelForRejectsAModelBelongingToTheOtherEngine() {
        // A stale config value pointing at another engine's model would otherwise
        // be handed to an engine that cannot load it.
        var jvmDefault = TtsRouter.modelFor(TtsEngine.JVM);
        ConfigService.set("tts.sidecar.model", jvmDefault);
        ConfigService.clearCache();
        try {
            assertNotEquals(jvmDefault, TtsRouter.modelFor(TtsEngine.SIDECAR),
                    "a JVM model id must not be accepted as the sidecar's model");
        } finally {
            ConfigService.delete("tts.sidecar.model");
            ConfigService.clearCache();
        }
    }
}
