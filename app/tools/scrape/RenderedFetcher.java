package tools.scrape;

import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import services.StealthSidecarManager;
import services.scrape.ScrapeSidecarException;
import utils.HttpFactories;
import utils.HttpKeys;
import utils.SsrfGuard;
import utils.WebExtraction;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Rung 3: render through the stealth browser sidecar (JCLAW-1088).
 *
 * <p>Not a {@link WebExtraction.Transport}. Rungs 1 and 2 hand each redirect back so the
 * JVM can re-validate it; a browser follows redirects internally and cannot be asked to
 * stop. Containment therefore moves rather than disappearing, and it is layered exactly
 * as {@code PlaywrightBrowserTool} layers it in-JVM (JCLAW-731):
 *
 * <ol>
 *   <li>the entry URL is validated here and pinned to the address {@link SsrfGuard}
 *       actually resolved, closing the rebinding window between our lookup and the
 *       browser's;</li>
 *   <li>the sidecar's route interceptor range-checks every further host the page
 *       reaches — redirects and subresources both — and aborts the non-public ones,
 *       reporting them back in {@code X-Blocked-Hosts}.</li>
 * </ol>
 */
public final class RenderedFetcher {

    private static final MediaType JSON = MediaType.get(HttpKeys.APPLICATION_JSON);

    /** A render is slow by nature: navigation, then a settle window for a challenge to
     *  resolve itself. Well above the sidecar's own per-render timeout so reaching this
     *  means the sidecar is wedged, not that the page was slow. */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(120);

    private static final OkHttpClient CLIENT = HttpFactories.general().newBuilder()
            .callTimeout(CALL_TIMEOUT)
            .build();

    private RenderedFetcher() {}

    public static boolean available() {
        return StealthSidecarManager.available();
    }

    /** Render {@code url} and return it in the same shape the other rungs produce. */
    public static WebExtraction.FetchResult fetch(String url) throws IOException {
        // Authoritative check stays in the JVM. hostResolverRule throws every
        // SecurityException assertUrlSafe does, so an unsafe entry URL never reaches
        // the browser.
        var pinRule = SsrfGuard.hostResolverRule(url);
        var baseUrl = StealthSidecarManager.ensureRunning();

        var payload = new JsonObject();
        payload.addProperty("url", url);
        var pins = new JsonObject();
        // "MAP <host> <ip>" — the sidecar rebuilds the flag, so the JVM never has to
        // know Chromium's argument syntax and the guard never has to emit it.
        pinRule.ifPresent(rule -> {
            var parts = rule.split(" ");
            if (parts.length == 3) pins.addProperty(parts[1], parts[2]);
        });
        payload.add("pins", pins);

        var request = new Request.Builder()
                .url(baseUrl + "/render")
                .post(RequestBody.create(payload.toString(), JSON))
                .build();

        try (var response = CLIENT.newCall(request).execute()) {
            var body = response.body() == null ? new byte[0] : response.body().bytes();
            if (!response.isSuccessful()) {
                throw new ScrapeSidecarException("stealth sidecar returned HTTP %d for %s: %s"
                        .formatted(response.code(), url,
                                new String(body, StandardCharsets.UTF_8).strip()), null);
            }
            var status = response.header("X-Upstream-Status", "0");
            if (!"0".equals(status) && Integer.parseInt(status) >= 400) {
                throw new IOException("HTTP %s fetching %s".formatted(status, url));
            }
            var finalUrl = response.header("X-Upstream-Url", url);
            // The browser may have been redirected; re-validate where it landed so a
            // hop the interceptor allowed still cannot return an unsafe final URL.
            SsrfGuard.assertUrlSafe(URI.create(finalUrl).toString());
            return new WebExtraction.FetchResult(body, "text/html; charset=utf-8", finalUrl);
        }
    }
}
