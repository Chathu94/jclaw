package tools;

import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;
import okhttp3.OkHttpClient;
import services.EventLogger;
import services.scrape.BlockClassifier;
import services.scrape.ScrapeObservation;
import services.scrape.ScrapeReason;
import services.scrape.ScrapeRung;
import utils.PlayConfig;
import utils.RobotsCache;
import utils.SsrfGuard;
import utils.WebExtraction;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

/**
 * Read a page and the pages it links to, as one block of Markdown.
 *
 * <p>The gap between this and {@code web_fetch} is a frontier, not an engine: both
 * run the same {@link WebExtraction} fetch-and-extract chain, and this one adds a
 * queue, a budget, and a scope rule (JCLAW-1083). Every URL — seed and harvested
 * alike — enters through {@link WebExtraction#fetch}, so {@link SsrfGuard} sees each
 * one and each redirect hop.
 *
 * <p>Breadth-first on purpose. A crawl that runs out of budget mid-way should have
 * spent it on the seed's immediate neighbours rather than one deep chain, because
 * the pages nearest the seed are the ones the caller asked about.
 */
public class WebScrapeTool implements ToolRegistry.Tool {

    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int TIMEOUT_SECONDS = 30;

    /** Mirrors {@code WebFetchTool.CLIENT}: package-private and non-final so tests can
     *  substitute a socket-free client, since SsrfGuard's DNS blocks the loopback a
     *  local test server would bind. Production must not mutate it. */
    static OkHttpClient CLIENT = SsrfGuard.buildGuardedClient(
            CONNECT_TIMEOUT_SECONDS, TIMEOUT_SECONDS);

    private static final String USER_AGENT = "Mozilla/5.0 (compatible; JClaw/1.0)";
    private static final Map<String, String> HEADERS = Map.of("User-Agent", USER_AGENT);

    /** {@code jclaw} is the token a site would write in a {@code User-agent:} line;
     *  the header above is what goes on the wire. */
    private static final RobotsCache.Identity IDENTITY =
            new RobotsCache.Identity(USER_AGENT, "jclaw");

    private static final String ARG_URL = "url";
    private static final String ARG_MAX_PAGES = "maxPages";
    private static final String ARG_MAX_DEPTH = "maxDepth";
    private static final String ARG_SAME_HOST = "sameHostOnly";

    private static final String CFG_MAX_PAGES = "web_scrape.max-pages";
    private static final String CFG_MAX_DEPTH = "web_scrape.max-depth";
    private static final String CFG_TIMEOUT_SECONDS = "web_scrape.timeout-seconds";
    private static final String CFG_RESPECT_ROBOTS = "web_scrape.respect-robots";
    private static final String CFG_CONCURRENCY = "web_scrape.concurrency";

    /** Ceilings an operator can lower but the model cannot raise. This is a tool call
     *  inside a conversation, not a background crawler: the caller is waiting, and the
     *  result has to fit a context window. */
    private static final int DEFAULT_MAX_PAGES = 25;
    private static final int DEFAULT_MAX_DEPTH = 2;
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    /** Outbound fan-out. Deliberately operator config and never a tool argument:
     *  fan-out is a resource knob, not a task-shaping one, so it belongs beside
     *  {@code dispatcher.llm.maxRequestsPerHost} rather than in a model-supplied
     *  argument. Raising it cannot make a crawl ruder — per-host pacing
     *  ({@link RobotsCache#awaitSlot}) claims slots atomically, so concurrent
     *  workers on one host queue onto consecutive slots instead of firing together.
     *  What it buys is overlapping round-trip time, turning a latency-bound crawl
     *  into a pacing-bound one. */
    private static final int DEFAULT_CONCURRENCY = 4;
    private static final int MAX_CONCURRENCY = 16;

    /** Total budget across every page, matching what one web_fetch may return. */
    private static final int MAX_TOTAL_CHARS = WebExtraction.MAX_TEXT_LENGTH;

    @Override public String name() { return "web_scrape"; }
    @Override public String category() { return "Web"; }
    @Override public String icon() { return "globe"; }

    @Override
    public String shortDescription() {
        return "Read a page and the pages it links to, as one block of Markdown.";
    }

    @Override
    public List<ToolAction> actions() {
        return List.of(new ToolAction("scrape",
                "Crawl from a URL, following links within a page and depth budget"));
    }

