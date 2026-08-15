import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.videogen.VideoGenerationRouter;

/**
 * The provider/model reported by {@code GET /api/videogen/state} (JCLAW-1057).
 *
 * <p>The endpoint exists because the effective model is not readable from config alone:
 * the router substitutes a per-provider default when the key is unset, so a caller
 * reading {@code videogen.local.model} directly sees a blank where a real model will be
 * used. These tests pin that the reported model is the one that would actually run.
 */
class VideogenStateTest extends UnitTest {

    /**
     * The whole reason for the endpoint. With no {@code videogen.local.model} set, the
     * router still runs a model — reporting the raw config value would say "none".
     */
    @Test
    void reportsThePerProviderDefaultRatherThanTheBlankConfigValue() {
        assertEquals("ltx", VideoGenerationRouter.effectiveModel("ltx-local"));
        assertEquals("wan-5b", VideoGenerationRouter.effectiveModel("wan-local"));
    }

    /** An unset or unknown provider has no model, and must not be given an invented one. */
    @Test
    void reportsNoModelForAProviderThatResolvesToNothing() {
        assertNull(VideoGenerationRouter.effectiveModel(null));
        assertNull(VideoGenerationRouter.effectiveModel(""));
        assertNull(VideoGenerationRouter.effectiveModel("   "));
        assertNull(VideoGenerationRouter.effectiveModel("no-such-provider"));
    }

    /**
     * The reported model and the configured-ness flag have to agree with the router that
     * actually dispatches, or the endpoint describes a system other than the running one.
     */
    @Test
    void agreesWithTheRouterAboutWhichProvidersAreUsable() {
        for (var provider : new String[]{"ltx-local", "wan-local"}) {
            assertTrue(VideoGenerationRouter.serviceFor(provider).isPresent(),
                    provider + " should resolve to a client");
            assertNotNull(VideoGenerationRouter.effectiveModel(provider),
                    provider + " resolves to a client, so it must report a model");
        }
        assertTrue(VideoGenerationRouter.serviceFor("no-such-provider").isEmpty());
    }
}
