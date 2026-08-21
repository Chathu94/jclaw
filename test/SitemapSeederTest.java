import models.Agent;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;
import tools.WebScrapeTool;
import utils.RobotsCache;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Seeding the crawl frontier from sitemaps (JCLAW-1092).
 *
 * <p>Uses a host of its own, like {@code RobotsCacheTest} and for the same reason:
 * {@link RobotsCache} is a process-global cache and play1 runs test classes concurrently,
 * so a shared host would make these assertions depend on which class warmed the cache.
 *
 * <p>Every page here is link-free on purpose. If a page linked anywhere, a passing
 * assertion could not distinguish "the sitemap seeded it" from "link harvesting found it",
 * which is the only thing this suite exists to prove.
 */
class SitemapSeederTest extends UnitTest {

    private static final Field CLIENT_FIELD;
    static {
        try {
            CLIENT_FIELD = WebScrapeTool.class.getDeclaredField("CLIENT");
            CLIENT_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    private static final String HOST = "https://sitemap.test";
    private static final String OTHER = "https://elsewhere.test";
    private static final String CFG_RESPECT = "web_scrape.respect-robots";
    private static final String CFG_MAX_URLS = "web_scrape.max-sitemap-urls";

    private RouteInterceptor routes;
    private OkHttpClient original;
    private String originalRespect;

    @BeforeEach
    void setup() throws Exception {
        RobotsCache.resetForTest();
        routes = new RouteInterceptor();
        original = (OkHttpClient) CLIENT_FIELD.get(null);
        originalRespect = ConfigService.get(CFG_RESPECT, "true");
        CLIENT_FIELD.set(null, new OkHttpClient.Builder()
                .addInterceptor(routes).callTimeout(5, TimeUnit.SECONDS).build());
    }

    @AfterEach
    void teardown() throws Exception {
        CLIENT_FIELD.set(null, original);
        ConfigService.set(CFG_RESPECT, originalRespect);
        ConfigService.set(CFG_MAX_URLS, "50");
        ConfigService.clearCache();
        RobotsCache.resetForTest();
    }

    /** A page with no links at all — so anything else that appears came from the sitemap. */
    private static String page(String title) {
        return "<html><head><title>" + title + "</title></head><body><article><p>"
                + "Body text long enough to clear the readability floor. ".repeat(12)
                + "</p></article></body></html>";
    }

    private static String urlset(String... locs) {
        var sb = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");
        for (var loc : locs) sb.append("<url><loc>").append(loc).append("</loc></url>");
        return sb.append("</urlset>").toString();
    }

    private static String sitemapIndex(String... locs) {
        var sb = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");
        for (var loc : locs) sb.append("<sitemap><loc>").append(loc).append("</loc></sitemap>");
        return sb.append("</sitemapindex>").toString();
    }

    private void robots(String extra) {
        routes.put(HOST + "/robots.txt", "User-agent: *\nAllow: /\n" + extra, "text/plain");
    }

    private String scrape(String json) {
        return new WebScrapeTool().execute(json, (Agent) null);
    }

    // ==================== Discovery ====================

    @Test
    void aSitemapSeedsPagesNothingLinksTo() {
        robots("Sitemap: " + HOST + "/sitemap.xml\n");
        routes.put(HOST + "/sitemap.xml", urlset(HOST + "/orphan-a", HOST + "/orphan-b"), "application/xml");
        routes.put(HOST + "/", page("Home"));
        routes.put(HOST + "/orphan-a", page("Orphan A"));
        routes.put(HOST + "/orphan-b", page("Orphan B"));

        var out = scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":1}");
        assertTrue(out.contains("# Orphan A"), "sitemap URL was not crawled: " + out.substring(0, 300));
        assertTrue(out.contains("# Orphan B"), "second sitemap URL was not crawled");
    }

    @Test
    void aSitemapIndexIsExpanded() {
        robots("Sitemap: " + HOST + "/sitemap-index.xml\n");
        routes.put(HOST + "/sitemap-index.xml", sitemapIndex(HOST + "/child.xml"), "application/xml");
        routes.put(HOST + "/child.xml", urlset(HOST + "/deep"), "application/xml");
        routes.put(HOST + "/", page("Home"));
        routes.put(HOST + "/deep", page("Deep"));

        assertTrue(scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":1}").contains("# Deep"),
                "a sitemap index must be followed to its children");
    }