    @Override
    public String description() {
        return """
                Read a starting URL and the pages it links to, returning all of them as one \
                Markdown document with each page's source URL as a heading. \
                Use this for a documentation site, a multi-page article, or any question that \
                needs more than one page. \
                For a single page use web_fetch instead — it is faster and cheaper.""";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, Map.of(
                        ARG_URL, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION, "The URL to start from"),
                        ARG_MAX_PAGES, Map.of(SchemaKeys.TYPE, "integer",
                                SchemaKeys.DESCRIPTION,
                                "Maximum pages to read (default %d)".formatted(DEFAULT_MAX_PAGES)),
                        ARG_MAX_DEPTH, Map.of(SchemaKeys.TYPE, "integer",
                                SchemaKeys.DESCRIPTION,
                                "How many links deep to follow; 0 reads only the starting URL (default %d)"
                                        .formatted(DEFAULT_MAX_DEPTH)),
                        ARG_SAME_HOST, Map.of(SchemaKeys.TYPE, "boolean",
                                SchemaKeys.DESCRIPTION,
                                "Stay on the starting URL's host (default true)")
                ),
                SchemaKeys.REQUIRED, List.of(ARG_URL)
        );
    }

    /** Holds no handles between calls and writes nothing to disk. */
    @Override public boolean parallelSafe() { return true; }

    private record Page(String url, String text) {}

    /** A frontier URL the guard declined, kept apart from {@link Page} so a refusal
     *  never spends a slot in the page budget. */
    private record Refusal(String url, String why) {}

    @Override
    public String execute(String argsJson, Agent agent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        URI seed;
        try {
            seed = URI.create(args.get(ARG_URL).getAsString().strip());
        } catch (RuntimeException e) {
            return "Error: could not parse url: %s".formatted(e.getMessage());
        }

        int maxPages = Math.clamp(
                args.has(ARG_MAX_PAGES) ? args.get(ARG_MAX_PAGES).getAsInt() : configMaxPages(),
                1, configMaxPages());
        int maxDepth = Math.clamp(
                args.has(ARG_MAX_DEPTH) ? args.get(ARG_MAX_DEPTH).getAsInt() : configMaxDepth(),
                0, configMaxDepth());
        boolean sameHostOnly = !args.has(ARG_SAME_HOST) || args.get(ARG_SAME_HOST).getAsBoolean();

        try {
            SsrfGuard.assertSafeScheme(seed);
        } catch (SecurityException e) {
            return "Error: URL rejected by SSRF guard: %s".formatted(e.getMessage());
        }
        return crawl(seed, maxPages, maxDepth, sameHostOnly);
    }

    /**
     * Fetch one URL exactly as the crawl would: SSRF admission, robots, per-host pacing,
     * then the shared fetch and extraction (JCLAW-1094).
     *
     * <p>Public so {@code ScrapeHarness} can measure this tool as a rung rather than a
     * replica of it. Returning the observation rather than rendered text keeps the rung
     * comparable with rung 1 — both hand the same classifier the same raw body, so a
     * difference in their access rates is a difference in admission, not in how the two
     * were scored.
     */
    public ScrapeObservation fetchSingle(String url) {
        URI uri;
        try {
            uri = URI.create(url);
            SsrfGuard.assertSafeScheme(uri);
        } catch (RuntimeException e) {
            return ScrapeObservation.failed(url, "rejected: " + e.getMessage());
        }
        boolean respect = respectRobots();
        if (respect && !RobotsCache.isAllowed(uri, CLIENT, IDENTITY)) {
            return ScrapeObservation.failed(url, "disallowed by robots.txt");
        }
        try {
            RobotsCache.awaitSlot(uri, respect
                    ? RobotsCache.delayMillis(uri, CLIENT, IDENTITY)
                    : RobotsCache.DEFAULT_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ScrapeObservation.failed(url, "interrupted");
        }
        try {
            var fetched = WebExtraction.fetch(url, CLIENT, HEADERS);
            return ScrapeObservation.of(fetched, WebExtraction.toText(fetched));
        } catch (Exception e) {
            return ScrapeObservation.failed(url, reason(e));
        }
    }

    private String crawl(URI seed, int maxPages, int maxDepth, boolean sameHostOnly) {
        var deadline = System.nanoTime()
                + Duration.ofSeconds(configTimeoutSeconds()).toNanos();
        var seen = new LinkedHashSet<String>();
        var pages = new ArrayList<Page>();
        var refused = new ArrayList<Refusal>();
        boolean respectRobots = respectRobots();

        var level = new ArrayList<URI>(List.of(seed));
        seen.add(canonical(seed));
        int depth = 0;
        String stoppedBecause = null;
        int totalChars = 0;
        int unvisited = 0;

        try (var pool = Executors.newFixedThreadPool(configConcurrency())) {
            while (!level.isEmpty()) {
                // Admission runs single-threaded, before any work is submitted. Both
                // checks are cheap and rejecting here keeps `seen`, the refusal list
                // and the budget free of synchronisation.
                var admitted = new ArrayList<URI>();
                for (var uri : level) {
                    try {
                        SsrfGuard.assertSafeScheme(uri);
                    } catch (SecurityException e) {
                        refused.add(new Refusal(uri.toString(), e.getMessage()));
                        continue;
                    }
                    if (respectRobots && !RobotsCache.isAllowed(uri, CLIENT, IDENTITY)) {
                        refused.add(new Refusal(uri.toString(), "disallowed by robots.txt"));
                        continue;
                    }
                    admitted.add(uri);
                }

                // Slice to the remaining budget before submitting, so the page count is
                // exact without workers racing on a shared counter.
                int room = maxPages - pages.size();
                if (room <= 0) {
                    stoppedBecause = "page budget (%d) reached".formatted(maxPages);
                    unvisited += admitted.size();
                    break;
                }
                if (admitted.size() > room) {
                    // Truncating a level to the remaining budget IS the stop reason.
                    // Leaving it unset reported "Stopped: null" alongside a non-zero
                    // unread count.
                    unvisited += admitted.size() - room;
                    admitted = new ArrayList<>(admitted.subList(0, room));
                    stoppedBecause = "page budget (%d) reached".formatted(maxPages);
                }
                if (admitted.isEmpty()) {
                    break;
                }

                var futures = admitted.stream()
                        .map(uri -> pool.submit(() -> fetchOne(uri, respectRobots)))
                        .toList();

                // Collected in submission order, never completion order: the JCLAW-1091
                // harness compares runs, and a result whose page order varies per run is
                // not comparable.
                var fetchedLevel = new ArrayList<WebExtraction.FetchResult>();
                for (int i = 0; i < futures.size(); i++) {
                    var uri = admitted.get(i);
                    try {
                        var outcome = futures.get(i).get();
                        if (!outcome.usable()) {
                            // Name the reason and the rung that would address it, rather
                            // than a bare failure. An agent reading "TURNSTILE" knows not
                            // to retry; "[Could not fetch]" invites a retry loop.
                            pages.add(new Page(uri.toString(), "[Not retrieved \u2014 %s%s%s]"
                                    .formatted(outcome.reason(),
                                            outcome.detail() == null ? "" : ": " + outcome.detail(),
                                            outcome.nextRung() == ScrapeRung.NONE ? ""
                                                    : "; needs " + outcome.nextRung())));
                            continue;
                        }
                        pages.add(new Page(outcome.fetched().finalUrl(), outcome.text()));
                        totalChars += outcome.text().length();
                        fetchedLevel.add(outcome.fetched());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        stoppedBecause = "interrupted";
                        break;
                    } catch (ExecutionException e) {
                        pages.add(new Page(uri.toString(),
                                "[Not retrieved \u2014 %s]".formatted(reason(e))));
                    }
                }

                if (stoppedBecause != null) {
                    break;
                }
                if (System.nanoTime() > deadline) {
                    stoppedBecause = "time budget (%ds) reached".formatted(configTimeoutSeconds());
                    break;
                }
                if (totalChars >= MAX_TOTAL_CHARS) {
                    stoppedBecause = "content budget (%d characters) reached".formatted(MAX_TOTAL_CHARS);
                    break;
                }
                if (depth >= maxDepth) {
                    break;
                }

                var next = new ArrayList<URI>();
                for (var fetched : fetchedLevel) {
                    collectLinks(fetched, seed, sameHostOnly, seen, next);
                }
                level = next;
                depth++;
            }
        }

        return render(seed, pages, refused, maxDepth, sameHostOnly, stoppedBecause, unvisited);
    }

    /** One page's work, as it runs on the pool. Pacing happens here so the wait for a
     *  host's next slot overlaps other hosts' fetches instead of blocking the crawl. */
    private record Outcome(WebExtraction.FetchResult fetched, String text,
                           ScrapeReason reason, ScrapeRung nextRung, String detail) {
        boolean usable() {
            return reason == ScrapeReason.OK;
        }
    }

    private Outcome fetchOne(URI uri, boolean respectRobots) {
        try {
            RobotsCache.awaitSlot(uri, respectRobots
                    ? RobotsCache.delayMillis(uri, CLIENT, IDENTITY)
                    : RobotsCache.DEFAULT_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return classified(uri, null, ScrapeObservation.failed(uri.toString(), "interrupted"));
        }
        try {
            var fetched = WebExtraction.fetch(uri.toString(), CLIENT, HEADERS);
            var text = WebExtraction.toText(fetched);
            return classified(uri, fetched, ScrapeObservation.of(fetched, text));
        } catch (Exception e) {
            // One unreachable page must not end the crawl — the caller asked for a
            // site, and a broken link on it is the site's problem, not the run's.
            return classified(uri, null, ScrapeObservation.failed(uri.toString(), reason(e)));
        }
    }

    /** Runs the shared classifier and records the outcome, so a live install produces
     *  the same telemetry the offline harness does. */
    private static Outcome classified(URI uri, WebExtraction.FetchResult fetched,
                                      ScrapeObservation obs) {
        var reason = BlockClassifier.classify(obs);
        var next = BlockClassifier.nextRung(reason);
        if (reason != ScrapeReason.OK) {
            EventLogger.info("scrape",
                    "%s: %s (would need %s)".formatted(uri, reason, next),
                    obs.failed() ? obs.error() : "extracted %d chars".formatted(obs.textLength()));
        }
        return new Outcome(fetched, obs.extractedText(), reason, next, obs.error());
    }

    /** Runs after a level completes, single-threaded, so {@code seen} needs no
     *  synchronisation and the next level's order is deterministic. */
    private static void collectLinks(WebExtraction.FetchResult fetched, URI seed,
                                     boolean sameHostOnly, LinkedHashSet<String> seen,
                                     List<URI> next) {
        for (var link : WebExtraction.links(fetched)) {
            if (sameHostOnly && !sameHost(link, seed)) {
                continue;
            }
            // Dedup on the canonical form, so ?a=1#frag and ?a=1 are one page.
            if (seen.add(canonical(link))) {
                next.add(link);
            }
        }
    }

    /** Same registrable host, or a subdomain of the seed's — {@code docs.x.com} counts
     *  as part of a crawl seeded at {@code x.com}, which is what a caller means by
     *  "this site". */
    private static boolean sameHost(URI candidate, URI seed) {
        var a = host(candidate);
        var b = host(seed);
        return a.equals(b) || a.endsWith("." + b) || b.endsWith("." + a);
    }

    private static String host(URI uri) {
        var h = uri.getHost();
        return h == null ? "" : h.toLowerCase(Locale.ROOT);
    }

    /** Dedup key: scheme and host lowercased, fragment dropped. Path and query are kept
     *  verbatim — a trailing slash or a query parameter can select a different page, and
     *  normalising those away merges pages that are not the same. */
    private static String canonical(URI uri) {
        var path = uri.getPath() == null ? "" : uri.getPath();
        var query = uri.getQuery() == null ? "" : "?" + uri.getQuery();
        return "%s://%s%s%s".formatted(
                uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase(Locale.ROOT),
                host(uri), path, query);
    }

    private static int configConcurrency() {
        return Math.clamp(
                services.ConfigService.getInt(CFG_CONCURRENCY, DEFAULT_CONCURRENCY),
                1, MAX_CONCURRENCY);
    }

    private static String reason(Exception e) {
        var m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    private static String render(URI seed, List<Page> pages, List<Refusal> refused,
                                 int maxDepth, boolean sameHostOnly,
                                 String stoppedBecause, int unvisited) {
        var sb = new StringBuilder();
        sb.append("Scraped %d page%s from %s (depth \u2264 %d, %s)\n"
                .formatted(pages.size(), pages.size() == 1 ? "" : "s", seed,
                        maxDepth, sameHostOnly ? "same host only" : "any host"));
        if (!refused.isEmpty()) {
            // Named, not merely counted: an operator debugging an allowlist needs to
            // know which host was declined and why.
            sb.append("Refused %d link%s:\n".formatted(
                    refused.size(), refused.size() == 1 ? "" : "s"));
            for (var r : refused) {
                sb.append("  - %s \u2014 %s\n".formatted(r.url(), r.why()));
            }
        }
        if (unvisited > 0) {
            // Say what was left behind rather than let the result read as complete.
            sb.append("Stopped: %s \u2014 %d discovered page%s not read.\n"
                    .formatted(stoppedBecause, unvisited, unvisited == 1 ? "" : "s"));
        }
        for (var p : pages) {
            sb.append("\n\n---\n\n## ").append(p.url()).append("\n\n").append(p.text());
        }
        if (sb.length() > MAX_TOTAL_CHARS) {
            return sb.substring(0, MAX_TOTAL_CHARS)
                    + "\n\n[Truncated: scraped content exceeds %d characters]"
                            .formatted(MAX_TOTAL_CHARS);
        }
        return sb.toString();
    }

    private static int configMaxPages() {
        return (int) PlayConfig.longOr(CFG_MAX_PAGES, DEFAULT_MAX_PAGES);
    }

    private static int configMaxDepth() {
        return (int) PlayConfig.longOr(CFG_MAX_DEPTH, DEFAULT_MAX_DEPTH);
    }

    private static int configTimeoutSeconds() {
        return (int) PlayConfig.longOr(CFG_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS);
    }

    /** Default-on. An operator turning this off is making a deliberate choice about
     *  someone else's site, so it has to be a config edit rather than a tool argument
     *  the model can set. */
    private static boolean respectRobots() {
        return !"false".equalsIgnoreCase(
                services.ConfigService.get(CFG_RESPECT_ROBOTS, "true").strip());
    }
}
