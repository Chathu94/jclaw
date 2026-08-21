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
        assertEquals(1.0, w.weight("served") + w.weight("denied"), 1e-9,
                "weights must span the reachable web exactly once");
    }

    @Test
    void theExclusionTravelsWithTheNumber() throws IOException {
        // A weighted score without this sentence beside it is misleading, so the loader
        // hands the caller both rather than trusting a report to remember.
        var w = ScrapePrevalence.load(write("""
                {"by_outcome": {"unreachable": 9363, "served": 17605, "challenge": 1502}}
                """));
        assertTrue(w.note().contains("unreachable"), "note must name the exclusion: " + w.note());
        assertTrue(w.note().contains("19,107"), "note must state the denominator: " + w.note());
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
    void theShippedPrevalenceFileLoadsAndItsWeightsSumToOne() throws IOException {
        var w = ScrapePrevalence.load(
                Path.of(play.Play.applicationPath.getAbsolutePath(), ScrapePrevalence.DEFAULT_PATH));
        double total = w.byOutcome().values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(1.0, total, 1e-9);
        assertTrue(w.unreachable() > 0, "the real probe did find unreachable domains");
    }
}
