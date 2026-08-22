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
 * What the crawler asks for and what it keeps — markdown preference (JCLAW-1101) and
 * locale de-duplication (JCLAW-1100).
 *
 * <p>Own host, like the sibling scrape suites: {@link RobotsCache} is a process-global
 * cache and play1 runs test classes concurrently.
 */
class ScrapeContentPreferencesTest extends UnitTest {

    private static final Field CLIENT_FIELD;
    static {
        try {
            CLIENT_FIELD = WebScrapeTool.class.getDeclaredField("CLIENT");
            CLIENT_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    private static final String HOST = "https://prefs.test";
    private static final String CFG_LANGUAGE = "web_scrape.language";

    private RouteInterceptor routes;
    private OkHttpClient original;

    @BeforeEach
    void setup() throws Exception {
        RobotsCache.resetForTest();
        routes = new RouteInterceptor();
        original = (OkHttpClient) CLIENT_FIELD.get(null);
        CLIENT_FIELD.set(null, new OkHttpClient.Builder()
                .addInterceptor(routes).callTimeout(5, TimeUnit.SECONDS).build());
        routes.put(HOST + "/robots.txt", "User-agent: *\nAllow: /\n", "text/plain");
    }

    @AfterEach
    void teardown() throws Exception {
        CLIENT_FIELD.set(null, original);
        ConfigService.set(CFG_LANGUAGE, "en");
        ConfigService.clearCache();
        RobotsCache.resetForTest();
    }

    private static String body(String... paragraphs) {
        return String.join("\n\n", paragraphs) + "\n\n"
                + "Body text long enough to clear the readability floor. ".repeat(12);
    }

    private String scrape(String json) {
        return new WebScrapeTool().execute(json, (Agent) null);
    }

    // ==================== JCLAW-1101: markdown ====================

    @Test
    void markdownIsPreferredInTheRequest() {
        routes.put(HOST + "/", "<html><head><title>H</title></head><body><article><p>"
                + "Body text long enough to clear the readability floor. ".repeat(12)
                + "</p></article></body></html>");
        scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":0}");

        var accept = routes.acceptFor(HOST + "/");
        assertNotNull(accept, "no Accept header was sent");
        assertTrue(accept.startsWith("text/markdown"),
                "markdown must be preferred over HTML, got: " + accept);
        assertTrue(accept.contains("text/html"),
                "HTML must stay acceptable — most sites serve nothing else: " + accept);
    }

    @Test
    void markdownIsUsedVerbatimRatherThanRunThroughReadability() {
        // The point of the whole story: when the origin hands us the target format, the
        // HTML reconstruction is skipped rather than applied to markdown.
        routes.put(HOST + "/", body("# Real Heading", "Some **bold** prose."), "text/markdown");
        var out = scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":0}");
        assertTrue(out.contains("**bold**"),
                "markdown emphasis must survive verbatim: " + out.substring(0, Math.min(400, out.length())));
    }

