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
                EmbeddingModelKeepAlive.strategyFor("ollama-local", "http://localhost:11434/v1"));
    }

    @Test
    void pinsLmStudioThroughItsTtl() {
        assertEquals(Strategy.LM_STUDIO_TTL,
                EmbeddingModelKeepAlive.strategyFor("lmstudio", "http://127.0.0.1:1234/v1"));
        assertEquals(Strategy.LM_STUDIO_TTL,
                EmbeddingModelKeepAlive.strategyFor("lm-studio", "http://127.0.0.1:1234/v1"));
    }

    /** vLLM holds the model for the server's lifetime; llama.cpp is server-flag only. */
    @Test
    void sendsNothingToBackendsWithNoClientSidePin() {
        assertEquals(Strategy.NONE,
                EmbeddingModelKeepAlive.strategyFor("vllm-local", "http://localhost:8000/v1"));
        assertEquals(Strategy.NONE,
                EmbeddingModelKeepAlive.strategyFor("llamacpp", "http://localhost:8080/v1"));
    }

    /** An Ollama name over a remote URL is Ollama Cloud — a hosted service with nothing to pin. */
    @Test
    void skipsHostedProvidersEvenWhenTheNameMatches() {
        assertEquals(Strategy.NONE,
                EmbeddingModelKeepAlive.strategyFor("ollama-cloud", "https://ollama.com/v1"));
        assertEquals(Strategy.NONE,
                EmbeddingModelKeepAlive.strategyFor("together", "https://api.together.xyz/v1"));
    }

    @Test
    void skipsWhenProviderOrUrlIsMissing() {
        assertEquals(Strategy.NONE,
                EmbeddingModelKeepAlive.strategyFor("", "http://localhost:11434/v1"));
        assertEquals(Strategy.NONE,
                EmbeddingModelKeepAlive.strategyFor("ollama-local", null));
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
}
