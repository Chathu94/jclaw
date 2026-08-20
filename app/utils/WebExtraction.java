package utils;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.vladsch.flexmark.util.data.MutableDataSet;
import net.dankito.readability4j.Readability4J;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.ResponseBody;
import org.apache.tika.Tika;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.jsoup.Jsoup;
import services.ConfigService;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SSRF-guarded fetch plus readable-content extraction, shared by {@code web_fetch}
 * and {@code web_scrape} (JCLAW-1082).
 *
 * <p>Extracted from {@code WebFetchTool} rather than duplicated: a crawl runs this
 * chain once per page, and two copies would drift on exactly the details that took
 * bug reports to get right — the manual redirect loop, the byte-bounded read, the
 * Readability-then-Jsoup fallback.
 *
 * <p>The OkHttp client is a parameter, not a static here. {@code WebFetchTool} owns
 * its guarded client as the seam its tests substitute, and passing it in keeps that
 * seam working while making the dependency explicit for other callers.
 */
public final class WebExtraction {

    private WebExtraction() {}

    public static final int MAX_TEXT_LENGTH = 50_000;
    public static final int MAX_REDIRECTS = 5;

    /** Below this many extracted characters the Readability pass is treated as a
     *  miss and the Jsoup boilerplate-strip fallback runs instead — small pages
     *  and non-article fragments aren't article-shaped enough to score well. */
    private static final int MIN_READABILITY_CHARS = 200;

    /** Cap on the raw response bytes buffered into the heap per fetch. The body
     *  comes from an untrusted, LLM-supplied URL and the {@link SsrfGuard} client
     *  sets no read/body limit, so a large or slow response — multiplied across
     *  the parallel virtual-thread fetches — could OOM the shared JVM. 10 MiB by
     *  default: comfortably above a typical article PDF, yet small enough that
     *  many concurrent fetches can't exhaust the heap. */
    private static final long DEFAULT_MAX_BODY_BYTES = 10L * 1024 * 1024;
    private static final String CFG_MAX_BODY_BYTES = "web_fetch.max-body-bytes";

    /** Comma-separated outbound host allowlist; see {@link #assertHostAllowed(URI)}. */
    private static final String CFG_ALLOWLIST = "web_fetch.allowlist";

    private static final FlexmarkHtmlConverter HTML_TO_MARKDOWN =
            FlexmarkHtmlConverter.builder(new MutableDataSet()
                    // Suppress the {#id} inline-attribute annotations flexmark emits
                    // for element ids. Parsoid-rendered HTML (e.g. Wikipedia) tags
                    // nearly every node with an id, which is pure noise in LLM-facing
                    // markdown.
                    .set(FlexmarkHtmlConverter.OUTPUT_ATTRIBUTES_ID, false)).build();

    /** Shared and configured once (never mutated per-call) so {@code parseToString}
     *  stays thread-safe under the parallel tool dispatch. */
    private static final Tika TIKA = new Tika();
    static {
        TIKA.setMaxStringLength(MAX_TEXT_LENGTH + 10_000);
    }

    /** Raw fetch result: undecoded body bytes plus the response Content-Type and
     *  the final (post-redirect) URL. Bytes — not a decoded String — so binary
     *  documents (PDF, Office) reach Tika intact. */
    public record FetchResult(byte[] body, String contentType, String finalUrl) {}

    /** Signals an outbound host the operator's allowlist doesn't cover. A distinct
     *  type from the {@link SecurityException} {@link SsrfGuard} throws, which
     *  reports a different refusal. */
    public static final class HostNotAllowedException extends RuntimeException {
        HostNotAllowedException(String host) {
            super(("Error: host '%s' is not on the operator's web_fetch allowlist (config %s). "
                    + "Ask the operator to add it if this fetch is intended.")
                    .formatted(host, CFG_ALLOWLIST));
        }
    }

