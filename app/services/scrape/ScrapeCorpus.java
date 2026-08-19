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
 * Loads the CF-100 corpus (JCLAW-1081). Format contract: {@code evals/scrape/README.md}.
 */
public final class ScrapeCorpus {

    private ScrapeCorpus() {}

    public static final String DEFAULT_PATH = "evals/scrape/cf-100.json";

    public record Entry(String url, String tier, int rank, GroundTruth groundTruth) {}

    public record Corpus(String trancoListId, String probedOn, String allocation, List<Entry> entries) {

        /** Guards the epic gate: the ≥90% threshold only forces work on the hard tiers
         *  under equal allocation. A proportionally-sampled corpus would make it nearly
         *  free to pass while failing every Turnstile origin. */
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
                    o.get("url").getAsString(),
                    o.get("tier").getAsString(),
                    o.has("rank") ? o.get("rank").getAsInt() : 0,
                    groundTruth(o.getAsJsonObject("ground_truth"))));
        }
        return new Corpus(str(root, "tranco_list_id"), str(root, "probed_on"),
                str(root, "allocation"), List.copyOf(entries));
    }

    private static GroundTruth groundTruth(JsonObject gt) {
        var markers = new ArrayList<String>();
        if (gt.has("reject_markers")) {
            gt.getAsJsonArray("reject_markers").forEach(m -> markers.add(m.getAsString()));
        }
        return new GroundTruth(
                gt.has("min_chars") ? gt.get("min_chars").getAsInt() : 600,
                List.copyOf(markers),
                gt.has("expect_title") ? gt.get("expect_title").getAsString() : null);
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) ? o.get(key).getAsString() : null;
    }
}
