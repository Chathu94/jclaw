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

    public record Result(String url, String tier, boolean ok, ScrapeReason reason,
                         int chars, boolean titleSeen, long ms) {}

    public record TierScore(int total, int ok, double rate) {}

    public record RungReport(String rung, int attempted, int ok, double rate,
                             Map<String, TierScore> byTier, Map<String, Integer> byReason,
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
        return new Result(e.url(), e.tier(), reason == ScrapeReason.OK, reason,
                out == null ? 0 : out.length(), e.groundTruth().titleSeen(out == null ? "" : out), ms);
    }

    private static RungReport report(String rungName, ScrapeCorpus.Corpus corpus,
                                     List<Result> results) {
        var byTier = new LinkedHashMap<String, TierScore>();
        var byReason = new LinkedHashMap<String, Integer>();
        for (var r : results) {
            byReason.merge(r.reason().name(), 1, Integer::sum);
            var prev = byTier.getOrDefault(r.tier(), new TierScore(0, 0, 0));
            int total = prev.total() + 1;
            int ok = prev.ok() + (r.ok() ? 1 : 0);
            byTier.put(r.tier(), new TierScore(total, ok, rate(ok, total)));
        }
        int ok = (int) results.stream().filter(Result::ok).count();
        return new RungReport(rungName, results.size(), ok, rate(ok, results.size()),
                byTier, byReason, List.copyOf(results));
    }

    private static double rate(int ok, int total) {
        return total == 0 ? 0 : Math.round(1000.0 * ok / total) / 10.0;
    }
}
