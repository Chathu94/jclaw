import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.evals.EvalCase;
import services.evals.EvalCheck;
import services.evals.EvalReport;
import services.evals.EvalRunner;
import services.evals.EvalScorer;
import services.evals.EvalSuite;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Runner and report behaviour (JCLAW-875): fan-out, per-case latency and call
 * counts, failure isolation, and the JSON round-trip that lets one run become the
 * next run's regression baseline.
 */
class EvalRunnerTest extends UnitTest {

    private static EvalCase caseSaying(String id, String expected) {
        return new EvalCase(id, "say " + expected, "the agent repeats the token",
                List.of(EvalCheck.of(EvalCheck.Kind.CONTAINS_ALL, List.of(expected))));
    }

    private static final EvalSuite SUITE = new EvalSuite("sample", 1, "fixture",
            List.of(caseSaying("alpha", "alpha"), caseSaying("beta", "beta")));

    /** Scores {@link #SUITE} against recorded responses — the shape a replayed run has. */
    private static EvalReport runWith(Map<String, EvalScorer.Response> responses) {
        return EvalRunner.run(SUITE, testCase -> {
            var response = responses.get(testCase.id());
            if (response == null) throw new IllegalStateException("no response recorded");
            return response;
        });
    }

    // ==================== Scoring a run ====================

    @Test
    void reportKeepsSuiteOrderAndCountsCalls() {
        var report = runWith(Map.of(
                "alpha", new EvalScorer.Response("alpha", List.of(), 1),
                "beta", new EvalScorer.Response("beta", List.of(), 2)));

        assertEquals("sample", report.suiteId());
        assertEquals(1, report.version());
        assertEquals(List.of("alpha", "beta"),
                report.results().stream().map(EvalReport.CaseResult::caseId).toList());
        assertEquals(1.0, report.passRate(), 0.0001);
        assertEquals(3, report.totalLlmCalls(), "the sweep's model-call cost is part of the report");
    }

    @Test
    void failingCaseCarriesItsFailureText() {
        var report = runWith(Map.of(
                "alpha", new EvalScorer.Response("alpha", List.of(), 1),
                "beta", new EvalScorer.Response("something else", List.of(), 1)));

        assertEquals(0.5, report.passRate(), 0.0001);
        var beta = report.results().get(1);
        assertFalse(beta.passed());
        assertEquals(1, beta.failures().size());
        assertTrue(beta.failures().getFirst().contains("missing \"beta\""), beta.failures().toString());
    }

    @Test
    void responderFailureCostsOneCaseNotTheRun() {
        // A missing recording (or a responder that throws) must read as a failed
        // case; losing the other verdicts would make a partial sweep unreadable.
        var report = runWith(Map.of("alpha", new EvalScorer.Response("alpha", List.of(), 1)));

        assertEquals(2, report.results().size());
        assertTrue(report.results().get(0).passed());
        assertFalse(report.results().get(1).passed());
        assertTrue(report.results().get(1).failures().getFirst().contains("no response recorded"),
                report.results().get(1).failures().toString());
    }

    @Test
    void casesRunConcurrently() {
        // Eight cases blocking 200 ms each: serial execution needs 1.6 s, so a run
        // under 800 ms can only come from the fan-out.
        var cases = IntStream.range(0, 8)
                .mapToObj(i -> caseSaying("case-" + i, "ok"))
                .toList();
        var suite = new EvalSuite("slow", 1, "fixture", cases);

        var startNs = System.nanoTime();
        var report = EvalRunner.run(suite, testCase -> {
            Thread.sleep(200);
            return new EvalScorer.Response("ok", List.of(), 1);
        });
        var elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

        assertEquals(8, report.results().size());
        assertEquals(1.0, report.passRate(), 0.0001);
        assertTrue(elapsedMs < 800, "expected concurrent execution, took " + elapsedMs + " ms");
        assertTrue(report.results().getFirst().latencyMs() >= 150,
                "per-case latency should reflect the responder, got " + report.results().getFirst().latencyMs());
    }

    // ==================== Report persistence and regressions ====================

    @Test
    void reportSurvivesAJsonRoundTrip() {
        var report = runWith(Map.of(
                "alpha", new EvalScorer.Response("alpha", List.of(), 1),
                "beta", new EvalScorer.Response("nope", List.of(), 4)));

        var restored = EvalReport.fromJson(report.toJson());

        assertEquals(report.suiteId(), restored.suiteId());
        assertEquals(report.version(), restored.version());
        assertEquals(report.passRate(), restored.passRate(), 0.0001);
        assertEquals(report.totalLlmCalls(), restored.totalLlmCalls());
        assertEquals(report.results().get(1).failures(), restored.results().get(1).failures());
    }

    @Test
    void regressionsAreOnlyCasesThatUsedToPass() {
        var baseline = runWith(Map.of(
                "alpha", new EvalScorer.Response("alpha", List.of(), 1),
                "beta", new EvalScorer.Response("nope", List.of(), 1)));
        var current = runWith(Map.of(
                "alpha", new EvalScorer.Response("nope", List.of(), 1),
                "beta", new EvalScorer.Response("nope", List.of(), 1)));

        assertEquals(List.of("alpha"), current.regressionsAgainst(baseline),
                "beta was already failing, so it is a known gap rather than a regression");
    }

    @Test
    void newCasesAreNotRegressions() {
        var baseline = new EvalReport("sample", 1,
                List.of(new EvalReport.CaseResult("alpha", true, List.of(), 1, 1)));
        var current = runWith(Map.of("alpha", new EvalScorer.Response("alpha", List.of(), 1)));

        assertTrue(current.regressionsAgainst(baseline).isEmpty(),
                "beta has no history in the baseline, so its failure is not a regression");
    }

    @Test
    void summaryNamesEveryFailedCheck() {
        var report = runWith(Map.of(
                "alpha", new EvalScorer.Response("alpha", List.of(), 1),
                "beta", new EvalScorer.Response("nope", List.of(), 1)));

        var summary = report.summary();

        assertTrue(summary.contains("sample.v1"), summary);
        assertTrue(summary.contains("FAIL beta"), summary);
        assertTrue(summary.contains("missing \"beta\""), summary);
        assertTrue(summary.contains("1/2 passed (50%)"), summary);
    }
}
