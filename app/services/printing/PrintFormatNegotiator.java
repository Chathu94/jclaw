package services.printing;

import services.EventLogger;

import javax.imageio.ImageIO;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Decides what bytes to actually send a printer (JCLAW-911).
 *
 * <p>Exists because the obvious approach — send the file as-is with its own MIME
 * type — fails on the printers people own. The Canon this was built against
 * advertises only {@code application/octet-stream}, {@code image/jpeg},
 * {@code image/urf} and {@code image/pwg-raster}. A text job sent as
 * {@code text/plain} came back
 * {@code client-error-document-format-not-supported}; the RAW fallback then
 * accepted the bytes and printed nothing at all, because a host-based inkjet has
 * no interpreter for ASCII.
 *
 * <p>So the rule is: send the source untouched when the printer claims to
 * understand it, and otherwise rasterise and re-encode into something it does.
 */
public final class PrintFormatNegotiator {

    private static final String CATEGORY = "printer";

    public static final String PWG_RASTER = "image/pwg-raster";
    public static final String JPEG = "image/jpeg";
    public static final String OCTET_STREAM = "application/octet-stream";

    /** JPEG quality for rendered pages. 0.9 keeps text edges clean without bloating the job. */
    private static final float JPEG_QUALITY = 0.9f;

    private PrintFormatNegotiator() {}

    /**
     * What to send, and how it was arrived at.
     *
     * @param document    bytes to transmit
     * @param format      MIME type to declare
     * @param converted   whether the source was rasterised rather than passed through
     * @param explanation one line for the operator; null when the source went as-is
     */
    public record Prepared(byte[] document, String format, boolean converted, String explanation) {}

    /**
     * Choose an encoding the printer accepts.
     *
     * @param document        source bytes
     * @param sourceFormat    MIME type of the source
     * @param supported       formats the printer advertises; empty means unknown
     * @param job             job options, for duplex hints in the raster header
     */
    public static Prepared prepare(byte[] document, String sourceFormat,
                                   Set<String> supported, JobAttributes job) throws IOException {
        // Unknown capabilities: send as-is. Converting on a guess is worse than
        // letting a printer that would have coped decide for itself, and IPP will
        // say document-format-not-supported if it cannot.
        if (supported.isEmpty()) {
            return new Prepared(document, sourceFormat, false, null);
        }
        // Native pass-through wins whenever it is available — no re-encode, no
        // resolution loss, and the printer's own renderer is better than ours.
        if (sourceFormat != null && supported.contains(sourceFormat.toLowerCase())) {
            return new Prepared(document, sourceFormat, false, null);
        }

        var page = PrintRenderer.PageSize.fromMedia(job == null ? null : job.media());
        var pages = PrintRenderer.render(document, sourceFormat, page);

        if (supported.contains(PWG_RASTER)) {
            var duplex = job != null && job.sides() != null && job.sides().startsWith("two-sided");
            var tumble = job != null && "two-sided-short-edge".equals(job.sides());
            var raster = PwgRasterEncoder.encode(pages, PrintRenderer.DPI,
                    job == null ? null : job.media(), duplex, tumble);
            return new Prepared(raster, PWG_RASTER, true,
                    "rendered %d page(s) to PWG raster because the printer does not accept %s"
                            .formatted(pages.size(), describe(sourceFormat)));
        }

        if (supported.contains(JPEG)) {
            // One page only: JPEG has no multi-page container, so a longer document
            // would silently lose everything after page one. Saying so beats
            // printing a truncated document that looks complete.
            var jpeg = toJpeg(pages.getFirst());
            var note = pages.size() > 1
                    ? " — ONLY PAGE 1 OF %d was sent, as this printer's best supported format "
                            .formatted(pages.size()) + "(JPEG) holds a single page"
                    : "";
            return new Prepared(jpeg, JPEG, true,
                    "rendered to JPEG because the printer does not accept %s"
                            .formatted(describe(sourceFormat)) + note);
        }

        if (supported.contains(OCTET_STREAM)) {
            // Last resort. octet-stream means "sniff it yourself", which a printer
            // may well fail at — but it is the only remaining thing it admits to
            // accepting, and an attempt beats a refusal.
            EventLogger.warn(CATEGORY, "Printer advertises no format JClaw can produce (%s); "
                    .formatted(String.join(", ", supported)) + "falling back to octet-stream");
            return new Prepared(document, OCTET_STREAM, false,
                    "sent as octet-stream — the printer advertises no format JClaw can render to, "
                            + "so it must detect the type itself");
        }

        throw new IOException("Printer accepts none of the formats JClaw can produce. "
                + "It advertises: " + String.join(", ", supported));
    }

    /** Encode one page as JPEG at {@link #JPEG_QUALITY}. */
    static byte[] toJpeg(java.awt.image.BufferedImage page) throws IOException {
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG encoder available in this JVM");
        }
        var writer = writers.next();
        var out = new ByteArrayOutputStream();
        try (var stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            var params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new javax.imageio.IIOImage(page, null, null), params);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static String describe(String format) {
        return format == null || format.isBlank() ? "that document type" : format;
    }

    /** Formats a discovered printer advertises, from its mDNS {@code pdl} TXT record. */
    public static Set<String> advertisedFormats(DiscoveredPrinter printer) {
        var pdl = printer.capabilities().get("pdl");
        if (pdl == null || pdl.isBlank()) {
            return Set.of();
        }
        return Set.copyOf(List.of(pdl.toLowerCase().split(","))
                .stream().map(String::trim).filter(s -> !s.isEmpty()).toList());
    }
}
