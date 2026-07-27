import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.evals.EvalCase;
import services.evals.EvalCheck;
import services.evals.EvalScorer;

import java.util.List;

/**
 * Per-check scoring (JCLAW-875). Covers each deterministic check kind on both
 * verdicts, and the JSON Schema subset in the shapes structured output actually
 * breaks in: a stringified number, a missing key, an invented key, an off-vocabulary
 * enum value, and prose wrapped around the object.
 */
class EvalScorerTest extends UnitTest {

    private static EvalCase caseWith(EvalCheck... checks) {
        return new EvalCase("a-case", "input", "rubric", List.of(checks));
    }

    private static EvalScorer.Response text(String output) {
        return new EvalScorer.Response(output, List.of(), 1);
    }

    private static JsonObject schema(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    // ==================== Text checks ====================

    @Test
    void containsAllPassesRegardlessOfCasing() {
        var failures = EvalScorer.failures(
                caseWith(EvalCheck.of(EvalCheck.Kind.CONTAINS_ALL, List.of("Titan", "[S2]"))),
                text("titan, per [s2], is Saturn's largest moon"));

        assertTrue(failures.isEmpty(), () -> "expected a pass, got " + failures);
    }

    @Test
    void containsAllNamesEveryMissingNeedle() {
        var failures = EvalScorer.failures(
                caseWith(EvalCheck.of(EvalCheck.Kind.CONTAINS_ALL, List.of("209", "days"))),
                text("the transfer took 211 days"));

        assertEquals(1, failures.size(), () -> failures.toString());
        assertTrue(failures.getFirst().contains("missing \"209\""), failures.getFirst());
    }

    @Test
    void notContainsAnyFlagsThePlantedDistractor() {
        var failures = EvalScorer.failures(
                caseWith(EvalCheck.of(EvalCheck.Kind.NOT_CONTAINS_ANY, List.of("Canberra"))),
                text("The dataset records Canberra."));

        assertEquals(1, failures.size());
        assertTrue(failures.getFirst().startsWith("not_contains_any:"), failures.getFirst());
    }

    @Test
    void matchesUsesRegexSearchNotFullMatch() {
        var check = EvalCheck.of(EvalCheck.Kind.MATCHES, List.of("(?i)does not (state|mention)"));

        assertTrue(EvalScorer.failures(caseWith(check),
                text("The source does not state the launch mass.")).isEmpty());
        assertEquals(1, EvalScorer.failures(caseWith(check), text("It was 1,340 kg.")).size());
    }

    // ==================== Tool and call-budget checks ====================

    @Test
    void toolChecksReadTheCalledToolList() {
        var response = new EvalScorer.Response("done", List.of("web_search", "web_fetch"), 3);

        assertTrue(EvalScorer.failures(
                caseWith(EvalCheck.of(EvalCheck.Kind.TOOL_CALLED, List.of("web_search"))), response).isEmpty());

        var missing = EvalScorer.failures(
                caseWith(EvalCheck.of(EvalCheck.Kind.TOOL_CALLED, List.of("datetime"))), response);
        assertEquals(1, missing.size());
        assertTrue(missing.getFirst().contains("was not called"), missing.getFirst());

        var forbidden = EvalScorer.failures(
                caseWith(EvalCheck.of(EvalCheck.Kind.TOOL_NOT_CALLED, List.of("web_fetch"))), response);
        assertEquals(1, forbidden.size());
        assertTrue(forbidden.getFirst().contains("was called"), forbidden.getFirst());
    }

    @Test
    void maxLlmCallsFailsWhenTheTurnExceedsItsBudget() {
        // The epic's efficiency NFR (JCLAW-833) expressed as a dataset assertion:
        // quality that costs an extra model call has to declare the cost.
        var withinBudget = new EvalScorer.Response("51", List.of(), 1);
        var overBudget = new EvalScorer.Response("51", List.of(), 3);

        assertTrue(EvalScorer.failures(caseWith(EvalCheck.maxLlmCalls(1)), withinBudget).isEmpty());

        var failures = EvalScorer.failures(caseWith(EvalCheck.maxLlmCalls(1)), overBudget);
        assertEquals(1, failures.size());
        assertTrue(failures.getFirst().contains("used 3 calls, budget 1"), failures.getFirst());
    }

    // ==================== JSON Schema subset ====================

    private static final String INVOICE_SCHEMA = """
            {"type": "object", "required": ["invoiceNumber", "total", "paid"], "additionalProperties": false,
             "properties": {"invoiceNumber": {"type": "string"}, "total": {"type": "number"},
                            "paid": {"type": "boolean"}}}
            """;

    @Test
    void jsonSchemaAcceptsAConformingObject() {
        var failures = EvalScorer.failures(caseWith(EvalCheck.schema(schema(INVOICE_SCHEMA))),
                text("{\"invoiceNumber\": \"INV-2041\", \"total\": 128.5, \"paid\": true}"));

        assertTrue(failures.isEmpty(), () -> failures.toString());
    }

    @Test
    void jsonSchemaCatchesTheStringifiedNumber() {
        var failures = EvalScorer.failures(caseWith(EvalCheck.schema(schema(INVOICE_SCHEMA))),
                text("{\"invoiceNumber\": \"INV-2041\", \"total\": \"128.50\", \"paid\": true}"));

        assertEquals(1, failures.size(), () -> failures.toString());
        assertTrue(failures.getFirst().contains("$.total: expected number, got string"), failures.getFirst());
    }

    @Test
    void jsonSchemaReportsMissingAndUnexpectedProperties() {
        var failures = EvalScorer.failures(caseWith(EvalCheck.schema(schema(INVOICE_SCHEMA))),
                text("{\"invoiceNumber\": \"INV-2041\", \"total\": 128.5, \"currency\": \"USD\"}"));

        assertEquals(2, failures.size(), () -> failures.toString());
        assertTrue(failures.stream().anyMatch(f -> f.contains("missing required property 'paid'")), failures.toString());
        assertTrue(failures.stream().anyMatch(f -> f.contains("unexpected property 'currency'")), failures.toString());
    }

    @Test
    void jsonSchemaRejectsAnOffVocabularyEnumValue() {
        var enumSchema = schema("""
                {"type": "object", "required": ["sentiment"], "additionalProperties": false,
                 "properties": {"sentiment": {"type": "string", "enum": ["positive", "neutral", "negative"]}}}
                """);

        assertTrue(EvalScorer.failures(caseWith(EvalCheck.schema(enumSchema)),
                text("{\"sentiment\": \"negative\"}")).isEmpty());

        var failures = EvalScorer.failures(caseWith(EvalCheck.schema(enumSchema)),
                text("{\"sentiment\": \"mixed\"}"));
        assertEquals(1, failures.size());
        assertTrue(failures.getFirst().contains("is not one of"), failures.getFirst());
    }

    @Test
    void jsonSchemaWalksArrayItemsAndNamesTheIndex() {
        var arraySchema = schema("""
                {"type": "object", "required": ["planets"], "additionalProperties": false,
                 "properties": {"planets": {"type": "array", "items": {"type": "object",
                    "required": ["name", "rank"], "additionalProperties": false,
                    "properties": {"name": {"type": "string"}, "rank": {"type": "integer"}}}}}}
                """);

        var failures = EvalScorer.failures(caseWith(EvalCheck.schema(arraySchema)),
                text("{\"planets\": [{\"name\": \"Jupiter\", \"rank\": 1}, {\"name\": \"Saturn\", \"rank\": 2.5}]}"));

        assertEquals(1, failures.size(), () -> failures.toString());
        assertTrue(failures.getFirst().contains("$.planets[1].rank: expected integer"), failures.getFirst());
    }

    @Test
    void jsonSchemaFailsOnProseWrappedJson() {
        // Fence-stripping would make the check pass for a body no machine caller
        // can consume, which is why the scorer parses the response verbatim.
        var failures = EvalScorer.failures(caseWith(EvalCheck.schema(schema(INVOICE_SCHEMA))),
                text("Here is the JSON:\n```json\n{\"invoiceNumber\": \"INV-1\", \"total\": 1, \"paid\": true}\n```"));

        assertEquals(1, failures.size());
        assertTrue(failures.getFirst().contains("not valid JSON"), failures.getFirst());
    }

    @Test
    void everyFailingCheckIsReported() {
        var failures = EvalScorer.failures(
                caseWith(EvalCheck.of(EvalCheck.Kind.CONTAINS_ALL, List.of("51")),
                        EvalCheck.of(EvalCheck.Kind.TOOL_NOT_CALLED, List.of("exec")),
                        EvalCheck.maxLlmCalls(1)),
                new EvalScorer.Response("fifty-one", List.of("exec"), 2));

        assertEquals(3, failures.size(), () -> "one line per failed check: " + failures);
    }
}
