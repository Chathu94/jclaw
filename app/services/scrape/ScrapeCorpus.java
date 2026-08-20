package services.scrape;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import play.Play;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the scrape corpus (JCLAW-1081). Format contract: {@code evals/scrape/README.md}.
 *
 * <p>Entries carry three independent axes rather than one difficulty tier. Conflating
 * them is what broke the first corpus: an edge vendor in front does not mean the vendor
 * is blocking, and a page with no server-rendered text may be a JS gate or an ordinary
 * client-rendered app.
 */
public final class ScrapeCorpus {

    private ScrapeCorpus() {}

    public static final String DEFAULT_PATH = "evals/scrape/corpus.json";

    /**
     * @param stratum  the sampling bucket, e.g. {@code unprotected-spa}
     * @param vendor   detected edge vendor, or {@code none} — fingerprint-based, so
     *                 {@code none} is an upper bound on unprotected, not a guarantee
     * @param outcome  what the origin did: served / denied / challenge / interactive
     * @param rendering {@code ssr} or {@code spa}; null unless the origin served us
     */
    public record Entry(String url, String stratum, String vendor, String outcome,
                        String rendering, int rank, GroundTruth groundTruth) {}

    public record Corpus(String trancoListId, String probedOn, String allocation,
                         List<String> strata, List<Entry> entries) {

        /** Guards the epic gate: an aggregate threshold only forces work on the hard
         *  strata under equal allocation. Proportionally sampled, the unprotected
         *  strata alone would carry it. */
        public boolean isEqualAllocation() {
            return "equal".equals(allocation);
        }
    }

    public static Corpus load() throws IOException {
        return load(Path.of(Play.applicationPath.getAbsolutePath(), DEFAULT_PATH));
    }

    public static Corpus load(Path path) throws IOException {
        var root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                .getAsJsonObject();
        var entries = new ArrayList<Entry>();
        for (var el : root.getAsJsonArray("entries")) {
            var o = el.getAsJsonObject();
            entries.add(new Entry(
                    str(o, "url"), str(o, "stratum"), str(o, "vendor"),
                    str(o, "outcome"), str(o, "rendering"),
                    o.has("rank") ? o.get("rank").getAsInt() : 0,
                    groundTruth(o.getAsJsonObject("ground_truth"))));
        }
        var strata = new ArrayList<String>();
        if (root.has("strata")) {
            root.getAsJsonArray("strata").forEach(s -> strata.add(s.getAsString()));
        }
        return new Corpus(str(root, "tranco_list_id"), str(root, "probed_on"),
                str(root, "allocation"), List.copyOf(strata), List.copyOf(entries));
    }

    private static GroundTruth groundTruth(JsonObject gt) {
        var markers = new ArrayList<String>();
        if (gt.has("reject_markers")) {
            gt.getAsJsonArray("reject_markers").forEach(m -> markers.add(m.getAsString()));
        }
        return new GroundTruth(
                gt.has("min_chars") ? gt.get("min_chars").getAsInt() : 500,
                List.copyOf(markers),
                gt.has("expect_title") ? gt.get("expect_title").getAsString() : null);
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }
}
