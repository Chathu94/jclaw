import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;
import services.FetchSidecarManager;
import services.scrape.BlockClassifier;
import services.scrape.ScrapeReason;
import services.scrape.ScrapeRung;
import tools.scrape.ImpersonatedFetcher;
import utils.WebExtraction;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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

    @AfterEach
    void clearOverrides() {
        ConfigService.set(FetchSidecarManager.CFG_ENABLED, "true");
        ConfigService.clearCache();
    }

    /** A transport that replays a canned script, recording what it was asked for. */
    private static WebExtraction.Transport scripted(List<WebExtraction.Exchange> script,
                                                    List<URI> seen) {
        var i = new AtomicInteger();
        return (uri, headers) -> {
            seen.add(uri);
            return script.get(Math.min(i.getAndIncrement(), script.size() - 1));
        };
    }

    // ==================== Containment across the seam ====================

    @Test
    void redirectOntoAPrivateAddressIsRefusedOnTheImpersonationLane() {
        // The whole reason the sidecar is forbidden to follow redirects: it is an
        // unguarded HTTP client, so a 3xx it chased itself would never reach SsrfGuard.
        // Handing the hop back must put it through the same guard rung 1 uses.
        var seen = new java.util.ArrayList<URI>();
        var transport = scripted(List.of(
                new WebExtraction.Exchange(302, new byte[0], "", "http://127.0.0.1:9/secret")), seen);

        var boom = assertThrows(SecurityException.class,
                () -> WebExtraction.fetch("https://example.com", H, transport));
        assertTrue(boom.getMessage().contains("SSRF guard"),
                "expected an SsrfGuard refusal, got: " + boom.getMessage());
        assertEquals(1, seen.size(), "the private-address hop must never be requested");
    }

    @Test
    void redirectsAreWalkedByTheCallerNotTheTransport() throws IOException {
        var seen = new java.util.ArrayList<URI>();
        var transport = scripted(List.of(
                new WebExtraction.Exchange(301, new byte[0], "", "https://example.com/moved"),
                new WebExtraction.Exchange(200, "hello".getBytes(), "text/plain", null)), seen);

        var result = WebExtraction.fetch("https://example.com", H, transport);
        assertEquals(2, seen.size(), "each hop must be a separate transport call");
        assertEquals("https://example.com/moved", result.finalUrl());
        assertEquals("hello", new String(result.body()));
    }

    // ==================== Feature detection ====================

    @Test
    void disablingTheRungDegradesRatherThanErroring() {
        // AC: an install without the sidecar falls back to rung 1 silently. available()
        // must answer without spawning anything, so the ladder can skip the rung.
        ConfigService.set(FetchSidecarManager.CFG_ENABLED, "false");
        ConfigService.clearCache();
        assertFalse(FetchSidecarManager.available());
        assertFalse(ImpersonatedFetcher.available());
    }

    @Test
    void theProfileIsOperatorPinnable() {
        // --model carries the profile, and LocalSidecarDaemon.isHealthy(expectedModel)
        // compares it, so a repin forces a respawn instead of leaving the old
        // fingerprint in service.
        assertEquals("chrome", FetchSidecarManager.profile());
        ConfigService.set(FetchSidecarManager.CFG_PROFILE, "chrome146");
        ConfigService.clearCache();
        assertEquals("chrome146", FetchSidecarManager.profile());
        ConfigService.set(FetchSidecarManager.CFG_PROFILE, "chrome");
        ConfigService.clearCache();
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
