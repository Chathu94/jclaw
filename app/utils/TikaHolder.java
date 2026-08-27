package utils;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaConfigException;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.CompositeParser;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.ocr.TesseractOCRParser;
import play.Logger;
import services.OcrExecutableResolver;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Shared Apache Tika instances for the entire application. Both Tika
 * (MIME sniffing) and AutoDetectParser (document text extraction) are
 * thread-safe for concurrent reuse, and both perform expensive
 * ServiceLoader-driven parser-registry discovery in their constructors —
 * a single process-wide instance avoids re-walking the classpath on every
 * call site.
 */
public final class TikaHolder {

    public static final Tika TIKA = new Tika();

    public static final AutoDetectParser PARSER = createParser();

    private TikaHolder() {}

    private static AutoDetectParser createParser() {
        var parser = new AutoDetectParser();
        var resolution = OcrExecutableResolver.resolve();
        if (resolution == null || !resolution.available()) return parser;
        var seen = Collections.newSetFromMap(new IdentityHashMap<Parser, Boolean>());
        configureTesseract(parser, resolution.directory(), seen);
        return parser;
    }

    private static void configureTesseract(Parser parser, java.nio.file.Path directory, Set<Parser> seen) {
        if (!seen.add(parser)) return;
        if (parser instanceof TesseractOCRParser tesseract) {
            try {
                tesseract.setTesseractPath(directory.toAbsolutePath().toString());
                tesseract.initialize(java.util.Map.of());
            } catch (TikaConfigException | RuntimeException e) {
                Logger.warn("OCR: could not configure Tika for tesseract at %s (%s)", directory, e.getMessage());
            }
        }
        if (parser instanceof CompositeParser composite) {
            for (var child : composite.getAllComponentParsers()) {
                configureTesseract(child, directory, seen);
            }
        }
    }
}
