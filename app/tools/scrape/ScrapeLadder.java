package tools.scrape;

import services.scrape.BlockClassifier;
import services.scrape.ScrapeObservation;
import services.scrape.ScrapeReason;
import services.scrape.ScrapeRung;
import utils.WebExtraction;

import java.util.Map;

/**
 * Climbs the escalation ladder for one URL (JCLAW-1099).
 *
 * <p>Every rung existed and was measured before this class did, and none of them was
 * reachable: the tools classified a failure, logged the rung that would fix it, and gave
 * up. On the CF-100 corpus that is the difference between 38.0% and 72.0%
 * equal-allocation, or 58.8% and 91.0% prevalence-weighted.
 *
 * <p><b>Escalation, never substitution.</b> A higher rung is not a superset of a lower
 * one — measured twice. Rung 2 reads pages rung 1 cannot and the reverse; rung 3 reads
 * far more than rung 2 but still loses two corpus entries to it, because presenting as a
 * real browser earns the JavaScript shell where a plain client was served a
 * server-rendered page. So the climb keeps the best attempt seen, not the last one made.
 */
public final class ScrapeLadder {

    /**
     * Rung 2 sends no {@code User-Agent}: curl_cffi supplies the header set matching the
     * profile it forges, and overriding it would pair a Chrome ClientHello with a
     * non-Chrome agent string — a mismatch WAFs test for directly (JCLAW-1087).
     */
    public static final Map<String, String> IMPERSONATED_HEADERS = Map.of(
            "Accept", "text/html,application/xhtml+xml",
            "Accept-Language", "en-US,en;q=0.9");

    /** One rung's product, and which rung produced it. */
    public record Attempt(ScrapeRung servedBy, WebExtraction.FetchResult fetched,
                          String text, ScrapeReason reason, String detail) {

        public boolean usable() {
            return reason == ScrapeReason.OK;
        }

        int textLength() {
            return text == null ? 0 : text.length();
        }
    }

    private ScrapeLadder() {}

    /** Whether any rung above {@link ScrapeRung#PLAIN} could be attempted on this install. */
    public static boolean available() {
        return ImpersonatedFetcher.available() || RenderedFetcher.available();
    }

    /**
     * Attempt higher rungs until one succeeds or the ladder is exhausted, and return the
     * best attempt made. {@code plain} is the rung-1 result the caller already has.
     *
     * <p>Returns {@code plain} untouched when it is usable, when no higher rung is
     * installed, or when the classifier says nothing further will help — so a caller can
     * invoke this unconditionally and an install with no sidecars simply keeps rung 1.
     */
    public static Attempt climb(String url, Attempt plain) {
        if (plain.usable()) return plain;

        var best = plain;
        var last = plain;
        var attempted = plain.servedBy();

        while (true) {
            var next = BlockClassifier.nextRung(last.reason(), attempted);
            if (!isInstalled(next)) return best;

            last = attempt(next, url);
            attempted = next;
            if (last.usable()) return last;
            best = better(best, last);
        }
    }

    /** Run one URL through {@code rung}, classifying the result the way the harness does. */
    private static Attempt attempt(ScrapeRung rung, String url) {
        try {
            var fetched = rung == ScrapeRung.BROWSER
                    ? RenderedFetcher.fetch(url)
                    : ImpersonatedFetcher.fetch(url, IMPERSONATED_HEADERS);
            var text = WebExtraction.toText(fetched);
            var obs = ScrapeObservation.of(fetched, text);
            return new Attempt(rung, fetched, text, BlockClassifier.classify(obs), null);
        } catch (Exception e) {
            var message = e.getMessage();
            var detail = message == null || message.isBlank()
                    ? e.getClass().getSimpleName() : message;
            var obs = ScrapeObservation.failed(url, detail);
            return new Attempt(rung, null, null, BlockClassifier.classify(obs), detail);
        }
    }

    /**
     * The attempt worth keeping. A usable one always wins; otherwise the one that
     * extracted more text, with ties going to the lower rung so a browser returning an
     * empty JavaScript shell never displaces a plain fetch's partial content.
     */
    private static Attempt better(Attempt a, Attempt b) {
        if (a.usable()) return a;
        if (b.usable()) return b;
        return b.textLength() > a.textLength() ? b : a;
    }

    /** {@link ScrapeRung#NONE} and any rung whose sidecar is absent are both "stop here". */
    private static boolean isInstalled(ScrapeRung rung) {
        return switch (rung) {
            case IMPERSONATE -> ImpersonatedFetcher.available();
            case BROWSER -> RenderedFetcher.available();
            case PLAIN, PROVIDER, NONE -> false;
        };
    }
}
