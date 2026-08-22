import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.EmbeddingModelKeepAlive;
import services.EmbeddingModelKeepAlive.Strategy;

/**
 * Residency has no portable directive, so the pin is chosen per backend — and only for a
 * locally-served one, since a hosted provider has no model to hold and would just be billed.
 */
class EmbeddingModelKeepAliveTest extends UnitTest {

    @Test
    void pinsOllamaThroughItsNativeKeepAlive() {
        assertEquals(Strategy.OLLAMA_KEEP_ALIVE,
                EmbeddingModelKeepAlive.strategyFor("ollama-local", "http://localhost:11434/v1", false));
    }

    @Test
    void pinsLmStudioThroughItsTtl() {
        assertEquals(Strategy.LM_STUDIO_TTL,
                EmbeddingModelKeepAlive.strategyFor("lmstudio", "http://127.0.0.1:1234/v1", false));
        assertEquals(Strategy.LM_STUDIO_TTL,
                EmbeddingModelKeepAlive.strategyFor("lm-studio", "http://127.0.0.1:1234/v1", false));
    }

    /** vLLM holds the model for the server's lifetime; llama.cpp is server-flag only. */
    @Test
    void sendsNothingToBackendsWithNoClientSidePin() {
        assertEquals(Strategy.NONE,
                EmbeddingModelKeepAlive.strategyFor("vllm-local", "http://localhost:8000/v1", false));
        assertEquals(Strategy.NONE,
                EmbeddingModelKeepAlive.strategyFor("llamacpp", "http://localhost:8080/v1", false));
    }

    /** An Ollama name over a remote URL is Ollama Cloud — a hosted service with nothing to pin. */
    @Test
    void skipsHostedProvidersEvenWhenTheNameMatches() {
        assertEquals(Strategy.NONE,
                EmbeddingModelKeepAlive.strategyFor("ollama-cloud", "https://ollama.com/v1", false));
        assertEquals(Strategy.NONE,
                EmbeddingModelKeepAlive.strategyFor("together", "https://api.together.xyz/v1", false));
    }

    @Test
    void skipsWhenProviderOrUrlIsMissing() {
        assertEquals(Strategy.NONE,
                EmbeddingModelKeepAlive.strategyFor("", "http://localhost:11434/v1", false));
        assertEquals(Strategy.NONE,
                EmbeddingModelKeepAlive.strategyFor("ollama-local", null, false));
    }

    @Test
    void derivesOllamasNativeRootFromTheCompatUrl() {
        assertEquals("http://localhost:11434",
                EmbeddingModelKeepAlive.nativeRoot("http://localhost:11434/v1"));
        assertEquals("http://localhost:11434",
                EmbeddingModelKeepAlive.nativeRoot("http://localhost:11434/v1/"));
        assertEquals("http://localhost:11434",
                EmbeddingModelKeepAlive.nativeRoot("http://localhost:11434"));
    }

    /**
     * JCLAW-1102: a self-hosted backend reached over a VPN holds a model resident exactly as
     * a loopback one does. The classification is an argument rather than a config read so
     * this stays a pure routing decision.
     */
    @Test
    void pinsADeclaredSelfHostedBackendReachedOverAVpn() {
        var tailnet = "http://100.108.220.119:11434/v1";
        assertEquals(Strategy.NONE,
                EmbeddingModelKeepAlive.strategyFor("ollama-local", tailnet, false));
        assertEquals(Strategy.OLLAMA_KEEP_ALIVE,
                EmbeddingModelKeepAlive.strategyFor("ollama-local", tailnet, true));
    }

    /** The classification says where a provider runs, not what it runs — the backend decides. */
    @Test
    void theDeclarationDoesNotInventAPinForABackendThatHasNone() {
        assertEquals(Strategy.NONE,
                EmbeddingModelKeepAlive.strategyFor("vllm-remote", "http://100.108.220.119:8000/v1", true));
    }

    /** The classification names a provider; with no URL there is no host to pin against. */
    @Test
    void theDeclarationStillNeedsABaseUrl() {
        assertEquals(Strategy.NONE,
                EmbeddingModelKeepAlive.strategyFor("ollama-local", null, true));
        assertEquals(Strategy.NONE,
                EmbeddingModelKeepAlive.strategyFor("ollama-local", "", true));
    }
}
