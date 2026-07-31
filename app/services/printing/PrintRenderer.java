package services.printing;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;

import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a document into page bitmaps (JCLAW-911).
 *
 * <p>Needed because printers do not agree on what they will accept. An
 * AirPrint-class inkjet — the Canon this was built against — advertises
 * {@code image/jpeg} and {@code image/pwg-raster} and nothing else useful: it
 * rejects {@code text/plain} outright and has no PDF interpreter. Sending the
 * source bytes and hoping is how a print job silently produces a blank page.
 *
 * <p>So JClaw rasterises first and lets {@link PrintFormatNegotiator} choose an
 * encoding the printer actually claims to support. Rendering is deliberately
 * plain: this is a utility print path, not a layout engine.
 */
public final class PrintRenderer {

    /** Render resolution. 300 DPI is the floor for legible text on paper. */
    public static final int DPI = 300;

    /** A4 at {@link #DPI}, in pixels. The most common size outside North America. */
    public static final int A4_WIDTH = 2480;
    public static final int A4_HEIGHT = 3508;

    /** US Letter at {@link #DPI}, in pixels. */
    public static final int LETTER_WIDTH = 2550;
    public static final int LETTER_HEIGHT = 3300;

    /** Printable margin. Most inkjets cannot reach closer than ~5mm to the edge. */
    private static final int MARGIN = 150;

    /** Point size for rendered text. 11pt at 300 DPI ≈ 46px. */
    private static final int TEXT_POINT_SIZE = 46;

    /** Refuse absurd page counts rather than filling a tray. */
    private static final int MAX_PAGES = 100;

    private PrintRenderer() {}

    /** Page geometry for a job. */
    public record PageSize(int width, int height) {
        public static final PageSize A4 = new PageSize(A4_WIDTH, A4_HEIGHT);
        public static final PageSize LETTER = new PageSize(LETTER_WIDTH, LETTER_HEIGHT);

        /**
         * Page size from a PWG media name, defaulting to A4.
         *
         * <p>Only the two common sizes are recognised. Guessing dimensions from an
         * arbitrary PWG name would produce a page that is subtly the wrong size,
         * which wastes paper more quietly than an obvious failure.
         */
        public static PageSize fromMedia(String media) {
            if (media != null && media.toLowerCase().contains("letter")) {
                return LETTER;
            }
            return A4;
        }
    }

    /**
     * Rasterise {@code document} into one bitmap per page.
     *
     * @param document     the source bytes
     * @param sourceFormat MIME type of {@code document}
     * @param page         target page geometry
     * @throws IOException if the document cannot be parsed as its declared type
     */
    public static List<BufferedImage> render(byte[] document, String sourceFormat, PageSize page)
            throws IOException {
        var format = sourceFormat == null ? "" : sourceFormat.toLowerCase();
        if (format.startsWith("image/")) {
            return List.of(fitToPage(readImage(document), page));
        }
        if (format.equals("application/pdf")) {
            return renderPdf(document, page);
        }
        // Everything else is treated as text. Reaching here with binary content
        // produces mojibake on paper rather than a crash — the same thing a
        // printer would do with it, and the caller already had a chance to
        // declare a real type.
        return renderText(new String(document, StandardCharsets.UTF_8), page);
    }

