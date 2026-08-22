import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.scrape.ScrapePrevalence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Prevalence weighting for the epic gate (JCLAW-1091).
 *
 * <p>The unreachable exclusion is the whole reason this has a test. It is worth about
 * thirty points on a measured run, which makes it the difference between the gate passing
 * and failing — so it is pinned here rather than left as a line of arithmetic somebody
 * could "simplify" later.
 */
class ScrapePrevalenceTest extends UnitTest {

    private Path write(String json) throws IOException {
        var p = Files.createTempFile("prevalence", ".json");
        Files.writeString(p, json);
        p.toFile().deleteOnExit();
        return p;
    }

    @Test
    void unreachableDomainsAreExcludedFromTheDenominator() throws IOException {
        // Nothing can scrape a dead host. Counting them would cap the achievable score
        // by arithmetic rather than by capability — here at 50%, so a 90% target would
        // be unreachable no matter how good the scraper was.
        var w = ScrapePrevalence.load(write("""
                {"by_outcome": {"unreachable": 100, "served": 80, "denied": 20}}
                """));

        assertEquals(100, w.reachable());
        assertEquals(100, w.unreachable());
        assertEquals(0.8, w.weight("served"), 1e-9);
        assertEquals(0.2, w.weight("denied"), 1e-9);
        // Not "the weights sum to 1" — every weight is count/reachable and reachable is
        // the sum of those counts, so that holds for any input file and can fail for
        // nothing. What can fail is unreachable leaking back in, on either side of the
        // division.
        assertFalse(w.byOutcome().containsKey("unreachable"),
                "a dead host is not an outcome the scraper can score against");
        assertEquals(0.0, w.weight("unreachable"), 1e-9);
    }

    @Test
    void theExclusionTravelsWithTheNumber() throws IOException {
        // A weighted score without this sentence beside it is misleading, so the loader
        // hands the caller both rather than trusting a report to remember.
        var w = ScrapePrevalence.load(write("""
                {"by_outcome": {"unreachable": 9363, "served": 17605, "challenge": 1502}}
                """));
        assertTrue(w.note().contains("unreachable"), "note must name the exclusion: " + w.note());
        // Grouped through the same formatter the note uses: "%,d" follows the default
        // locale, so a literal "19,107" asserts the JVM's locale as much as the number.
        assertTrue(w.note().contains("%,d".formatted(19_107)),
                "note must state the denominator: " + w.note());
        assertTrue(w.note().contains("%,d".formatted(9_363)),
                "and how many were excluded to reach it: " + w.note());
    }

    @Test
    void anOutcomeTheProbeNeverSawCarriesNoWeight() throws IOException {
        var w = ScrapePrevalence.load(write("""
                {"by_outcome": {"served": 10}}
                """));
        assertEquals(0.0, w.weight("interactive"), 1e-9);
    }

    @Test
    void aProbeWithNothingReachableIsRefusedRatherThanDividedByZero() throws IOException {
        var p = write("""
                {"by_outcome": {"unreachable": 42}}
                """);
        assertThrows(IOException.class, () -> ScrapePrevalence.load(p));
    }

    @Test
    void anUnusableFileFailsTheSameWayAMissingOneDoes() throws IOException {
        // Every caller treats an unusable prevalence file as an absent one and catches
        // IOException for it. Gson signals a reshaped file with unchecked exceptions, so
        // a run used to be discarded over a typo in a data file.
        for (var bad : java.util.List.of(
                "not json at all",
                "[1, 2, 3]",
                "{\"by_outcome\": 7}",
                "{\"probed_on\": \"2026-08-20\"}",
                "{\"by_outcome\": {\"served\": \"lots\"}}",
                "{\"by_outcome\": {\"served\": 10, \"denied\": -1}}")) {
            var p = write(bad);
            assertThrows(IOException.class, () -> ScrapePrevalence.load(p),
                    "must arrive as the checked failure a missing file does: " + bad);
        }
    }

    @Test
    void theShippedPrevalenceFileWeightsTheReachableWebOnly() throws IOException {
        var path = Path.of(play.Play.applicationPath.getAbsolutePath(),
                ScrapePrevalence.DEFAULT_PATH);
        var w = ScrapePrevalence.load(path);

        // Read independently of the loader: the counts are what the gate report's
        // prevalence figure is built on, so an assertion derived from the loader's own
        // arithmetic would only restate it.
        var byOutcome = JsonParser.parseString(Files.readString(path))
                .getAsJsonObject().getAsJsonObject("by_outcome");
        long unreachable = byOutcome.get("unreachable").getAsLong();
        long reachable = byOutcome.entrySet().stream()
                .filter(e -> !"unreachable".equals(e.getKey()))
                .mapToLong(e -> e.getValue().getAsLong()).sum();

        assertTrue(unreachable > 0, "the real probe did find unreachable domains");
        assertEquals(reachable, w.reachable());
        assertEquals(unreachable, w.unreachable());
        assertEquals((double) byOutcome.get("served").getAsLong() / reachable,
                w.weight("served"), 1e-9,
                "an outcome's share is of the reachable web, not of the whole probe");
    }
}
