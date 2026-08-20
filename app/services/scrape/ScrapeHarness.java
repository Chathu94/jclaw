package services.scrape;

import okhttp3.OkHttpClient;
import utils.SsrfGuard;
import utils.WebExtraction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the CF-100 corpus and reports per-rung access rates (JCLAW-1081).
 *
 * <p>Only rung 1 exists today, so this establishes the baseline the epic must beat.
 * Rungs 2-4 land with JCLAW-1087/1088/1089 and register here.
 *
 * <p><b>Each rung is measured independently, never through the escalation ladder.</b>
 * The ladder returns one aggregate outcome, which is exactly the attribution the
 * harness exists to produce.
 *
 * <p><b>Rungs run the real code path, never a stand-in HTTP client.</b> Which client
 * gets through is the whole question — curl's TLS fingerprint is not OkHttp's, is not
 * the impersonation sidecar's, is not Chromium's — so a harness that substituted its
 * own fetcher would measure a client the product does not ship.
 */
public final class ScrapeHarness {

    private ScrapeHarness() {}

    /** One rung's attempt. Failures are returned as observations, not thrown, because
     *  a failure is itself a classifiable outcome. */
    @FunctionalInterface
    public interface Rung {
        ScrapeObservation fetch(String url);
    }

    private static final Map<String, String> HEADERS =
            Map.of("User-Agent", "Mozilla/5.0 (compatible; JClaw/1.0)");

    /** The harness's own guarded client. {@code WebFetchTool.CLIENT} is package-private
     *  in {@code tools}, and duplicating its construction here is the smaller cost —
     *  same {@link SsrfGuard#buildGuardedClient} call, same timeouts. */
    private static final OkHttpClient CLIENT = SsrfGuard.buildGuardedClient(10, 30);

    /**
     * Rung 1: the shipped fetch-and-extract chain — OkHttp, SsrfGuard, manual
     * redirects, Readability, markdown.
     *
     * <p>Calls {@link WebExtraction} directly rather than {@code WebFetchTool.execute}.
     * Since JCLAW-1082 the tool is a thin wrapper over this chain, and the wrapper's
     * contribution is presentation — error strings and the html-mode branch — which the
     * classifier would then have to parse back out. Going direct also gives the raw
     * body, without which JCLAW-1086's classifier cannot tell a gate from a SPA. It is
     * the same code path {@code web_scrape} uses per page.
     */
    public static Rung rung1() {
        return url -> {
            try {
                var fetched = WebExtraction.fetch(url, CLIENT, HEADERS);
                return ScrapeObservation.of(fetched, WebExtraction.toText(fetched));
            } catch (Exception e) {
                var m = e.getMessage();
                return ScrapeObservation.failed(url,
                        m == null || m.isBlank() ? e.getClass().getSimpleName() : m);
            }
        };
    }

    /** {@code detail} carries the head of a failing fetch's output. Without it a run
     *  reports ERROR without saying what the error was, which makes the harness
     *  unfalsifiable — the failure mode it exists to prevent, one level up. */
    /**
     * Rung 1s: the shipped {@code web_scrape} tool's own per-URL path — everything rung 1
     * does, plus SSRF admission, robots.txt and per-host pacing.
     *
     * <p>Not a separate capability but a separate <em>policy</em>. Comparing it against
     * rung 1 answers a question the epic otherwise cannot: what politeness costs in
     * access. The corpus is crawled at depth 0, one URL per entry, because the harness
     * scores per-URL reach and a crawl would conflate that with link discovery.
     */
    public static Rung rungScrape() {
        var tool = new tools.WebScrapeTool();
        return tool::fetchSingle;
    }

    /** {@code detail} carries the head of a failing fetch's output. Without it a run
     *  reports ERROR without saying what the error was, which makes the harness
     *  unfalsifiable — the failure mode it exists to prevent, one level up.
     *
     *  <p>{@code nextRung} is what the aggregate cannot say: which rung would have to
     *  exist for this failure to become a success. {@code prerender} counts origins that
     *  would serve a declared crawler more than they served us. */
    public record Result(String url, String stratum, String vendor, String outcome,
                         String rendering, boolean ok, ScrapeReason reason,
                         ScrapeRung nextRung, boolean prerender,
                         int chars, boolean titleSeen, long ms, String detail) {}