    /**
     * Fetch a URL through a {@link SsrfGuard}ed client. Redirects are followed
     * manually, up to {@link #MAX_REDIRECTS}, so each hop is re-validated through
     * {@link SsrfGuard#assertSafeScheme(URI)} and re-resolved through the guarded DNS.
     *
     * <p>Only the final (non-redirect) response body is read, and it is read as raw
     * bytes — never a decoded String — so binary documents survive intact for Tika.
     * The read is size-bounded through {@link #readBounded}.
     *
     * @param headers request headers, so a caller can present a different client
     *                identity without forking this loop
     */
    public static FetchResult fetch(String url, OkHttpClient client, Map<String, String> headers)
            throws IOException {
        var current = URI.create(url);
        SsrfGuard.assertSafeScheme(current);
        assertHostAllowed(current);

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            var builder = new Request.Builder().url(current.toString()).get();
            headers.forEach(builder::header);

            try (var response = client.newCall(builder.build()).execute()) {
                int code = response.code();

                // Follow 3xx manually so every hop re-enters SsrfGuard.
                if (code >= 300 && code < 400) {
                    var location = response.header("Location");
                    if (location == null || location.isBlank()) {
                        throw new IOException(
                                "HTTP %d with no Location header for %s".formatted(code, current));
                    }
                    current = current.resolve(location);
                    SsrfGuard.assertSafeScheme(current);
                    assertHostAllowed(current);
                    continue;
                }

                if (code >= 400) {
                    throw new IOException("HTTP %d fetching %s".formatted(code, current));
                }

                var bytes = readBounded(response.body(), current);
                var contentType = response.header("Content-Type", "");
                return new FetchResult(bytes, contentType, current.toString());
            }
        }
        throw new IOException("Too many redirects (>%d) fetching %s".formatted(MAX_REDIRECTS, url));
    }

    /**
     * Refuse an outbound host that is absent from the operator's allowlist
     * ({@link #CFG_ALLOWLIST}, comma-separated). Unset or blank means no
     * restriction — the shipped default, because deny-by-default would break
     * web_fetch on every existing install. An entry matches that host and any
     * subdomain of it. Enforced on every redirect hop, so a listed host can't
     * bounce the fetch onward to one the operator never listed.
     */
    private static void assertHostAllowed(URI uri) {
        var raw = ConfigService.get(CFG_ALLOWLIST, "").strip();
        if (raw.isEmpty()) {
            return;
        }
        var host = uri.getHost().toLowerCase(Locale.ROOT);
        for (var entry : raw.split(",")) {
            var allowed = entry.strip().toLowerCase(Locale.ROOT);
            if (!allowed.isEmpty() && (host.equals(allowed) || host.endsWith("." + allowed))) {
                return;
            }
        }
        throw new HostNotAllowedException(host);
    }

    /**
     * Buffer at most {@link #maxBodyBytes()} of an untrusted response body into
     * the heap, so a large or slow LLM-supplied URL can't OOM the shared JVM.
     * Two layered guards:
     * <ol>
     *   <li>a declared {@code Content-Length} over the cap is rejected before a
     *       single body byte is read;</li>
     *   <li>the read itself is bounded — okio buffers only {@code cap + 1} bytes
     *       (rounded up to its segment size), so a server that omits or lies
     *       about {@code Content-Length} still can't push more than ~cap onto
     *       the heap.</li>
     * </ol>
     */
    private static byte[] readBounded(ResponseBody body, URI url) throws IOException {
        long cap = maxBodyBytes();
        long declared = body.contentLength();
        if (declared > cap) {
            throw new IOException("Response body too large (%d bytes, limit %d) fetching %s"
                    .formatted(declared, cap, url));
        }
        var source = body.source();
        // request(cap + 1) reads segment by segment only until the buffer holds
        // cap + 1 bytes (or the source is exhausted) — never the whole stream.
        if (source.request(cap + 1)) {
            return source.readByteArray(cap); // more than the cap available → keep the capped prefix
        }
        return source.readByteArray(); // whole body fit under the cap
    }

    /** Per-fetch heap cap for the raw response body ({@link #CFG_MAX_BODY_BYTES},
     *  default {@link #DEFAULT_MAX_BODY_BYTES}). */
    private static long maxBodyBytes() {
        return PlayConfig.longOr(CFG_MAX_BODY_BYTES, DEFAULT_MAX_BODY_BYTES);
    }

    /**
     * Render a fetched response as LLM-facing text, routed by content type:
     * HTML through the Readability pass to Markdown, already-readable formats
     * unchanged, and everything else through Tika.
     */
    public static String toText(FetchResult fetched) {
        var contentType = fetched.contentType();
        var body = fetched.body();

        // 1. HTML → Readability main-content pass → Markdown.
        if (isHtml(contentType, body)) {
            return extractText(new String(body, charsetFor(contentType)), fetched.finalUrl());
        }

        // 2. Textual (JSON / XML / CSV / plain text) → pass through unchanged.
        if (isTextual(contentType) || (contentType.isBlank() && !looksBinary(body))) {
            return truncate(new String(body, charsetFor(contentType)), "content");
        }

        // 3. Binary document (PDF / Office / …) → Tika text extraction.
        return extractWithTika(body, contentType, fetched.finalUrl());
    }

    /**
     * Absolute http(s) links from an HTML response, in document order, deduplicated.
     *
     * <p>Resolved against the response's <em>final</em> URL rather than the requested
     * one, so relative hrefs on a page reached through a redirect resolve against
     * where the page actually came from.
     *
     * <p>Empty for any non-HTML response — a crawl has nothing to follow out of a PDF.
     */
    public static List<URI> links(FetchResult fetched) {
        if (!isHtml(fetched.contentType(), fetched.body())) {
            return List.of();
        }
        var html = new String(fetched.body(), charsetFor(fetched.contentType()));
        var out = new LinkedHashSet<URI>();
        for (var a : Jsoup.parse(html, fetched.finalUrl()).select("a[href]")) {
            var abs = a.attr("abs:href");
            if (abs.isBlank()) {
                continue;
            }
            try {
                var uri = URI.create(abs);
                var scheme = uri.getScheme();
                // mailto:, javascript:, tel: and friends are not crawlable, and
                // SsrfGuard would refuse them one layer down anyway.
                if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                        && uri.getHost() != null) {
                    out.add(uri);
                }
            } catch (IllegalArgumentException _) {
                // Malformed href on someone else's page — skip it, don't fail the crawl.
            }
        }
        return List.copyOf(out);
    }

    /**
     * Extract readable content from HTML and render it as Markdown.
     *
     * <p>A Readability main-content pass runs first; if it finds no substantial
     * article (or throws on malformed input) the original Jsoup boilerplate
     * strip runs as a fallback, so this never returns empty for a page that has
     * body content. The chosen content HTML is converted to Markdown, prefixed
     * with the page title as an H1 when present.
     */
    public static String extractText(String html, String url) {
        String contentHtml = null;
        String title = null;

        // 1. Readability main-content pass.
        try {
            var article = new Readability4J(url, html).parse();
            var articleText = article.getTextContent();
            if (articleText != null && articleText.strip().length() >= MIN_READABILITY_CHARS) {
                contentHtml = article.getContent();
                title = article.getTitle();
            }
        } catch (Exception _) {
            // fall through to the Jsoup boilerplate-strip fallback
        }

        // 2. Fallback: strip non-content elements and keep the body HTML.
        if (contentHtml == null || contentHtml.isBlank()) {
            var doc = Jsoup.parse(html, url);
            doc.select("script, style, noscript, iframe, svg, canvas, nav, footer, " +
                       "header, aside, form, button, input, select, textarea, " +
                       "[role=navigation], [role=banner], [role=complementary], " +
                       "[aria-hidden=true], .hidden, .sr-only, .visually-hidden").remove();
            title = doc.title();
            // jsoup always yields a <body> (creating an empty one if absent), so no null guard is needed.
            contentHtml = doc.body().html();
        }

        // 3. HTML → Markdown.
        var markdown = HTML_TO_MARKDOWN.convert(contentHtml).strip();

        // 4. Assemble with an optional title heading.
        var result = new StringBuilder();
        if (title != null && !title.isBlank()) {
            result.append("# ").append(title.strip()).append("\n\n");
        }
        result.append(markdown);

        if (result.length() > MAX_TEXT_LENGTH) {
            return result.substring(0, MAX_TEXT_LENGTH)
                    + "\n\n[Truncated: extracted text exceeds %d characters]".formatted(MAX_TEXT_LENGTH);
        }
        return result.toString();
    }

    /** Extract text from a non-HTML document (PDF, Office, EPUB, …) with Tika. */
    private static String extractWithTika(byte[] body, String contentType, String url) {
        try {
            var metadata = new Metadata();
            if (!contentType.isBlank()) {
                metadata.set(HttpHeaders.CONTENT_TYPE, contentType);
            }
            // Resource-name hint lets Tika fall back to extension-based detection.
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, url);
            var text = TIKA.parseToString(new ByteArrayInputStream(body), metadata);
            return truncate(text.strip(), "content");
        } catch (Exception e) {
            return "Error: could not extract text from %s: %s".formatted(url, e.getMessage());
        }
    }

    /** True when the response is HTML: an explicit html content type, or — when
     *  the content type is absent — a body whose first non-whitespace char opens
     *  a tag that isn't an XML declaration. */
    private static boolean isHtml(String contentType, byte[] body) {
        if (contentType.toLowerCase().contains("html")) {
            return true;
        }
        if (contentType.isBlank()) {
            var head = new String(body, 0, Math.min(body.length, 256), StandardCharsets.UTF_8).stripLeading();
            return head.startsWith("<") && !head.regionMatches(true, 0, "<?xml", 0, 5);
        }
        return false;
    }

    /** True for content types that are already human-readable and must pass
     *  through untouched (JSON, XML, CSV, plain text, source). */
    private static boolean isTextual(String contentType) {
        if (contentType.isBlank()) {
            return false;
        }
        var ct = contentType.toLowerCase();
        return ct.startsWith("text/")
                || ct.contains("json")
                || ct.contains("xml")
                || ct.contains("csv")
                || ct.contains("javascript")
                || ct.contains("yaml");
    }

    /** Heuristic used only when the content type is absent: magic numbers for
     *  common binary documents, or a NUL byte early in the stream. */
    private static boolean looksBinary(byte[] body) {
        if (body.length == 0) {
            return false;
        }
        if (startsWith(body, "%PDF")) {                                   // PDF
            return true;
        }
        if (body.length >= 4 && body[0] == 'P' && body[1] == 'K'
                && body[2] == 3 && body[3] == 4) {                        // ZIP (docx/xlsx/pptx/odf)
            return true;
        }
        if (body.length >= 2 && (body[0] & 0xFF) == 0xD0 && (body[1] & 0xFF) == 0xCF) {
            return true;                                                  // OLE2 (legacy .doc/.xls/.ppt)
        }
        int n = Math.min(body.length, 512);
        for (int i = 0; i < n; i++) {
            if (body[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWith(byte[] body, String ascii) {
        if (body.length < ascii.length()) {
            return false;
        }
        for (int i = 0; i < ascii.length(); i++) {
            if (body[i] != ascii.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** Parse the charset from a Content-Type header, defaulting to UTF-8 — the
     *  same rule OkHttp's {@code ResponseBody.string()} applied before the
     *  switch to raw bytes. */
    public static Charset charsetFor(String contentType) {
        if (contentType.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        var mediaType = MediaType.parse(contentType);
        return mediaType != null ? mediaType.charset(StandardCharsets.UTF_8) : StandardCharsets.UTF_8;
    }

    private static String truncate(String text, String label) {
        if (text.length() > MAX_TEXT_LENGTH) {
            return text.substring(0, MAX_TEXT_LENGTH)
                    + "\n\n[Truncated: %s exceeds %d characters]".formatted(label, MAX_TEXT_LENGTH);
        }
        return text;
    }
}
