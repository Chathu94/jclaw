import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.printing.JobAttributes;
import services.printing.PrintFormatNegotiator;
import services.printing.PrintRenderer;

import javax.imageio.ImageIO;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Rasterisation and format negotiation (JCLAW-911).
 *
 * <p>Both exist because of one measured failure: a Canon E3300 answered
 * {@code client-error-document-format-not-supported} for {@code text/plain},
 * and the raw-socket fallback then accepted the bytes and printed nothing —
 * the worst outcome, since it reports success.
 */
class PrintRenderingTest extends UnitTest {

    private static byte[] png(int w, int h) throws Exception {
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, w, h);
        g.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    // ─── Rendering ───

    @Test
    void textPaginatesRatherThanTruncating() {
        var many = "line\n".repeat(400);
        var pages = PrintRenderer.renderText(many, PrintRenderer.PageSize.a4());
        // 400 lines cannot fit one A4 page at 11pt. Silently dropping the
        // remainder would print a document that looks complete.
        assertTrue(pages.size() > 1, "expected pagination, got " + pages.size() + " page(s)");
        assertEquals(PrintRenderer.PageSize.a4().width(), pages.getFirst().getWidth());
        assertEquals(PrintRenderer.PageSize.a4().height(), pages.getFirst().getHeight());
    }

    @Test
    void textWrapsAtTheColumnAndKeepsExplicitNewlines() {
        var wrapped = PrintRenderer.wrap("aaaaaaaaaa\n\nbb", 4);
        // Long line splits into ceil(10/4)=3; the blank line survives; short line intact.
        assertEquals(java.util.List.of("aaaa", "aaaa", "aa", "", "bb"), wrapped);
    }

    @Test
    void anImageIsFittedWholeRatherThanCropped() throws Exception {
        // A wide image on a portrait page gets bars, not a trimmed subject —
        // cropping a photo to fit is a silent content change.
        var wide = ImageIO.read(new java.io.ByteArrayInputStream(png(400, 100)));
        var fitted = PrintRenderer.fitToPage(wide, PrintRenderer.PageSize.a4());

        assertEquals(PrintRenderer.PageSize.a4().width(), fitted.getWidth());
        assertEquals(PrintRenderer.PageSize.a4().height(), fitted.getHeight());
        // Corners stay white (the letterbox), centre carries the image.
        assertEquals(Color.WHITE.getRGB(), fitted.getRGB(5, 5));
        assertEquals(Color.RED.getRGB(),
                fitted.getRGB(PrintRenderer.PageSize.a4().width() / 2, PrintRenderer.PageSize.a4().height() / 2));
    }

    @Test
    void aCorruptImageFailsWithSomethingActionable() {
        var boom = assertThrows(java.io.IOException.class,
                () -> PrintRenderer.render("not an image".getBytes(StandardCharsets.UTF_8),
                        "image/png", PrintRenderer.PageSize.a4()));
        assertTrue(boom.getMessage().contains("image"), boom.getMessage());
    }

    @Test
    void mediaNameSelectsThePageGeometry() {
        var letter = PrintRenderer.PageSize.fromMedia("na_letter_8.5x11in", 300);
        assertEquals(2550, letter.width());
        assertEquals(3300, letter.height());

        // Anything unrecognised is A4 rather than a guessed size — a subtly wrong
        // page wastes paper more quietly than an obvious failure.
        var a4 = PrintRenderer.PageSize.a4();
        assertEquals(a4, PrintRenderer.PageSize.fromMedia("iso_a4_210x297mm", 300));
        assertEquals(a4, PrintRenderer.PageSize.fromMedia(null, 300));
        assertEquals(a4, PrintRenderer.PageSize.fromMedia("vendor-tray-7", 300));
    }

    @Test
    void pageGeometryScalesWithThePrintersDeclaredResolution() {
        // The bug this pins: 300 DPI was hardcoded and sent to a Canon that
        // declares 600dpi only. The job was accepted, then the printer stopped
        // with printer-state-reasons=spool-area-full and emitted nothing.
        var at300 = PrintRenderer.PageSize.fromMedia("iso_a4_210x297mm", 300);
        var at600 = PrintRenderer.PageSize.fromMedia("iso_a4_210x297mm", 600);

        assertEquals(600, at600.dpi(), "the page must carry the resolution it was built for");
        // Twice the DPI is twice the pixels on each axis, within rounding.
        assertTrue(Math.abs(at600.width() - at300.width() * 2) <= 2,
                "%d vs %d".formatted(at600.width(), at300.width()));
        assertTrue(Math.abs(at600.height() - at300.height() * 2) <= 2,
                "%d vs %d".formatted(at600.height(), at300.height()));

        // Zero means "the printer would not say" — fall back, never render 0x0.
        assertEquals(PrintRenderer.DEFAULT_DPI,
                PrintRenderer.PageSize.fromMedia(null, 0).dpi());
    }

