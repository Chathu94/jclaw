import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.evals.EvalCheck;
import services.evals.EvalDatasetLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Loader validation for the eval dataset format (JCLAW-875). Each test writes one
 * suite file into its own temp directory and asserts the loader either models it
 * exactly or refuses it by name — a dataset defect must surface here, not as a
 * check that silently scores nothing at run time.
 *
 * <p>Every test gets a fresh directory because the play1 engine runs test methods
 * concurrently; a shared fixture directory would have them deleting each other's
 * files.
 */
class EvalDatasetLoaderTest extends UnitTest {

    private static Path root;

    private static final String VALID = """
            {
              "id": "sample",
              "version": 1,
              "description": "A sample suite",
              "cases": [
                {
                  "id": "greets-back",
                  "input": "hello",
                  "rubric": "the agent answers rather than calling a tool",
                  "checks": [
                    {"kind": "contains_all", "args": ["hello", "there"]},
                    {"kind": "tool_not_called", "args": ["web_search"]},
                    {"kind": "max_llm_calls", "limit": 1}
                  ]
                }
              ]
            }
            """;

    @BeforeAll
    static void makeRoot() throws Exception {
        root = Files.createTempDirectory("eval-suites");
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (root != null && Files.exists(root)) {
            try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception _) {
                        // best-effort temp cleanup
                    }
                });
            }
        }
    }

    /** Writes one suite file into a fresh directory and returns its path. */
    private static Path write(String fileName, String body) throws Exception {
        var dir = Files.createTempDirectory(root, "case");
        var file = dir.resolve(fileName);
        Files.writeString(file, body);
        return file;
    }

    /** The loader's refusal message for a suite it should reject, or {@code null} if it accepted it. */
    private static String refusal(String fileName, String body) throws Exception {
        try {
            EvalDatasetLoader.loadSuite(write(fileName, body));
            return null;
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    // ==================== Happy path ====================

    @Test
    void loadsSuiteWithEveryFieldMapped() throws Exception {
        var suite = EvalDatasetLoader.loadSuite(write("sample.v1.json", VALID));

        assertEquals("sample", suite.id());
        assertEquals(1, suite.version());
        assertEquals("sample.v1", suite.qualifiedId());
        assertEquals(1, suite.cases().size());
        var evalCase = suite.cases().getFirst();
        assertEquals("greets-back", evalCase.id());
        assertEquals("hello", evalCase.input());
        assertEquals("the agent answers rather than calling a tool", evalCase.rubric());
        assertEquals(3, evalCase.checks().size());
        assertEquals(EvalCheck.Kind.CONTAINS_ALL, evalCase.checks().get(0).kind());
        assertEquals(2, evalCase.checks().get(0).args().size());
        assertEquals("web_search", evalCase.checks().get(1).arg());
        assertEquals(1, evalCase.checks().get(2).limit());
    }

    @Test
    void loadAllReadsEverySuiteInFilenameOrder() throws Exception {
        var dir = Files.createTempDirectory(root, "all");
        Files.writeString(dir.resolve("sample.v1.json"), VALID);
        Files.writeString(dir.resolve("beta.v2.json"),
                VALID.replace("\"sample\"", "\"beta\"").replace("\"version\": 1", "\"version\": 2"));

        var suites = EvalDatasetLoader.loadAll(dir);

        assertEquals(2, suites.size());
        assertEquals("beta", suites.get(0).id(), "sorted by filename so report order is stable");
        assertEquals("sample", suites.get(1).id());
    }

    // ==================== Rejections ====================

    @Test
    void rejectsFilenameThatDoesNotCarryTheVersion() throws Exception {
        var msg = refusal("sample.json", VALID);
        assertNotNull(msg, "a suite file without a version in its name must be refused");
        assertTrue(msg.contains("<id>.v<version>.json"), msg);
    }

    @Test
    void rejectsVersionThatDisagreesWithTheFilename() throws Exception {
        // The filename is what stops a published suite being edited in place, so a
        // body/name mismatch is a hard failure rather than a preference.
        var msg = refusal("sample.v2.json", VALID);
        assertNotNull(msg);
        assertTrue(msg.contains("does not match the filename"), msg);
    }

    @Test
    void rejectsDuplicateCaseIds() throws Exception {
        var body = VALID.replace("\"cases\": [",
                """
                "cases": [
                    {"id": "greets-back", "input": "hi", "rubric": "a second case reusing the id",
                     "checks": [{"kind": "contains_all", "args": ["hi"]}]},""");
        var msg = refusal("sample.v1.json", body);
        assertNotNull(msg);
        assertTrue(msg.contains("duplicate case id"), msg);
    }

    @Test
    void rejectsUnknownCheckKind() throws Exception {
        var msg = refusal("sample.v1.json", VALID.replace("\"contains_all\"", "\"vibes_check\""));
        assertNotNull(msg);
        assertTrue(msg.contains("unknown check kind 'vibes_check'"), msg);
    }

    @Test
    void rejectsUnknownKeyRatherThanIgnoringIt() throws Exception {
        var msg = refusal("sample.v1.json", VALID.replace("\"rubric\":", "\"expectd\": \"typo\", \"rubric\":"));
        assertNotNull(msg);
        assertTrue(msg.contains("unknown key 'expectd'"), msg);
    }

    @Test
    void rejectsCheckKindWithTheWrongArgCount() throws Exception {
        var msg = refusal("sample.v1.json", VALID.replace(
                "{\"kind\": \"tool_not_called\", \"args\": [\"web_search\"]}",
                "{\"kind\": \"tool_not_called\", \"args\": [\"web_search\", \"exec\"]}"));
        assertNotNull(msg);
        assertTrue(msg.contains("expected 1 arg(s), got 2"), msg);
    }

    @Test
    void rejectsInvalidRegex() throws Exception {
        var msg = refusal("sample.v1.json", VALID.replace(
                "{\"kind\": \"contains_all\", \"args\": [\"hello\", \"there\"]}",
                "{\"kind\": \"matches\", \"args\": [\"([unclosed\"]}"));
        assertNotNull(msg);
        assertTrue(msg.contains("invalid regex"), msg);
    }

    @Test
    void rejectsSchemaKeywordTheScorerDoesNotImplement() throws Exception {
        // The whole point of the load-time gate: an unimplemented keyword would let
        // invalid structured output through while the report claimed a pass.
        var msg = refusal("sample.v1.json", VALID.replace(
                "{\"kind\": \"contains_all\", \"args\": [\"hello\", \"there\"]}",
                "{\"kind\": \"json_schema\", \"schema\": {\"type\": \"object\", \"minProperties\": 2}}"));
        assertNotNull(msg);
        assertTrue(msg.contains("unsupported keyword 'minProperties'"), msg);
    }

    @Test
    void rejectsUnsupportedKeywordNestedInsideAProperty() throws Exception {
        var msg = refusal("sample.v1.json", VALID.replace(
                "{\"kind\": \"contains_all\", \"args\": [\"hello\", \"there\"]}",
                "{\"kind\": \"json_schema\", \"schema\": {\"type\": \"object\", "
                        + "\"properties\": {\"total\": {\"type\": \"number\", \"minimum\": 0}}}}"));
        assertNotNull(msg);
        assertTrue(msg.contains("unsupported keyword 'minimum'"), msg);
        assertTrue(msg.contains("schema.total"), "the message should name the offending path: " + msg);
    }

    @Test
    void rejectsCaseWithNoChecks() throws Exception {
        var body = """
                {
                  "id": "sample", "version": 1, "description": "A sample suite",
                  "cases": [{"id": "empty", "input": "hi", "rubric": "nothing asserted", "checks": []}]
                }
                """;
        var msg = refusal("sample.v1.json", body);
        assertNotNull(msg);
        assertTrue(msg.contains("'checks' must be a non-empty array"), msg);
    }

    @Test
    void rejectsBlankRubric() throws Exception {
        var msg = refusal("sample.v1.json",
                VALID.replace("\"the agent answers rather than calling a tool\"", "\"  \""));
        assertNotNull(msg);
        assertTrue(msg.contains("'rubric' must be a non-blank string"), msg);
    }
}