    /** Decode image bytes, failing with a useful message rather than a null. */
    static BufferedImage readImage(byte[] bytes) throws IOException {
        var image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            throw new IOException("Unsupported or corrupt image — no ImageIO decoder accepted it. "
                    + "JPEG, PNG, GIF, BMP, TIFF and WebP are readable here.");
        }
        return image;
    }

    /**
     * Scale an image to fit the page, preserving aspect ratio and centring it on
     * white. Never upscales beyond the printable area, and never crops: a photo
     * that arrives at the wrong aspect gets bars, not a silently trimmed subject.
     */
    public static BufferedImage fitToPage(BufferedImage source, PageSize page) {
        var canvas = blankPage(page);
        var g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            var maxWidth = page.width() - 2 * MARGIN;
            var maxHeight = page.height() - 2 * MARGIN;
            var scale = Math.min((double) maxWidth / source.getWidth(),
                    (double) maxHeight / source.getHeight());
            var w = (int) Math.round(source.getWidth() * scale);
            var h = (int) Math.round(source.getHeight() * scale);
            g.drawImage(source, (page.width() - w) / 2, (page.height() - h) / 2, w, h, null);
        } finally {
            g.dispose();
        }
        return canvas;
    }

    /** Render each PDF page at {@link #DPI}, then fit it to the target page. */
    static List<BufferedImage> renderPdf(byte[] pdf, PageSize page) throws IOException {
        var pages = new ArrayList<BufferedImage>();
        try (var document = Loader.loadPDF(pdf)) {
            var renderer = new PDFRenderer(document);
            var count = Math.min(document.getNumberOfPages(), MAX_PAGES);
            for (int i = 0; i < count; i++) {
                // Rendered at DPI then fitted, rather than rendered straight to the
                // page box: PDF pages carry their own size, and scaling after the
                // fact keeps a Letter-sized PDF from being stretched onto A4.
                pages.add(fitToPage(renderer.renderImageWithDPI(i, DPI, ImageType.RGB), page));
            }
        }
        return pages;
    }

    /**
     * Lay text out into pages: monospaced, hard-wrapped at the printable width,
     * paginated at the printable height.
     *
     * <p>Monospaced on purpose. The things an agent prints here are logs, tables
     * and code, all of which depend on column alignment; a proportional font
     * silently destroys that.
     */
    public static List<BufferedImage> renderText(String text, PageSize page) {
        var font = new Font(Font.MONOSPACED, Font.PLAIN, TEXT_POINT_SIZE);
        var probe = blankPage(new PageSize(1, 1)).createGraphics();
        probe.setFont(font);
        var metrics = probe.getFontMetrics();
        var charWidth = Math.max(1, metrics.charWidth('M'));
        var lineHeight = metrics.getHeight();
        probe.dispose();

        var usableWidth = page.width() - 2 * MARGIN;
        var usableHeight = page.height() - 2 * MARGIN;
        var columns = Math.max(1, usableWidth / charWidth);
        var rows = Math.max(1, usableHeight / lineHeight);

        var lines = wrap(text, columns);
        var pages = new ArrayList<BufferedImage>();
        for (int start = 0; start < lines.size() && pages.size() < MAX_PAGES; start += rows) {
            var canvas = blankPage(page);
            var g = canvas.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(Color.BLACK);
                g.setFont(font);
                var y = MARGIN + metrics.getAscent();
                for (int i = start; i < Math.min(start + rows, lines.size()); i++) {
                    g.drawString(lines.get(i), MARGIN, y);
                    y += lineHeight;
                }
            } finally {
                g.dispose();
            }
            pages.add(canvas);
        }
        // An empty document still yields one page; the caller has already refused
        // genuinely empty input, so this only catches whitespace.
        return pages.isEmpty() ? List.of(blankPage(page)) : pages;
    }

    /** Hard-wrap at {@code columns}, preserving explicit newlines. */
    public static List<String> wrap(String text, int columns) {
        var out = new ArrayList<String>();
        for (var raw : text.replace("\t", "    ").split("\n", -1)) {
            if (raw.isEmpty()) {
                out.add("");
                continue;
            }
            for (int i = 0; i < raw.length(); i += columns) {
                out.add(raw.substring(i, Math.min(raw.length(), i + columns)));
            }
        }
        return out;
    }

    /** A white page. White, not transparent — a printer renders alpha as black. */
    public static BufferedImage blankPage(PageSize page) {
        var image = new BufferedImage(page.width(), page.height(), BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, page.width(), page.height());
        } finally {
            g.dispose();
        }
        return image;
    }
}
