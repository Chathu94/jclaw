import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Guards the tool-icon contract across the JVM/TypeScript boundary: every key an
 * {@code icon()} override can return must have an entry in the frontend
 * dictionary that renders it.
 *
 * <p>Three hand-synced copies of that dictionary previously drifted from the
 * registry. The agent detail page mapped an unknown key to {@code null}, and
 * {@code <component :is="null">} renders nothing, so {@code memory}
 * ({@code brain}) and {@code printer} showed as empty boxes with no error.
 *
 * <p>Scans source rather than enumerating {@code ToolRegistry} on purpose: the
 * registry is a process-global that concurrent play1 test lanes fight over, and
 * taking its lock here reshuffles the whole suite's scheduling. Source also
 * covers keys no native registration reaches — the {@code Tool} interface
 * default, and the MCP adapters that only register once a server connects.
 */
class ToolIconContractTest extends UnitTest {

    private static final String DICTIONARY = "frontend/utils/tool-icons.ts";

    /** Matches both the block form and the {@code @Override public ... } one-liner. */
    private static final Pattern ICON_RETURN =
            Pattern.compile("String\\s+icon\\(\\)\\s*\\{\\s*return\\s+([^;]+);");

    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]*)\"");

    @Test
    void everyIconKeyInProductionSourceIsMappedInTheFrontendDictionary() throws IOException {
        var dictionary = Files.readString(repoFile(DICTIONARY));
        var keys = iconKeysBySource();

        var unmapped = new TreeMap<String, String>();
        keys.forEach((key, source) -> {
            if (!dictionary.contains("'" + key + "':")) unmapped.put(key, source);
        });

        assertTrue(unmapped.isEmpty(),
                DICTIONARY + " has no entry for icon key(s) " + unmapped
                        + "; every tool using them falls back to a generic wrench");
    }

    @Test
    void theScanFindsTheKeysItIsSupposedToFind() throws IOException {
        // Without this, a regex that silently matched nothing would make the
        // contract test above pass vacuously.
        var keys = iconKeysBySource().keySet();
        assertTrue(keys.size() >= 20, "expected the full icon vocabulary, found " + keys);
        assertTrue(keys.contains("brain"), "MemoryTool's key must be scanned: " + keys);
        assertTrue(keys.contains("printer"), "PrinterTool's key must be scanned: " + keys);
        assertTrue(keys.contains("wrench"), "the Tool interface default must be scanned: " + keys);
        assertTrue(keys.contains("plug"), "the MCP adapters' key must be scanned: " + keys);
        assertTrue(keys.contains("search"),
                "WebSearchTool returns a constant, not a literal, so constant resolution must work");
    }

    /** Icon key → the {@code app/}-relative file that returns it. */
    private static TreeMap<String, String> iconKeysBySource() throws IOException {
        var appRoot = repoFile("app");
        var keys = new TreeMap<String, String>();
        try (var tree = Files.walk(appRoot)) {
            for (var source : tree.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                var body = Files.readString(source);
                var matcher = ICON_RETURN.matcher(body);
                while (matcher.find()) {
                    var key = resolve(matcher.group(1).trim(), body);
                    if (key != null) keys.putIfAbsent(key, appRoot.relativize(source).toString());
                }
            }
        }
        return keys;
    }

    /**
     * Resolve a returned expression to the string it yields: either a literal, or
     * a constant declared in the same file (WebSearchTool returns ACTION_SEARCH).
     * Returns null for anything else, so an expression this test cannot evaluate
     * is skipped rather than asserted against as if it were a key.
     */
    private static String resolve(String expression, String enclosingSource) {
        if (expression.startsWith("\"")) {
            var literal = STRING_LITERAL.matcher(expression);
            return literal.find() ? literal.group(1) : null;
        }
        if (!expression.matches("[A-Za-z_][A-Za-z0-9_]*")) return null;
        var declaration = Pattern.compile(
                "\\b" + Pattern.quote(expression) + "\\s*=\\s*\"([^\"]*)\"").matcher(enclosingSource);
        return declaration.find() ? declaration.group(1) : null;
    }

    private static Path repoFile(String relative) {
        return Path.of(Play.applicationPath.getAbsolutePath(), relative);
    }
}
