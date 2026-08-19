import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.scrape.BlockClassifier;
import services.scrape.GroundTruth;
import services.scrape.ScrapeCorpus;
import services.scrape.ScrapeHarness;
import services.scrape.ScrapeReason;

import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JCLAW-1081. The known-zero and known-one cases are the point of this class: a
 * harness that reports plausible nonsense is worse than no harness, because it gets
 * believed. Everything else here exists to keep those two honest.
 */
public class ScrapeHarnessTest extends UnitTest {

    /** A real Cloudflare interstitial: valid HTML, a title, body text. Extracts to
     *  clean markdown, which is exactly why length alone cannot score it. */
    private static final String CHALLENGE_PAGE = """
            Just a moment...

            Verifying you are human. This may take a few seconds.
            example.com needs to review the security of your connection before proceeding.
            Enable JavaScript and cookies to continue.
            Performance & security by Cloudflare.
            """ + "x".repeat(2_000);

    private static final String REAL_ARTICLE =
            "# Understanding Widgets\n\nWidgets are components that "
                    + "combine several parts into one. ".repeat(40);

    private static GroundTruth gt() {
        return new GroundTruth(600,
                List.of("/cdn-cgi/challenge-platform/", "just a moment", "cf-turnstile"), null);
    }

    @Test
    public void knownZero_challengePageIsNeverScoredAsContent() {
        // Long enough and clean enough to pass a naive length check — the whole trap.
        assertTrue(CHALLENGE_PAGE.length() > gt().minChars());
        assertEquals(ScrapeReason.JS_CHALLENGE, BlockClassifier.classify(CHALLENGE_PAGE, gt()));
    }

    @Test
    public void knownOne_realArticleScoresOk() {
        assertEquals(ScrapeReason.OK, BlockClassifier.classify(REAL_ARTICLE, gt()));
    }

    @Test
    public void turnstileOutranksGenericChallenge() {
        var page = CHALLENGE_PAGE + "\n<script src=\"https://challenges.cloudflare.com/turnstile/v0/api.js\">";
        assertEquals(ScrapeReason.TURNSTILE, BlockClassifier.classify(page, gt()));
    }

    @Test
    public void thinBodyIsTrustBlockNotOk() {
        assertEquals(ScrapeReason.TRUST_BLOCK, BlockClassifier.classify("tiny", gt()));
    }

    @Test
    public void httpStatusIsRecoveredFromTheToolsErrorString() {
        assertEquals(ScrapeReason.TRUST_BLOCK,
                BlockClassifier.classify("Error fetching URL: HTTP 403 fetching https://x.test", gt()));
        assertEquals(ScrapeReason.POLICY_BLOCK,
                BlockClassifier.classify("Error fetching URL: HTTP 451 fetching https://x.test", gt()));
        assertEquals(ScrapeReason.TIMEOUT,
                BlockClassifier.classify("Error: Request timed out after 30 seconds fetching https://x.test", gt()));
    }

    @Test
    public void blankOutputIsErrorNotSilentPass() {
        assertEquals(ScrapeReason.ERROR, BlockClassifier.classify("", gt()));
        assertEquals(ScrapeReason.ERROR, BlockClassifier.classify(null, gt()));
    }

    @Test
    public void expectTitleIsReportedButDoesNotGate() {
        // Readability + markdown conversion do not reliably preserve <title>, so gating
        // on it would manufacture failures that say nothing about access.
        var withTitle = new GroundTruth(600, List.of(), "a title that is absent");
        assertEquals(ScrapeReason.OK, BlockClassifier.classify(REAL_ARTICLE, withTitle));
        assertFalse(withTitle.titleSeen(REAL_ARTICLE));
    }

    @Test
    public void harnessScoresACorpusWithoutTouchingTheNetwork() throws Exception {
        var json = """
                {"tranco_list_id":"TEST","probed_on":"2026-08-19","allocation":"equal","per_tier":1,
                 "entries":[
                  {"url":"https://ok.test","tier":"open","rank":1,
                   "ground_truth":{"min_chars":600,"reject_markers":["just a moment"]}},
                  {"url":"https://blocked.test","tier":"turnstile","rank":2,
                   "ground_truth":{"min_chars":600,"reject_markers":["just a moment"]}}]}
                """;
        var f = Files.createTempFile("cf-corpus", ".json");
        Files.writeString(f, json);
        var corpus = ScrapeCorpus.load(f);
        assertTrue(corpus.isEqualAllocation());

        ScrapeHarness.Rung stub = url -> url.contains("ok.test") ? REAL_ARTICLE : CHALLENGE_PAGE;
        var rep = ScrapeHarness.run("stub", stub, corpus, 2);

        assertEquals(2, rep.attempted());
        assertEquals(1, rep.ok());
        assertEquals(50.0, rep.rate(), 0.01);
        assertEquals(100.0, rep.byTier().get("open").rate(), 0.01);
        assertEquals(0.0, rep.byTier().get("turnstile").rate(), 0.01);
        Files.deleteIfExists(f);
    }

    @Test
    public void proportionalCorpusIsFlaggedBecauseTheGateDependsOnEqualAllocation() throws Exception {
        var f = Files.createTempFile("cf-prop", ".json");
        Files.writeString(f, """
                {"allocation":"proportional","entries":[]}""");
        assertFalse(ScrapeCorpus.load(f).isEqualAllocation());
        Files.deleteIfExists(f);
    }
}
