import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;
import services.FetchSidecarManager;
import services.StealthSidecarManager;
import services.scrape.ScrapeReason;
import services.scrape.ScrapeRung;
import tools.scrape.ScrapeLadder;
import utils.WebExtraction;

import java.nio.charset.StandardCharsets;

/**
 * The escalation ladder (JCLAW-1099).
 *
 * <p>Whether a given rung defeats a given WAF is a fact about that WAF, measured by the
 * corpus harness against the live web. What is pinned here is the wiring the harness
 * cannot see: that a usable result is never traded away for a higher rung's worse one,
 * that an install with no sidecars still succeeds, and that the ladder stops.
 */
class ScrapeLadderTest extends UnitTest {

    @AfterEach
    void restoreRungs() {
        ConfigService.set(FetchSidecarManager.CFG_ENABLED, "true");
        ConfigService.set(StealthSidecarManager.CFG_ENABLED, "true");
        ConfigService.clearCache();
    }

    private static void disableEveryRung() {
        ConfigService.set(FetchSidecarManager.CFG_ENABLED, "false");
        ConfigService.set(StealthSidecarManager.CFG_ENABLED, "false");
        ConfigService.clearCache();
    }

    private static ScrapeLadder.Attempt plain(ScrapeReason reason, String text) {
        var fetched = new WebExtraction.FetchResult(
                text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8),
                "text/html", "https://example.test/");
        return new ScrapeLadder.Attempt(ScrapeRung.PLAIN, fetched, text, reason, null);
    }

    @Test
    void aUsablePlainResultIsNeverEscalated() {
        // Climbing a page that already read is pure cost: seconds of browser time to
        // replace text we have. The guard is here rather than at the call site so every
        // caller gets it.
        var ok = plain(ScrapeReason.OK, "the article body");
        var result = ScrapeLadder.climb("https://example.test/", ok);
        assertSame(ok, result);
        assertEquals(ScrapeRung.PLAIN, result.servedBy());
    }

    @Test
    void withNoRungsInstalledThePlainAttemptIsReturnedUnchanged() {
        // AC: absence degrades, never errors. An install without uv or without the
        // sidecar directories must still scrape, just no further than rung 1.
        disableEveryRung();
        assertFalse(ScrapeLadder.available());

        var blocked = plain(ScrapeReason.TRUST_BLOCK, null);
        var result = ScrapeLadder.climb("https://example.test/", blocked);
        assertSame(blocked, result, "a missing sidecar must not turn a block into an error");
        assertEquals(ScrapeRung.PLAIN, result.servedBy());
    }

    @Test
    void thinContentEscalatesWithoutABlockBeingDetected() {
        // A client-rendered page at zero protection is a rendering failure, not a
        // refusal. If THIN_CONTENT did not escalate, the rung that fixes the largest
        // single population in the corpus would be unreachable for it (JCLAW-1088).
        assertEquals(ScrapeRung.BROWSER,
                services.scrape.BlockClassifier.nextRung(ScrapeReason.THIN_CONTENT, ScrapeRung.PLAIN));
    }

    @Test
    void theLadderStopsWhenNothingFurtherWouldHelp() {
        // POLICY_BLOCK is a licensing or geo refusal. No transport defeats it, and a
        // ladder that kept climbing would spend a browser render to be refused again.
        disableEveryRung();
        var refused = plain(ScrapeReason.POLICY_BLOCK, null);
        assertSame(refused, ScrapeLadder.climb("https://example.test/", refused));
    }

    @Test
    void aStructuralErrorEscalatesButATimeoutDoesNot() {
        // ERROR only reaches the ladder after TransientRetryInterceptor has retried the
        // retryable statuses, so what is left is structural — a persistent 400, a
        // redirect loop — which a browser handles natively.
        assertEquals(ScrapeRung.BROWSER,
                services.scrape.BlockClassifier.nextRung(ScrapeReason.ERROR, ScrapeRung.PLAIN));
        // A slow origin will not answer a browser faster, and a render is the most
        // expensive possible way to wait.
        assertEquals(ScrapeRung.NONE,
                services.scrape.BlockClassifier.nextRung(ScrapeReason.TIMEOUT, ScrapeRung.PLAIN));
    }

    @Test
    void aStatedPolicyRefusalIsNeverEscalated() {
        // Load-bearing, and not a metric decision: an origin that says it blocks agents
        // is refusing on identity. Rung 3 demonstrably reads several of these — the
        // ladder declines to. Four corpus entries sit behind this and stay there.
        for (var attempted : java.util.List.of(ScrapeRung.PLAIN, ScrapeRung.IMPERSONATE)) {
            assertEquals(ScrapeRung.NONE,
                    services.scrape.BlockClassifier.nextRung(ScrapeReason.POLICY_BLOCK, attempted),
                    "policy refusals must not be escalated to a stealth rung");
        }
    }

    @Test
    void aDeadLinkIsNeverEscalated() {
        // 404/410 used to classify as ERROR, which escalates to BROWSER. Crawls hit dead
        // links constantly, so a handful of them spent the whole escalation budget on
        // renders that return the same 404 — and suppressed the pages that needed it.
        assertEquals(ScrapeReason.NOT_FOUND, services.scrape.BlockClassifier.classify(
                services.scrape.ScrapeObservation.failed(
                        "https://example.test/gone", "HTTP 404 fetching https://example.test/gone")));
        for (var attempted : java.util.List.of(ScrapeRung.PLAIN, ScrapeRung.IMPERSONATE)) {
            assertEquals(ScrapeRung.NONE,
                    services.scrape.BlockClassifier.nextRung(ScrapeReason.NOT_FOUND, attempted),
                    "a page the origin says is absent is not a transport problem");
        }
    }

    @Test
    void wouldAttemptAnswersBeforeABudgetIsSpent() {
        // The budget is claimed before the climb, so a caller must be able to ask whether
        // the climb would issue any request at all. Reasons no rung addresses used to
        // spend a slot and be counted in the "escalated N pages" line.
        assertFalse(ScrapeLadder.wouldAttempt(ScrapeReason.POLICY_BLOCK),
                "a policy refusal reaches no installed rung");
        assertFalse(ScrapeLadder.wouldAttempt(ScrapeReason.NOT_FOUND),
                "a dead link reaches no installed rung");
        disableEveryRung();
        assertFalse(ScrapeLadder.wouldAttempt(ScrapeReason.THIN_CONTENT),
                "with no sidecar installed nothing is attempted");
    }

    @Test
    void anEscalatedFetchCarriesTheCallersLanguage() {
        // A crawl asked for "ja" and the escalated fetch silently reverted to en-US, so
        // the crawl mixed two languages with only a rung marker to explain why.
        assertEquals("ja, *;q=0.5",
                ScrapeLadder.impersonatedHeaders("ja").get("Accept-Language"));
    }

    @Test
    void escalationIsAvailableWhenEitherSidecarIs() {
        ConfigService.set(FetchSidecarManager.CFG_ENABLED, "true");
        ConfigService.set(StealthSidecarManager.CFG_ENABLED, "false");
        ConfigService.clearCache();
        assertEquals(FetchSidecarManager.available(), ScrapeLadder.available());
    }
}