    @Test
    void linksAreHarvestedFromMarkdownSoCrawlingStillWorks() {
        // Without markdown link parsing, preferring markdown would silently reduce every
        // crawl to its seed page.
        routes.put(HOST + "/", body("# Index", "See [the guide](/guide) for more."), "text/markdown");
        routes.put(HOST + "/guide", body("# Guide", "Guide content."), "text/markdown");

        var out = scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":1}");
        assertTrue(out.contains("/guide"), "a markdown link must be followed: " + out);
    }

    @Test
    void anOriginThatIgnoresTheHeaderStillWorks() {
        // Content negotiation is advisory; a server returning HTML anyway must take the
        // ordinary path unchanged.
        routes.put(HOST + "/", "<html><head><title>Plain</title></head><body><article><p>"
                + "Body text long enough to clear the readability floor. ".repeat(12)
                + "</p></article></body></html>");
        assertTrue(scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":0}").contains("# Plain"));
    }

    @Test
    void theRequestStatesALanguagePreferenceWithoutDemandingIt() {
        routes.put(HOST + "/", "<html><head><title>H</title></head><body><article><p>"
                + "Body text long enough to clear the readability floor. ".repeat(12)
                + "</p></article></body></html>");
        scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":0,\"language\":\"ja\"}");

        var lang = routes.languageFor(HOST + "/");
        assertNotNull(lang, "no Accept-Language header was sent");
        assertTrue(lang.startsWith("ja"), "the requested language must lead: " + lang);
        // A bare "Accept-Language: ja" invites a 406 or an empty body from a site with no
        // Japanese. A language preference must never cost us the page.
        assertTrue(lang.contains("*"), "a wildcard fallback must remain acceptable: " + lang);
    }

    @Test
    void theLanguagePreferenceDefaultsToEnglish() {
        routes.put(HOST + "/", "<html><head><title>H</title></head><body><article><p>"
                + "Body text long enough to clear the readability floor. ".repeat(12)
                + "</p></article></body></html>");
        scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":0}");
        assertTrue(routes.languageFor(HOST + "/").startsWith("en"),
                "English by default: " + routes.languageFor(HOST + "/"));
    }

    // ==================== JCLAW-1100: locale variants ====================

    private static String withAlternates(String title, String... langToPath) {
        var links = new StringBuilder();
        for (int i = 0; i < langToPath.length; i += 2) {
            links.append("<link rel=\"alternate\" hreflang=\"").append(langToPath[i])
                 .append("\" href=\"").append(HOST).append(langToPath[i + 1]).append("\"/>");
        }
        return "<html><head><title>" + title + "</title>" + links
                + "</head><body><article><p>"
                + "Body text long enough to clear the readability floor. ".repeat(12)
                + "</p><a href=\"" + HOST + "/de\">de</a><a href=\"" + HOST + "/ja\">ja</a>"
                + "<a href=\"" + HOST + "/fr\">fr</a>"
                + "</article></body></html>";
    }

    @Test
    void translationsOfAPageDoNotSpendThePageBudget() {
        routes.put(HOST + "/", withAlternates("Home", "en", "/", "de", "/de", "ja", "/ja"));
        routes.put(HOST + "/de", withAlternates("Startseite", "en", "/", "de", "/de"));
        routes.put(HOST + "/ja", withAlternates("Home JA", "en", "/", "ja", "/ja"));

        var out = scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":1}");
        assertFalse(routes.hits.contains(HOST + "/de"), "a declared translation must not be fetched");
        assertFalse(routes.hits.contains(HOST + "/ja"), "a declared translation must not be fetched");
        assertTrue(out.contains("Dropped 2 locale variants"),
                "the caller must be told what was dropped: " + out.substring(0, Math.min(400, out.length())));
    }

    @Test
    void theLanguageArgumentSelectsWhichTranslationIsKept() {
        routes.put(HOST + "/", withAlternates("Home", "en", "/", "de", "/de"));
        routes.put(HOST + "/de", withAlternates("Startseite", "en", "/", "de", "/de"));

        scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":1,\"language\":\"de\"}");
        assertTrue(routes.hits.contains(HOST + "/de"),
                "asking for German must keep the German variant: " + routes.hits);
    }

    @Test
    void aSiteWithNoPreferredVariantKeepsOneRatherThanNone() {
        // A language filter that empties the frontier is worse than no filter.
        routes.put(HOST + "/", withAlternates("Startseite", "de", "/de", "fr", "/fr"));
        routes.put(HOST + "/de", withAlternates("DE", "de", "/de"));
        routes.put(HOST + "/fr", withAlternates("FR", "fr", "/fr"));

        scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":1}");
        long kept = routes.hits.stream().filter(u -> u.endsWith("/de") || u.endsWith("/fr")).count();
        assertEquals(1, kept, "exactly one variant survives when none matches: " + routes.hits);
    }

    // ==================== Helper ====================

    static final class RouteInterceptor implements Interceptor {
        private final Map<String, String> bodies = new HashMap<>();
        private final Map<String, String> types = new HashMap<>();
        private final Map<String, String> accepts = new HashMap<>();
        private final Map<String, String> languages = new HashMap<>();
        final List<String> hits = new ArrayList<>();

        void put(String url, String b) { put(url, b, "text/html; charset=utf-8"); }

        void put(String url, String b, String contentType) {
            bodies.put(url, b);
            types.put(url, contentType);
        }

        String acceptFor(String url) { return accepts.get(url); }

        String languageFor(String url) { return languages.get(url); }

        @Override
        public Response intercept(Chain chain) throws IOException {
            var url = chain.request().url().toString();
            hits.add(url);
            accepts.put(url, chain.request().header("Accept"));
            languages.put(url, chain.request().header("Accept-Language"));
            var b = bodies.get(url);
            if (b == null) throw new IOException("no route for " + url);
            var type = types.get(url);
            return new Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .addHeader("Content-Type", type)
                    .body(ResponseBody.create(b, MediaType.parse(type)))
                    .build();
        }
    }
}
