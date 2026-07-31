import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.printing.JobAttributes;
import services.printing.PrinterDefaults;

/**
 * The operator's saved default printer (JCLAW-911).
 *
 * <p>What matters here is the round trip and the clearing path: a half-cleared
 * default pointing at a printer that has been unplugged sends jobs into a hole,
 * which is worse than having no default at all.
 */
class PrinterDefaultsTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        PrinterDefaults.clear();
    }

    @Test
    void unsetByDefault() {
        var d = PrinterDefaults.load();
        assertTrue(d.isUnset());
        // The tool keys off isUnset to decide whether to refuse; a blank-but-present
        // host would sail past a null check and print nowhere.
        assertNull(d.host());
    }

    @Test
    void roundTripsThePrinterAndItsJobOptions() {
        PrinterDefaults.save(new PrinterDefaults.Defaults(
                "Office LaserJet", "10.0.0.5", 631, "IPP",
                "two-sided-long-edge", "monochrome", "iso_a4_210x297mm"));

        var d = PrinterDefaults.load();
        assertFalse(d.isUnset());
        assertEquals("Office LaserJet", d.name());
        assertEquals("10.0.0.5", d.host());
        assertEquals(631, d.port());
        assertEquals("IPP", d.protocol());

        // The job options must arrive at the print path in the shape it consumes.
        var job = d.jobAttributes();
        assertEquals("two-sided-long-edge", job.sides());
        assertEquals("monochrome", job.colorMode());
        assertNull(job.validationError());
    }

    @Test
    void clearingLeavesNothingBehind() {
        PrinterDefaults.save(new PrinterDefaults.Defaults(
                "P", "10.0.0.5", 631, "IPP", "two-sided-long-edge", "color", "a4"));
        PrinterDefaults.clear();

        var d = PrinterDefaults.load();
        assertTrue(d.isUnset(), "cleared default must read as unset");
        // Every field, not just the host: a stale sides/color left in config would
        // silently attach itself to the NEXT printer the operator picks.
        assertNull(d.sides());
        assertNull(d.color());
        assertNull(d.media());
        assertNull(d.protocol());
    }

    @Test
    void matchesTheDiscoveredPrinterItWasChosenFrom() {
        var withPort = new PrinterDefaults.Defaults("P", "10.0.0.5", 631, "IPP", null, null, null);
        assertTrue(withPort.matches("10.0.0.5", 631));
        assertFalse(withPort.matches("10.0.0.6", 631));
        assertFalse(withPort.matches("10.0.0.5", 9100));

        // Port 0 means "the protocol's standard port", so it must still match the
        // printer it was chosen from — otherwise the UI never shows the badge.
        var noPort = new PrinterDefaults.Defaults("P", "10.0.0.5", 0, null, null, null, null);
        assertTrue(noPort.matches("10.0.0.5", 631));
        assertTrue(noPort.matches("10.0.0.5", 9100));

        assertFalse(PrinterDefaults.NONE.matches("10.0.0.5", 631));
    }

    @Test
    void aNonNumericPortDoesNotBreakLoading() {
        // A hand-edited config row should degrade to the protocol default rather
        // than throwing on every print.
        services.ConfigService.set("printer.default.host", "10.0.0.9");
        services.ConfigService.set("printer.default.port", "not-a-number");

        var d = PrinterDefaults.load();
        assertEquals("10.0.0.9", d.host());
        assertEquals(0, d.port());
    }

    @Test
    void clearingOneJobOptionDoesNotPoisonEveryLaterJob() {
        // The write side stores "" for an absent value, so the read side must turn
        // it back into null. It did not, and the empty string reached
        // JobAttributes.validationError as an invalid keyword — every print then
        // failed with "invalid 'sides' value ''" until the default was re-saved.
        PrinterDefaults.save(new PrinterDefaults.Defaults(
                "P", "10.0.0.5", 631, "IPP", "two-sided-long-edge", "color", "a4"));
        // Operator switches duplex back to "Printer default", keeping everything else.
        PrinterDefaults.save(new PrinterDefaults.Defaults(
                "P", "10.0.0.5", 631, "IPP", null, "color", "a4"));

        var d = PrinterDefaults.load();
        assertNull(d.sides(), "a cleared option must read back as null, not \"\"");
        assertEquals("color", d.color(), "clearing one option must not disturb the others");
        assertNull(d.jobAttributes().validationError(),
                "the resulting job options must still be printable");
    }

    @Test
    void invalidJobOptionsAreRejectableBeforeBeingSaved() {
        // The controller validates on save so a bad default cannot sit in config
        // waiting to fail the first real job.
        assertNotNull(new JobAttributes("sideways", null, null).validationError());
        assertNull(new JobAttributes("one-sided", "auto", null).validationError());
    }
}
