package services.scrape;

import com.google.gson.JsonParser;
import play.Play;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How often each outcome actually occurs on the web, from the Tranco probe that built
 * the corpus (JCLAW-1091).
 *
 * <p>The corpus is deliberately skewed toward difficulty — 25 entries per stratum
 * regardless of how rare that stratum is — because equal allocation is what forces work
 * on the hard tiers. That makes it the right denominator for a per-stratum floor and the
 * wrong one for an "overall" number: read off the corpus, an aggregate describes a
 * distribution nobody browses.
 *
 * <p><b>Unreachable domains are excluded, and this is load-bearing.</b> Roughly a third
 * of the Tranco sample did not respond at all — parked, dead or NXDOMAIN. Nothing can
 * scrape a dead host, so leaving them in the denominator caps the achievable score near
 * 69% by arithmetic rather than by capability. The exclusion moves the measured ladder
 * run from about 61% to 89.1%, so it is stated in the report rather than buried here.
 */
public final class ScrapePrevalence {

    public static final String DEFAULT_PATH = "evals/scrape/prevalence.json";

    /** Domains that never answered; excluded from the denominator (see class Javadoc). */
    private static final String UNREACHABLE = "unreachable";

    /** Outcome to its share of the reachable web, summing to 1. */
    public record Weights(Map<String, Double> byOutcome, long reachable, long unreachable) {

        /** The share this outcome carries, or 0 for one the probe never saw. */
        public double weight(String outcome) {
            return byOutcome.getOrDefault(outcome, 0.0);
        }

        public String note() {
            return "%,d reachable of %,d probed; %,d unreachable excluded from the denominator"
                    .formatted(reachable, reachable + unreachable, unreachable);
        }
    }

    private ScrapePrevalence() {}

    public static Weights load() throws IOException {
        return load(Path.of(Play.applicationPath.getAbsolutePath(), DEFAULT_PATH));
    }

    public static Weights load(Path path) throws IOException {
        var root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        var byOutcome = root.getAsJsonObject("by_outcome");

        long unreachable = 0;
        long reachable = 0;
        var counts = new LinkedHashMap<String, Long>();
        for (var entry : byOutcome.entrySet()) {
            long n = entry.getValue().getAsLong();
            if (UNREACHABLE.equals(entry.getKey())) {
                unreachable = n;
            } else {
                counts.put(entry.getKey(), n);
                reachable += n;
            }
        }
        if (reachable == 0) {
            throw new IOException("prevalence has no reachable outcomes: " + path);
        }

        var weights = new LinkedHashMap<String, Double>();
        for (var e : counts.entrySet()) {
            weights.put(e.getKey(), (double) e.getValue() / reachable);
        }
        return new Weights(Map.copyOf(weights), reachable, unreachable);
    }
}
