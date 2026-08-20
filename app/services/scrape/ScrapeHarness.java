package services.scrape;

import tools.WebFetchTool;

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

    /** One rung's fetch: URL in, LLM-visible text out. Errors are returned, not thrown,
     *  because an error string is itself a classifiable outcome. */
    @FunctionalInterface
    public interface Rung {
        String fetch(String url);
    }

    /** Rung 1: the shipped {@code web_fetch} path — OkHttp, SsrfGuard, Readability,
     *  markdown. A null agent is intentional: {@code WebFetchTool} only uses one for
     *  the oversize-workspace-write branch, so the harness exercises fetch and
     *  extraction without dragging agent state into the measurement. */
    public static Rung rung1() {
        var tool = new WebFetchTool();
        return url -> tool.execute("{\"url\":\"%s\",\"mode\":\"text\"}".formatted(url), null);
    }

    /** {@code detail} carries the head of a failing fetch's output. Without it a run
     *  reports ERROR without saying what the error was, which makes the harness
     *  unfalsifiable — the failure mode it exists to prevent, one level up. */
    public record Result(String url, String stratum, String vendor, String outcome,
                         String rendering, boolean ok, ScrapeReason reason,
                         int chars, boolean titleSeen, long ms, String detail) {}

    public record Score(int total, int ok, double rate) {}

    /** {@code byStratum} is what the epic gates on; {@code byVendor} answers "which
     *  WAFs can we get past", which an aggregate cannot. Both come from one run. */
    public record RungReport(String rung, int attempted, int ok, double rate,
                             Map<String, Score> byStratum, Map<String, Score> byVendor,
                             Map<String, Score> byRendering, Map<String, Integer> byReason,
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
        String out;
        try {
            out = rung.fetch(e.url());
        } catch (RuntimeException ex) {
            out = "Error: " + ex;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        var reason = BlockClassifier.classify(out, e.groundTruth());
        boolean ok = reason == ScrapeReason.OK;
        var text = out == null ? "" : out;
        return new Result(e.url(), e.stratum(), e.vendor(), e.outcome(), e.rendering(),
                ok, reason, text.length(), e.groundTruth().titleSeen(text), ms,
                ok ? null : text.substring(0, Math.min(200, text.length())).replace('\n', ' '));
    }

    private static RungReport report(String rungName, ScrapeCorpus.Corpus corpus,
                                     List<Result> results) {
        var byReason = new LinkedHashMap<String, Integer>();
        results.forEach(r -> byReason.merge(r.reason().name(), 1, Integer::sum));
        int ok = (int) results.stream().filter(Result::ok).count();
        return new RungReport(rungName, results.size(), ok, rate(ok, results.size()),
                group(results, Result::stratum), group(results, Result::vendor),
                group(results, Result::rendering), byReason, List.copyOf(results));
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
