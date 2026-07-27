package services.evals;

import com.google.gson.Gson;
import utils.GsonHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The outcome of running one {@link EvalSuite}: per-case verdicts plus the three
 * numbers the epic is judged on — pass rate, per-case latency, and LLM calls spent
 * (JCLAW-875, JCLAW-833).
 *
 * <p>Latency is measured with a plain {@code nanoTime} span in {@link EvalRunner}
 * rather than {@link utils.LatencyTrace}: that instrumentation persists samples
 * into the request-path histograms behind the Chat Performance dashboard, and an
 * offline eval sweep firing hundreds of turns would skew the very baseline the
 * epic compares against.
 *
 * <p>Serialised with Gson's record support so a run can be written to disk and fed
 * back as the baseline of a later run — which is what makes regression detection
 * mean anything across commits.
 */
public record EvalReport(String suiteId, int version, List<CaseResult> results) {

    /** One case's verdict. {@code failures} is empty exactly when {@code passed}. */
    public record CaseResult(String caseId, boolean passed, List<String> failures, long latencyMs, int llmCalls) {

        public CaseResult {
            failures = failures == null ? List.of() : List.copyOf(failures);
        }
    }

    // Derived from the app's shared serializer so the report inherits its contract
    // (serializeNulls, Instant adapter, no HTML escaping); pretty-printed on top
    // because a report file is read by people and diffed between runs.
    private static final Gson GSON = GsonHolder.GSON.newBuilder().setPrettyPrinting().create();

    public EvalReport {
        results = results == null ? List.of() : List.copyOf(results);
    }

    /** Fraction of cases that passed, 0.0–1.0. An empty suite scores 0 rather than a vacuous 1. */
    public double passRate() {
        if (results.isEmpty()) return 0.0;
        return (double) results.stream().filter(CaseResult::passed).count() / results.size();
    }

    public int passed() {
        return (int) results.stream().filter(CaseResult::passed).count();
    }

    /** Model calls spent across the whole suite — the epic's call-budget number (JCLAW-833). */
    public int totalLlmCalls() {
        return results.stream().mapToInt(CaseResult::llmCalls).sum();
    }

    /**
     * Case ids that passed in {@code baseline} and fail here. Cases absent from the
     * baseline are not regressions — a new case has no history to regress from, and
     * counting it as one would make every suite addition look like a break.
     */
    public List<String> regressionsAgainst(EvalReport baseline) {
        Map<String, Boolean> before = baseline.results().stream()
                .collect(Collectors.toMap(CaseResult::caseId, CaseResult::passed, (a, b) -> a));
        return results.stream()
                .filter(r -> !r.passed() && Boolean.TRUE.equals(before.get(r.caseId())))
                .map(CaseResult::caseId)
                .toList();
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static EvalReport fromJson(String json) {
        return GSON.fromJson(json, EvalReport.class);
    }

    /** One line per case plus a totals line, for the CLI. */
    public String summary() {
        var out = new ArrayList<String>();
        out.add(suiteId + ".v" + version);
        for (var r : results) {
            out.add(String.format(Locale.ROOT, "  %-4s %-40s %5d ms  %d call(s)",
                    r.passed() ? "PASS" : "FAIL", r.caseId(), r.latencyMs(), r.llmCalls()));
            r.failures().forEach(f -> out.add("         " + f));
        }
        out.add(String.format(Locale.ROOT, "  %d/%d passed (%.0f%%), %d LLM call(s) total",
                passed(), results.size(), passRate() * 100, totalLlmCalls()));
        return String.join("\n", out);
    }
}
