import llm.ProviderLocality;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/**
 * JCLAW-939: what counts as a provider running on the operator's own machine.
 *
 * <p>This decides whether memory text is allowed to leave the machine, so the interesting
 * cases are the ones that must be refused — a hostname that merely looks local, and a
 * public address behind a locally-named provider.
 */
class ProviderLocalityTest extends UnitTest {

    @Test
    void loopbackAndPrivateAddressesAreLocal() {
        assertTrue(ProviderLocality.isLocalUrl("http://localhost:11434"));
        assertTrue(ProviderLocality.isLocalUrl("http://127.0.0.1:1234/v1"));
        assertTrue(ProviderLocality.isLocalUrl("http://[::1]:8080/v1"));
        assertTrue(ProviderLocality.isLocalUrl("http://192.168.1.50:11434"));
        assertTrue(ProviderLocality.isLocalUrl("http://10.0.0.4:11434"));
        assertTrue(ProviderLocality.isLocalUrl("http://172.16.5.9:11434"));
        assertTrue(ProviderLocality.isLocalUrl("http://mac-studio.local:1234/v1"));
        assertTrue(ProviderLocality.isLocalUrl("http://host.docker.internal:11434"));
    }

    @Test
    void ipv6UniqueLocalIsLocal() {
        // Java's isSiteLocalAddress only covers the deprecated fec0::/10 for v6, so
        // fc00::/7 has to be recognised separately or a ULA host reads as remote.
        assertTrue(ProviderLocality.isLocalUrl("http://[fd12:3456:789a::1]:11434"));
    }

    @Test
    void publicAddressesAreNotLocal() {
        assertFalse(ProviderLocality.isLocalUrl("https://api.openai.com/v1"));
        assertFalse(ProviderLocality.isLocalUrl("https://ollama.com/api"));
        assertFalse(ProviderLocality.isLocalUrl("http://8.8.8.8:11434"));
    }

    @Test
    void aHostnameThatMerelyLooksLocalIsRefused() {
        // The whole guarantee is that memory text stays on the machine. Hostnames are
        // never resolved here, so anything that is not local by definition or by literal
        // address has to fail closed — otherwise "localhost.attacker.com" would pass, and
        // a name that resolves to loopback today could resolve anywhere tomorrow.
        assertFalse(ProviderLocality.isLocalUrl("http://localhost.example.com/v1"));
        assertFalse(ProviderLocality.isLocalUrl("http://not-localhost/v1"));
        assertFalse(ProviderLocality.isLocalUrl("http://my-local-llm.example.com/v1"));
    }

    @Test
    void malformedAndMissingUrlsAreNotLocal() {
        assertFalse(ProviderLocality.isLocalUrl(null));
        assertFalse(ProviderLocality.isLocalUrl(""));
        assertFalse(ProviderLocality.isLocalUrl("   "));
        assertFalse(ProviderLocality.isLocalUrl("not a url"));
        assertFalse(ProviderLocality.isLocalUrl("file:///etc/passwd"));
    }
}
