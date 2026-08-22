import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import services.FetchSidecarManager;
import services.scrape.BlockClassifier;
import services.scrape.ScrapeReason;
import services.scrape.ScrapeRung;
import tools.scrape.ImpersonatedFetcher;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Rung 2 — the TLS-impersonating fetch lane (JCLAW-1087).
 *
 * <p>What is worth pinning here is the containment and the degrade path, not the
 * impersonation itself: whether a forged ClientHello gets past a given origin is a
 * fact about that origin's WAF, measured by the corpus harness against the live web,
 * and a unit test asserting it would only encode one afternoon's WAF configuration.
 */
class ImpersonatedFetcherTest extends UnitTest {

    private static final Map<String, String> H = Map.of("Accept", "text/html");

    private final ScrapeConfigGuard config = new ScrapeConfigGuard();

    @AfterEach
    void clearOverrides() {
        config.restore();
    }

    // ==================== Containment across the seam ====================

    @Test
    void anUnsafeUrlIsRefusedBeforeTheSidecarIsEvenContacted() {
        // The guard that actually contains rung 2 is inside the transport, ahead of
        // FetchSidecarManager.ensureRunning: hostResolverRule throws everything
        // assertUrlSafe does AND returns the address it validated, so curl is never left
        // to resolve the name a second time. Running ahead of ensureRunning is also what
        // makes this assertable with no sidecar installed.
        var transport = ImpersonatedFetcher.transport();
        for (var url : List.of("http://127.0.0.1:9/secret",
                               "http://169.254.169.254/latest/meta-data/",
                               "http://[::1]:9/")) {
            // Parsed outside the lambda so the assertion can only pass on the guard's
            // refusal, never on a URI that failed to parse.
            var uri = URI.create(url);
            assertThrows(SecurityException.class,
                    () -> transport.exchange(uri, H),
                    "expected an SsrfGuard refusal for " + url);
        }
    }

    @Test
    void theSidecarNeverFollowsARedirectItself() throws IOException {
        // The property this rung's containment rests on lives in the sidecar, where the
        // JVM cannot observe it: curl following a 3xx on its own would reach an address
        // SsrfGuard never saw. Asserted against the source, the way StealthBrowserTest
        // reaches into sidecar/*/ssrf.py for the guard it cannot call.
        var serve = Files.readString(
                Path.of(Play.applicationPath.getAbsolutePath(), "sidecar/fetch/serve.py"));
        assertTrue(serve.contains("allow_redirects=False"),
                "the fetch sidecar must hand every hop back to the JVM");
        assertFalse(serve.contains("allow_redirects=True"),
                "no call site may re-enable redirect following");
    }

    // ==================== Feature detection ====================

    @Test
    void disablingTheRungDegradesRatherThanErroring() {
        // AC: an install without the sidecar falls back to rung 1 silently. available()
        // must answer without spawning anything, so the ladder can skip the rung.
        config.set(FetchSidecarManager.CFG_ENABLED, "false");
        assertFalse(FetchSidecarManager.available());
        assertFalse(ImpersonatedFetcher.available());
    }

    @Test
    void theProfileIsOperatorPinnable() {
        // --model carries the profile, and LocalSidecarDaemon.isHealthy(expectedModel)
        // compares it, so a repin forces a respawn instead of leaving the old
        // fingerprint in service.
        config.delete(FetchSidecarManager.CFG_PROFILE);
        assertEquals("chrome", FetchSidecarManager.profile(),
                "the rolling alias is the compiled-in default, not a value a test wrote");
        config.set(FetchSidecarManager.CFG_PROFILE, "chrome146");
        assertEquals("chrome146", FetchSidecarManager.profile());
    }

    // ==================== Ladder-aware escalation advice ====================

    @Test
    void escalationNeverRecommendsTheRungThatJustFailed() {
        // Before this took the attempted rung into account, a rung-2 report answered
        // IMPERSONATE for every TRUST_BLOCK — i.e. "retry the thing that just failed".
        assertEquals(ScrapeRung.IMPERSONATE,
                BlockClassifier.nextRung(ScrapeReason.TRUST_BLOCK, ScrapeRung.PLAIN));
        assertEquals(ScrapeRung.BROWSER,
                BlockClassifier.nextRung(ScrapeReason.TRUST_BLOCK, ScrapeRung.IMPERSONATE));
    }

    @Test
    void escalationStopsAtTheTopOfTheLadder() {
        assertEquals(ScrapeRung.NONE,
                BlockClassifier.nextRung(ScrapeReason.THIN_CONTENT, ScrapeRung.PROVIDER));
    }

    @Test
    void aHopelessReasonStaysHopelessAtEveryRung() {
        for (var attempted : List.of(ScrapeRung.PLAIN, ScrapeRung.IMPERSONATE, ScrapeRung.BROWSER)) {
            assertEquals(ScrapeRung.NONE,
                    BlockClassifier.nextRung(ScrapeReason.POLICY_BLOCK, attempted),
                    "POLICY_BLOCK is a licensing/geo refusal — no rung defeats it");
        }
    }
}