    @Test
    void greyscaleRenderingIsAQuarterOfTheHeapAndAThirdOfTheWire() throws Exception {
        var canon = Set.of("image/pwg-raster");
        var caps = new services.printing.IppClient.RasterCapabilities(600, true, Set.of("sgray_8"));
        var text = "hello printer".getBytes(StandardCharsets.UTF_8);

        var grey = PrintFormatNegotiator.prepare(text, "text/plain", canon,
                JobAttributes.DEFAULTS, caps);
        var colour = PrintFormatNegotiator.prepare(text, "text/plain", canon,
                new JobAttributes(null, "color", null), caps);

        assertEquals("image/pwg-raster", grey.format());
        assertTrue(grey.explanation().contains("600 DPI"), grey.explanation());
        assertTrue(grey.explanation().contains("greyscale"), grey.explanation());
        // Asking for colour must actually get colour, not be silently overridden.
        assertTrue(colour.explanation().contains("colour"), colour.explanation());
        assertTrue(grey.document().length < colour.document().length,
                "greyscale should be smaller: %d vs %d"
                        .formatted(grey.document().length, colour.document().length));
    }

    // ─── Negotiation ───

    @Test
    void aFormatThePrinterSupportsGoesThroughUntouched() throws Exception {
        var source = png(10, 10);
        var prepared = PrintFormatNegotiator.prepare(source, "image/png",
                Set.of("image/png", "image/jpeg"), JobAttributes.DEFAULTS);

        // Native pass-through beats re-encoding: no generation loss, and the
        // printer's own renderer is better than ours.
        assertFalse(prepared.converted());
        assertEquals("image/png", prepared.format());
        assertArrayEquals(source, prepared.document());
        assertNull(prepared.explanation());
    }

    @Test
    void textForACanonClassPrinterBecomesPwgRaster() throws Exception {
        // The exact capability set the Canon advertises.
        var canon = Set.of("application/octet-stream", "image/jpeg", "image/urf", "image/pwg-raster");
        var prepared = PrintFormatNegotiator.prepare(
                "hello printer".getBytes(StandardCharsets.UTF_8), "text/plain",
                canon, JobAttributes.DEFAULTS);

        assertTrue(prepared.converted());
        assertEquals("image/pwg-raster", prepared.format());
        assertEquals("RaS2", new String(prepared.document(), 0, 4, StandardCharsets.US_ASCII));
        assertTrue(prepared.explanation().contains("text/plain"), prepared.explanation());
    }

    @Test
    void jpegIsUsedWhenRasterIsNotOfferedAndTruncationIsAnnounced() throws Exception {
        var jpegOnly = Set.of("image/jpeg");
        var manyPages = "line\n".repeat(400);
        var prepared = PrintFormatNegotiator.prepare(
                manyPages.getBytes(StandardCharsets.UTF_8), "text/plain",
                jpegOnly, JobAttributes.DEFAULTS);

        assertEquals("image/jpeg", prepared.format());
        assertTrue(prepared.converted());
        // JPEG holds one page. Printing page 1 of 9 without saying so produces a
        // document that looks complete and is not.
        assertTrue(prepared.explanation().contains("ONLY PAGE 1"), prepared.explanation());
    }

    @Test
    void unknownCapabilitiesMeanSendAsIs() throws Exception {
        var source = "hello".getBytes(StandardCharsets.UTF_8);
        var prepared = PrintFormatNegotiator.prepare(source, "text/plain",
                Set.of(), JobAttributes.DEFAULTS);

        // Converting on a guess is worse than letting a printer that would have
        // coped decide; IPP will say so if it cannot.
        assertFalse(prepared.converted());
        assertArrayEquals(source, prepared.document());
    }

    @Test
    void aPrinterAcceptingNothingWeProduceFailsLoudly() {
        var boom = assertThrows(java.io.IOException.class, () ->
                PrintFormatNegotiator.prepare("x".getBytes(StandardCharsets.UTF_8), "text/plain",
                        Set.of("application/vnd.hp-PCL"), JobAttributes.DEFAULTS));
        // Naming what it does accept is the difference between "buy a different
        // printer" and "convert your file".
        assertTrue(boom.getMessage().contains("vnd.hp-PCL"), boom.getMessage());
    }

    @Test
    void advertisedFormatsComeFromTheMdnsPdlRecord() {
        var printer = new services.printing.DiscoveredPrinter("P", "10.0.0.5", 631,
                services.printing.PrintProtocol.IPP,
                java.util.Map.of("pdl", "application/octet-stream,image/jpeg,image/pwg-raster"));
        var formats = PrintFormatNegotiator.advertisedFormats(printer);

        assertTrue(formats.contains("image/pwg-raster"));
        assertTrue(formats.contains("image/jpeg"));
        assertEquals(3, formats.size());
        // No pdl record at all is "unknown", not "supports nothing".
        assertTrue(PrintFormatNegotiator.advertisedFormats(
                new services.printing.DiscoveredPrinter("P", "h", 631,
                        services.printing.PrintProtocol.IPP, java.util.Map.of())).isEmpty());
    }
}
