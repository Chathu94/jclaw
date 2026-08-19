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
 *
 * <p><b>Markers are split by whether ordinary content can contain them, and that split
 * is load-bearing.</b> The first baseline run scored oxylabs.io as {@code POLICY_BLOCK}
 * off 7,947 characters of ordinary marketing copy, because a proxy vendor's page
 * naturally contains phrases like "scraping is prohibited". Those are only evidence
 * when no page came back. Phrases only a challenge page contains are evidence on their
 * own, at any length.
 *
 * <p>Length cannot substitute for that judgement. An extracted interstitial runs a few
 * hundred characters, which clears the derived {@code minChars} floor of a small site —
 * so gating challenge detection on brevity would miss exactly the case the known-zero
 * test exists to catch.
 *
 * <p>Note the asymmetry with the corpus builder, which sees raw HTML and can match
 * {@code /cdn-cgi/challenge-platform/}. Readability strips scripts, so by the time a
 * challenge page reaches this classifier the technical markers are gone and the prose
 * is all that survives.
 */
public final class BlockClassifier {

    private BlockClassifier() {}

    /** Case-insensitive: the classifier lowercases before matching, so an anchored
     *  uppercase "HTTP" would never fire. */
    private static final Pattern HTTP_STATUS = Pattern.compile("http (\\d{3})", Pattern.CASE_INSENSITIVE);

    /** Phrases no ordinary page contains. Evidence at any length. */
    private static final String[] TURNSTILE_MARKERS = {
            "challenges.cloudflare.com/turnstile", "cf-turnstile"
    };
    private static final String[] CHALLENGE_MARKERS = {
            "/cdn-cgi/challenge-platform/", "cf_chl_opt",
            "verifying you are human", "enable javascript and cookies to continue",
            "needs to review the security of your connection"
    };

    /** Ordinary English an article can contain innocently — "just a moment" reads as
     *  prose as readily as it reads as an interstitial title. Only consulted when
     *  nothing resembling a page came back. */
    private static final String[] WEAK_MARKERS = {"just a moment", "checking your browser"};

    /** Origins that say what they block rather than merely scoring us. This is the
     *  distinction that decides, at the JCLAW-1091 gate, whether Web Bot Auth would
     *  have opened any doors — so it is recorded even though nothing acts on it yet. */
    private static final String[] POLICY = {
            "ai crawler", "ai training", "automated access is not permitted",
            "bots are not allowed", "scraping is prohibited"
    };

    public static ScrapeReason classify(String output, GroundTruth gt) {
        if (output == null) return ScrapeReason.ERROR;
        // Blank is a fetched response that extracted to nothing, not a failure of ours:
        // a pure-JS gate and a client-rendered app both land here. Readability strips the
        // scripts that would tell them apart, so this classifier cannot — JCLAW-1086 gets
        // the raw response and can. Calling it ERROR blamed our stack for 22 of 25
        // Turnstile-tier origins that OkHttp had in fact reached.
        if (output.isBlank()) return ScrapeReason.THIN_CONTENT;
        var lower = output.toLowerCase(Locale.ROOT);

        if (lower.startsWith("error")) return classifyError(lower);

        if (containsAny(lower, TURNSTILE_MARKERS)) return ScrapeReason.TURNSTILE;
        if (containsAny(lower, CHALLENGE_MARKERS) || gt.rejected(output)) return ScrapeReason.JS_CHALLENGE;

        if (output.length() >= gt.minChars()) return ScrapeReason.OK;

        if (containsAny(lower, WEAK_MARKERS)) return ScrapeReason.JS_CHALLENGE;
        if (containsAny(lower, POLICY)) return ScrapeReason.POLICY_BLOCK;
        return ScrapeReason.THIN_CONTENT;
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
