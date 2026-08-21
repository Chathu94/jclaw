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
import tools.scrape.ScrapeLadder;
import tools.scrape.SitemapSeeder;
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
import java.util.concurrent.ExecutorService;
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
    private static final String ARG_RESPECT_ROBOTS = "respectRobots";

    private static final String CFG_MAX_PAGES = "web_scrape.max-pages";
    private static final String CFG_MAX_DEPTH = "web_scrape.max-depth";
    private static final String CFG_TIMEOUT_SECONDS = "web_scrape.timeout-seconds";
    private static final String CFG_RESPECT_ROBOTS = "web_scrape.respect-robots";
    private static final String CFG_CONCURRENCY = "web_scrape.concurrency";
    private static final String CFG_MAX_ESCALATIONS = "web_scrape.max-escalations";
    private static final String CFG_SEED_FROM_SITEMAP = "web_scrape.seed-from-sitemap";

    /** Escalated pages allowed per crawl. Deliberately well below max-pages: a rung-3
     *  render costs seconds where a plain fetch costs milliseconds, so a crawl that
     *  escalated every page would be unusable. Five buys the pages most likely to
     *  matter — a blocked entry page, a client-rendered section — without turning a
     *  25-page crawl into a multi-minute one. */
    private static final int DEFAULT_MAX_ESCALATIONS = 5;

    private static final String INTERRUPTED = "interrupted";
    private static final String ROBOTS_REFUSAL = "disallowed by robots.txt";

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
                                "Stay on the starting URL's host (default true)"),
                        ARG_RESPECT_ROBOTS, Map.of(SchemaKeys.TYPE, "boolean",
                                SchemaKeys.DESCRIPTION,
                                "Honour the site's robots.txt (default true). Set false ONLY when "
                                + "the user explicitly asks to ignore robots.txt for this request; "
                                + "never choose it yourself to work around a refusal.")
                ),
                SchemaKeys.REQUIRED, List.of(ARG_URL)
        );
    }

    /** Holds no handles between calls and writes nothing to disk. */
    @Override public boolean parallelSafe() { return true; }

    /** {@code servedBy} is the rung that produced this text. PLAIN for the ordinary
     *  case; anything higher means the ladder was climbed for this page. */
    private record Page(String url, String text, ScrapeRung servedBy) {}

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
        // Config supplies the default; the argument overrides it per call. An operator
        // who wants robots ignored everywhere sets the config once, and one who leaves
        // it on can still say "ignore robots for this" in a single request.
        boolean respectRobots = args.has(ARG_RESPECT_ROBOTS)
                ? args.get(ARG_RESPECT_ROBOTS).getAsBoolean()
                : respectRobotsDefault();

        try {
            SsrfGuard.assertSafeScheme(seed);
        } catch (SecurityException e) {
            return "Error: URL rejected by SSRF guard: %s".formatted(e.getMessage());
        }
        return crawl(seed, maxPages, maxDepth, sameHostOnly, respectRobots);
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
        boolean respect = respectRobotsDefault();
        if (respect && !RobotsCache.isAllowed(uri, CLIENT, IDENTITY)) {
            return ScrapeObservation.failed(url, ROBOTS_REFUSAL);
        }
        try {
            RobotsCache.awaitSlot(uri, respect
                    ? RobotsCache.delayMillis(uri, CLIENT, IDENTITY)
                    : RobotsCache.DEFAULT_DELAY_MS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return ScrapeObservation.failed(url, INTERRUPTED);
        }
        try {
            var fetched = WebExtraction.fetch(url, CLIENT, HEADERS);
            return ScrapeObservation.of(fetched, WebExtraction.toText(fetched));
        } catch (Exception e) {
            return ScrapeObservation.failed(url, reason(e));
        }
    }

    /** Accumulators for one crawl. A small mutable carrier so the passes below can be
     *  separate methods without threading six out-parameters through each of them. */
    private static final class CrawlState {
        final List<Page> pages = new ArrayList<>();
        final List<Refusal> refused = new ArrayList<>();
        final LinkedHashSet<String> seen = new LinkedHashSet<>();
        int totalChars;
        int unvisited;
        String stoppedBecause;
        /** Remaining escalation budget, and what refusing it cost — reported rather
         *  than silently dropped, so a thin result is never mistaken for a blocked one. */
        int escalationsLeft = maxEscalations();
        int escalationsUsed;
        int escalationsSuppressed;
    }

    private String crawl(URI seed, int maxPages, int maxDepth, boolean sameHostOnly,
                         boolean respectRobots) {
        long deadline = System.nanoTime()
                + Duration.ofSeconds(configTimeoutSeconds()).toNanos();
        var state = new CrawlState();
        state.seen.add(canonical(seed));
        var level = List.of(seed);
        int depth = 0;

        try (var pool = Executors.newFixedThreadPool(configConcurrency())) {
            while (!level.isEmpty()) {
                var admitted = withinBudget(admit(level, respectRobots, state), maxPages, state);
                if (admitted.isEmpty()) {
                    break;
                }
                var fetched = fetchLevel(pool, admitted, respectRobots, state);
                if (state.stoppedBecause != null || exhausted(deadline, state)
                        || depth >= maxDepth) {
                    break;
                }
                level = nextLevel(fetched, seed, sameHostOnly, state.seen);
                if (depth == 0) {
                    level = withSitemapSeeds(level, seed, sameHostOnly, respectRobots, state);
                }
                depth++;
            }
        }
        return render(seed, state, maxDepth, sameHostOnly);
    }

    /**
     * Drop the URLs this crawl will not visit, recording why.
     *
     * <p>Runs single-threaded before any work is submitted. Both checks are cheap, and
     * rejecting here keeps {@code seen}, the refusal list and the page budget free of
     * synchronisation.
     */
    private static List<URI> admit(List<URI> level, boolean respectRobots, CrawlState state) {
        var admitted = new ArrayList<URI>();
        for (var uri : level) {
            try {
                SsrfGuard.assertSafeScheme(uri);
            } catch (SecurityException e) {
                state.refused.add(new Refusal(uri.toString(), e.getMessage()));
                continue;
            }
            if (respectRobots && !RobotsCache.isAllowed(uri, CLIENT, IDENTITY)) {
                state.refused.add(new Refusal(uri.toString(), ROBOTS_REFUSAL));
                continue;
            }
            admitted.add(uri);
        }
        return admitted;
    }

    /** Slice a level to the remaining page budget before anything is submitted, so the
     *  page count stays exact without workers racing a shared counter. */
    private static List<URI> withinBudget(List<URI> admitted, int maxPages, CrawlState state) {
        int room = maxPages - state.pages.size();
        if (room <= 0) {
            state.stoppedBecause = budgetReached(maxPages);
            state.unvisited += admitted.size();
            return List.of();
        }
        if (admitted.size() > room) {
            // Truncating a level to the remaining budget IS the stop reason. Leaving it
            // unset reported "Stopped: null" beside a non-zero unread count.
            state.unvisited += admitted.size() - room;
            state.stoppedBecause = budgetReached(maxPages);
            return List.copyOf(admitted.subList(0, room));
        }
        return admitted;
    }

    private static String budgetReached(int maxPages) {
        return "page budget (%d) reached".formatted(maxPages);
    }

    /**
     * Fetch a whole level concurrently and record each outcome.
     *
     * <p>Results are collected in submission order, never completion order: the
     * JCLAW-1091 harness compares runs, and a result whose page order varies per run is
     * not comparable.
     */
    private List<WebExtraction.FetchResult> fetchLevel(ExecutorService pool, List<URI> admitted,
                                                       boolean respectRobots, CrawlState state) {
        var futures = admitted.stream()
                .map(uri -> pool.submit(() -> fetchOne(uri, respectRobots, state)))
                .toList();
        var fetched = new ArrayList<WebExtraction.FetchResult>();
        for (int i = 0; i < futures.size(); i++) {
            var uri = admitted.get(i);
            try {
                recordOutcome(futures.get(i).get(), uri, state, fetched);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                state.stoppedBecause = INTERRUPTED;
                return fetched;
            } catch (ExecutionException e) {
                state.pages.add(new Page(uri.toString(),
                        "[Not retrieved \u2014 %s]".formatted(reason(e)), ScrapeRung.PLAIN));
            }
        }
        return fetched;
    }

    /** Name the reason and the rung that would address it rather than reporting a bare
     *  failure. An agent reading "TURNSTILE" knows not to retry; "[Could not fetch]"
     *  invites a retry loop. */
    private static void recordOutcome(Outcome outcome, URI uri, CrawlState state,
                                      List<WebExtraction.FetchResult> fetched) {
        if (!outcome.usable()) {
            state.pages.add(new Page(uri.toString(), "[Not retrieved \u2014 %s%s%s]"
                    .formatted(outcome.reason(),
                            outcome.detail() == null ? "" : ": " + outcome.detail(),
                            outcome.nextRung() == ScrapeRung.NONE ? ""
                                    : "; needs " + outcome.nextRung()), outcome.servedBy()));
            return;
        }
        state.pages.add(new Page(outcome.fetched().finalUrl(), outcome.text(),
                outcome.servedBy()));
        state.totalChars += outcome.text().length();
        fetched.add(outcome.fetched());
    }

    /** True when the time or content budget is spent; records which one. */
    private boolean exhausted(long deadline, CrawlState state) {
        if (System.nanoTime() > deadline) {
            state.stoppedBecause = "time budget (%ds) reached".formatted(configTimeoutSeconds());
            return true;
        }
        if (state.totalChars >= MAX_TOTAL_CHARS) {
            state.stoppedBecause =
                    "content budget (%d characters) reached".formatted(MAX_TOTAL_CHARS);
            return true;
        }
        return false;
    }

    private static List<URI> nextLevel(List<WebExtraction.FetchResult> fetched, URI seed,
                                       boolean sameHostOnly, LinkedHashSet<String> seen) {
        var next = new ArrayList<URI>();
        for (var f : fetched) {
            collectLinks(f, seed, sameHostOnly, seen, next);
        }
        return next;
    }

    /**
     * Merge the host's sitemap URLs into the frontier (JCLAW-1092).
     *
     * <p><b>Seeded URLs are depth 1, not depth 0.</b> The ticket asked for this to be
     * decided and recorded. Depth 0 means "the page you asked for" — a caller passing
     * {@code maxDepth=0} gets one page, and letting a sitemap add twenty-five more would
     * silently redefine that contract for every existing caller. Seeding is discovery,
     * {@code maxDepth} is what bounds discovery, so a depth-0 crawl correctly does no
     * seeding at all. Seeded URLs then compete with harvested links on equal terms, which
     * is right: both are one step of discovery from the seed.
     *
     * <p><b>Skipped when {@code respectRobots} is off.</b> The epic separates discovery
     * from politeness, so seeding regardless looked defensible — a {@code Sitemap:} line
     * is a publishing hint, not a restriction. {@code RobotsCacheTest} decided otherwise:
     * {@code turningOffRespectRobotsIgnoresTheRulesButStillPaces} asserts robots.txt is
     * not fetched at all when its rules are ignored. Mining that file for hints while
     * declaring we ignore it would break a tested contract to save a caller nothing, and
     * an operator who turned robots off wants fewer requests, not an extra one.
     */
    private List<URI> withSitemapSeeds(List<URI> harvested, URI seed, boolean sameHostOnly,
                                       boolean respectRobots, CrawlState state) {
        if (!respectRobots || !seedFromSitemapDefault()) return harvested;
        var seeds = SitemapSeeder.seedsFor(seed, CLIENT, IDENTITY);
        if (seeds.isEmpty()) return harvested;

        // Harvested links first: a page the site links to from its entry point is a
        // better guess at what a caller wants than an arbitrary sitemap row, and the
        // page budget cuts from the end.
        var merged = new ArrayList<>(harvested);
        for (var uri : seeds) {
            // The crawl's own rules, not the seeder's: sameHost accepts subdomains both
            // ways, so a sitemap on www.example.com still belongs to a crawl seeded at
            // example.com. An exact match here dropped every such seed silently.
            if (sameHostOnly && !sameHost(uri, seed)) continue;
            if (state.seen.add(canonical(uri))) merged.add(uri);
        }
        return merged;
    }

    /** One page's work, as it runs on the pool. Pacing happens here so the wait for a
     *  host's next slot overlaps other hosts' fetches instead of blocking the crawl. */
    private record Outcome(WebExtraction.FetchResult fetched, String text,
                           ScrapeReason reason, ScrapeRung nextRung, String detail,
                           ScrapeRung servedBy) {
        boolean usable() {
            return reason == ScrapeReason.OK;
        }
    }

    private Outcome fetchOne(URI uri, boolean respectRobots, CrawlState state) {
        try {
            RobotsCache.awaitSlot(uri, respectRobots
                    ? RobotsCache.delayMillis(uri, CLIENT, IDENTITY)
                    : RobotsCache.DEFAULT_DELAY_MS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return classified(uri, null, ScrapeObservation.failed(uri.toString(), INTERRUPTED));
        }
        Outcome plain;
        try {
            var fetched = WebExtraction.fetch(uri.toString(), CLIENT, HEADERS);
            var text = WebExtraction.toText(fetched);
            plain = classified(uri, fetched, ScrapeObservation.of(fetched, text));
        } catch (Exception e) {
            // One unreachable page must not end the crawl — the caller asked for a
            // site, and a broken link on it is the site's problem, not the run's.
            plain = classified(uri, null, ScrapeObservation.failed(uri.toString(), reason(e)));
        }
        return escalate(uri, plain, state);
    }

    /**
     * Climb the ladder for a page rung 1 could not read, if the crawl's escalation
     * budget allows.
     *
     * <p>The budget is claimed before the attempt and never refunded on failure: a rung
     * that failed still spent the seconds, and refunding would let one pathological host
     * consume the whole crawl one retry at a time.
     */
    private Outcome escalate(URI uri, Outcome plain, CrawlState state) {
        if (plain.usable() || !ScrapeLadder.available()) return plain;
        synchronized (state) {
            if (state.escalationsLeft <= 0) {
                state.escalationsSuppressed++;
                return plain;
            }
            state.escalationsLeft--;
            state.escalationsUsed++;
        }
        var best = ScrapeLadder.climb(uri.toString(),
                new ScrapeLadder.Attempt(ScrapeRung.PLAIN, plain.fetched(), plain.text(),
                        plain.reason(), plain.detail()));
        if (best.servedBy() == ScrapeRung.PLAIN) return plain;
        EventLogger.info("scrape", "%s: served by %s after %s at PLAIN"
                .formatted(uri, best.servedBy(), plain.reason()), null);
        return new Outcome(best.fetched(), best.text(), best.reason(),
                BlockClassifier.nextRung(best.reason(), best.servedBy()),
                best.detail(), best.servedBy());
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
        return new Outcome(fetched, obs.extractedText(), reason, next, obs.error(),
                ScrapeRung.PLAIN);
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

    private static String render(URI seed, CrawlState state, int maxDepth,
                                 boolean sameHostOnly) {
        var pages = state.pages;
        var refused = state.refused;
        var stoppedBecause = state.stoppedBecause;
        int unvisited = state.unvisited;
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
        if (state.escalationsUsed > 0 || state.escalationsSuppressed > 0) {
            sb.append("Escalated %d page%s beyond the plain fetch".formatted(
                    state.escalationsUsed, state.escalationsUsed == 1 ? "" : "s"));
            if (state.escalationsSuppressed > 0) {
                // Never a silent truncation: a caller reading thin text needs to know
                // whether the page resisted or whether we simply stopped trying.
                sb.append("; %d more could have been but the escalation budget (%d) was spent"
                        .formatted(state.escalationsSuppressed, maxEscalations()));
            }
            sb.append(".\n");
        }
        if (unvisited > 0) {
            // Say what was left behind rather than let the result read as complete.
            sb.append("Stopped: %s \u2014 %d discovered page%s not read.\n"
                    .formatted(stoppedBecause, unvisited, unvisited == 1 ? "" : "s"));
        }
        for (var p : pages) {
            sb.append("\n\n---\n\n## ").append(p.url());
            if (p.servedBy() != ScrapeRung.PLAIN) {
                sb.append(" _(via ").append(p.servedBy()).append(")_");
            }
            sb.append("\n\n").append(p.text());
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

    /** Runtime config, matching web_scrape.concurrency and .respect-robots. JCLAW-1099
     *  described this as operator-tunable but read it from application.conf, which needs
     *  a restart to change — not tunable in the sense the ticket meant. */
    private static int maxEscalations() {
        return services.ConfigService.getInt(CFG_MAX_ESCALATIONS, DEFAULT_MAX_ESCALATIONS);
    }

    private static int configTimeoutSeconds() {
        return (int) PlayConfig.longOr(CFG_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * Default for the {@code respectRobots} argument when a call omits it.
     *
     * <p>Default-on. Ignoring a site's robots.txt is a deliberate choice about someone
     * else's server, so it is opt-out per call rather than something that happens by
     * omission — and an operator who wants it off everywhere sets this key once.
     *
     * <p>Note what the override does <em>not</em> change: per-host pacing stays on
     * either way. "Ignore this site's directives" and "hammer this site" are different
     * requests, and only the first is available.
     */
    private static boolean seedFromSitemapDefault() {
        return !"false".equalsIgnoreCase(
                services.ConfigService.get(CFG_SEED_FROM_SITEMAP, "true").strip());
    }

    private static boolean respectRobotsDefault() {
        return !"false".equalsIgnoreCase(
                services.ConfigService.get(CFG_RESPECT_ROBOTS, "true").strip());
    }
}