    public record Score(int total, int ok, double rate) {}

    /** {@code byStratum} is what the epic gates on; {@code byVendor} answers "which
     *  WAFs can we get past", which an aggregate cannot. Both come from one run. */
    public record RungReport(String rung, int attempted, int ok, double rate,
                             Map<String, Score> byStratum, Map<String, Score> byVendor,
                             Map<String, Score> byRendering, Map<String, Integer> byReason,
                             Map<String, Integer> byNextRung, int prerenderCapable,
                             List<Result> results) {}

    public static RungReport run(String rungName, Rung rung, ScrapeCorpus.Corpus corpus,
                                 int concurrency) {
        var results = new ArrayList<Result>();
        var counter = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(Math.max(1, concurrency))) {
            var futures = corpus.entries().stream()
                    .map(e -> pool.submit(() -> measure(rung, e)))
                    .toList();
            for (var f : futures) {
                try {
                    results.add(f.get(120, TimeUnit.SECONDS));
                } catch (Exception ex) {
                    counter.incrementAndGet();
                }
            }
        }
        return report(rungName, corpus, results);
    }

    private static Result measure(Rung rung, ScrapeCorpus.Entry e) {
        long t0 = System.nanoTime();
        ScrapeObservation obs;
        try {
            obs = rung.fetch(e.url());
        } catch (RuntimeException ex) {
            obs = ScrapeObservation.failed(e.url(), String.valueOf(ex));
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;

        var gt = e.groundTruth();
        var reason = BlockClassifier.classify(obs, gt.minChars());

        // The corpus's reject markers stay in play as an INDEPENDENT check on the
        // classifier rather than as its input. If a run ever scores an interstitial as
        // content, this is what catches it — and a benchmark whose only guard is the
        // component under test has no guard at all.
        var text = obs.extractedText() == null ? "" : obs.extractedText();
        if (reason == ScrapeReason.OK && gt.rejected(text)) {
            reason = ScrapeReason.JS_CHALLENGE;
        }

        boolean ok = reason == ScrapeReason.OK;
        var detail = obs.failed() ? obs.error() : text;
        return new Result(e.url(), e.stratum(), e.vendor(), e.outcome(), e.rendering(),
                ok, reason, BlockClassifier.nextRung(reason),
                BlockClassifier.hasPrerenderMarkers(obs),
                text.length(), gt.titleSeen(text), ms,
                ok ? null : detail.substring(0, Math.min(200, detail.length())).replace('\n', ' '));
    }

    private static RungReport report(String rungName, ScrapeCorpus.Corpus corpus,
                                     List<Result> results) {
        var byReason = new LinkedHashMap<String, Integer>();
        var byNextRung = new LinkedHashMap<String, Integer>();
        for (var r : results) {
            byReason.merge(r.reason().name(), 1, Integer::sum);
            if (!r.ok()) {
                byNextRung.merge(r.nextRung().name(), 1, Integer::sum);
            }
        }
        int ok = (int) results.stream().filter(Result::ok).count();
        int prerender = (int) results.stream()
                .filter(r -> !r.ok() && r.prerender()).count();
        return new RungReport(rungName, results.size(), ok, rate(ok, results.size()),
                group(results, Result::stratum), group(results, Result::vendor),
                group(results, Result::rendering), byReason, byNextRung, prerender,
                List.copyOf(results));
    }

    private static Map<String, Score> group(List<Result> results,
                                            java.util.function.Function<Result, String> key) {
        var out = new LinkedHashMap<String, Score>();
        for (var r : results) {
            var k = key.apply(r);
            if (k == null) continue;
            var prev = out.getOrDefault(k, new Score(0, 0, 0));
            int total = prev.total() + 1;
            int ok = prev.ok() + (r.ok() ? 1 : 0);
            out.put(k, new Score(total, ok, rate(ok, total)));
        }
        return out;
    }

    private static double rate(int ok, int total) {
        return total == 0 ? 0 : Math.round(1000.0 * ok / total) / 10.0;
    }
}
