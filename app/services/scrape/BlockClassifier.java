package services.scrape;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns one fetch outcome into a {@link ScrapeReason} (JCLAW-1081).
 *
 * <p>Provisional. JCLAW-1086 hardens it and gives the runtime escalation ladder the
 * same entry point, so the harness and the tool can never disagree about why a fetch
 * failed. Until then it reads what {@code WebFetchTool} actually returns — a flat
 * String — which is why the status code is recovered by regex rather than read from
 * a field. That is the seam JCLAW-1086 replaces, not a pattern to copy.
 */
public final class BlockClassifier {

    private BlockClassifier() {}

    /** Case-insensitive: the classifier lowercases before matching, so an anchored
     *  uppercase "HTTP" would never fire. */
    private static final Pattern HTTP_STATUS = Pattern.compile("http (\\d{3})", Pattern.CASE_INSENSITIVE);

    /** Cloudflare's interstitials; distinct from {@link GroundTruth#rejectMarkers()},
     *  which is per-entry and may be widened by the corpus builder. */
    private static final String[] CHALLENGE = {
            "/cdn-cgi/challenge-platform/", "just a moment", "cf_chl_opt",
            "enable javascript and cookies to continue"
    };
    private static final String[] TURNSTILE = {"challenges.cloudflare.com/turnstile", "cf-turnstile"};

    /** Origins that say what they block rather than merely scoring us. This is the
     *  distinction that decides, at the JCLAW-1091 gate, whether Web Bot Auth would
     *  have opened any doors — so it is recorded even though nothing acts on it yet. */
    private static final String[] POLICY = {
            "ai crawler", "ai training", "automated access is not permitted",
            "bots are not allowed", "scraping is prohibited"
    };

    public static ScrapeReason classify(String output, GroundTruth gt) {
        if (output == null || output.isBlank()) return ScrapeReason.ERROR;
        var lower = output.toLowerCase(Locale.ROOT);

        if (lower.startsWith("error")) return classifyError(lower);
        if (containsAny(lower, TURNSTILE)) return ScrapeReason.TURNSTILE;
        if (containsAny(lower, CHALLENGE) || gt.rejected(output)) return ScrapeReason.JS_CHALLENGE;
        if (containsAny(lower, POLICY)) return ScrapeReason.POLICY_BLOCK;

        // Extraction succeeded but yielded less than a page. Not a challenge we can
        // name, so it lands as a trust-score refusal rather than an error of ours.
        if (output.length() < gt.minChars()) return ScrapeReason.TRUST_BLOCK;
        return ScrapeReason.OK;
    }

    private static ScrapeReason classifyError(String lower) {
        if (lower.contains("timed out")) return ScrapeReason.TIMEOUT;
        var m = HTTP_STATUS.matcher(lower);
        if (m.find()) {
            return switch (Integer.parseInt(m.group(1))) {
                case 401, 402, 451 -> ScrapeReason.POLICY_BLOCK;
                case 403, 406, 429, 503 -> ScrapeReason.TRUST_BLOCK;
                default -> ScrapeReason.ERROR;
            };
        }
        return ScrapeReason.ERROR;
    }

    private static boolean containsAny(String haystack, String[] needles) {
        for (var n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }
}