    @Test
    void noSitemapDirectiveIsNotAnError() {
        robots("");
        routes.put(HOST + "/", page("Home"));
        assertTrue(scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":1}").contains("# Home"),
                "the common case — no Sitemap: line — must crawl exactly as before");
    }

    @Test
    void aMalformedSitemapDegradesToNoSeeds() {
        // Seeding is an enhancement to a crawl that already works; a broken file on
        // someone else's server must not break the caller's crawl.
        robots("Sitemap: " + HOST + "/sitemap.xml\n");
        routes.put(HOST + "/sitemap.xml", "this is not xml at all", "application/xml");
        routes.put(HOST + "/", page("Home"));
        assertTrue(scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":1}").contains("# Home"));
    }

    // ==================== Bounds and scoping ====================

    @Test
    void offHostSitemapEntriesAreDroppedUnderSameHostOnly() {
        robots("Sitemap: " + HOST + "/sitemap.xml\n");
        routes.put(HOST + "/sitemap.xml", urlset(OTHER + "/elsewhere", HOST + "/here"), "application/xml");
        routes.put(HOST + "/", page("Home"));
        routes.put(HOST + "/here", page("Here"));

        var out = scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":1,\"sameHostOnly\":true}");
        assertTrue(out.contains("# Here"));
        assertFalse(routes.hits.stream().anyMatch(u -> u.startsWith(OTHER)),
                "an off-host sitemap entry must not be fetched: " + routes.hits);
    }

    @Test
    void theUrlCapBoundsWhatASitemapCanInject() {
        // A real sitemap can carry tens of thousands of rows against a page budget of 25.
        ConfigService.set(CFG_MAX_URLS, "1");
        ConfigService.clearCache();
        robots("Sitemap: " + HOST + "/sitemap.xml\n");
        routes.put(HOST + "/sitemap.xml",
                urlset(HOST + "/one", HOST + "/two", HOST + "/three"), "application/xml");
        routes.put(HOST + "/", page("Home"));
        for (var p : List.of("one", "two", "three")) routes.put(HOST + "/" + p, page(p));

        scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":1}");
        long seeded = routes.hits.stream()
                .filter(u -> u.endsWith("/one") || u.endsWith("/two") || u.endsWith("/three")).count();
        assertEquals(1, seeded, "the cap must bound seeded URLs, got: " + routes.hits);
    }

    // ==================== The two recorded decisions ====================

    @Test
    void depthZeroDoesNoSeeding() {
        // Recorded decision: seeded URLs are depth 1. maxDepth=0 means "the page you asked
        // for", and letting a sitemap add more would redefine that for every caller.
        robots("Sitemap: " + HOST + "/sitemap.xml\n");
        routes.put(HOST + "/sitemap.xml", urlset(HOST + "/orphan"), "application/xml");
        routes.put(HOST + "/", page("Home"));
        routes.put(HOST + "/orphan", page("Orphan"));

        var out = scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":0}");
        assertTrue(out.contains("# Home"));
        assertFalse(out.contains("# Orphan"), "depth 0 must return exactly the page asked for");
    }

    @Test
    void ignoringRobotsAlsoSkipsSeeding() {
        // RobotsCacheTest asserts robots.txt is not fetched when its rules are ignored.
        // Mining it for Sitemap: hints anyway would break that contract to save nobody
        // anything, so seeding turns off with robots compliance.
        ConfigService.set(CFG_RESPECT, "false");
        ConfigService.clearCache();
        routes.put(HOST + "/", page("Home"));

        scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":1}");
        assertFalse(routes.hits.stream().anyMatch(u -> u.endsWith("/robots.txt")),
                "robots.txt must not be fetched when its rules are ignored: " + routes.hits);
    }

    // ==================== Helper ====================

    static final class RouteInterceptor implements Interceptor {
        private final Map<String, String> bodies = new HashMap<>();
        private final Map<String, String> types = new HashMap<>();
        final List<String> hits = new ArrayList<>();

        void put(String url, String body) { put(url, body, "text/html; charset=utf-8"); }

        void put(String url, String body, String contentType) {
            bodies.put(url, body);
            types.put(url, contentType);
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            var url = chain.request().url().toString();
            hits.add(url);
            var body = bodies.get(url);
            if (body == null) throw new IOException("no route for " + url);
            var type = types.get(url);
            return new Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .addHeader("Content-Type", type)
                    .body(ResponseBody.create(body, MediaType.parse(type)))
                    .build();
        }
    }
}
