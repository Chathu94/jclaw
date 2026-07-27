package services.evals;

import com.google.gson.JsonParseException;
import utils.GsonHolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs an {@link EvalSuite} against a responder and scores it (JCLAW-875).
 *
 * <p>Offline by construction: nothing here touches the request path, the database,
 * or the metrics store, so an eval sweep costs zero LLM calls on the serving path
 * and cannot skew the latency baselines JCLAW-833 measures against. The model
 * calls a sweep does spend are the responder's, and the suite can assert a budget
 * on them via {@link EvalCheck.Kind#MAX_LLM_CALLS}.
 *
 * <p>{@link Responder} is the seam. Today the shipped implementation replays
 * recorded responses (see {@link #main}); the agent-backed responder lands with
 * the critic work in JCLAW-836, which is the story that owns invoking a model.
 */
public final class EvalRunner {

    /** Produces the agent's answer for one case. Implementations may be slow and may throw. */
    @FunctionalInterface
    public interface Responder {
        EvalScorer.Response respond(EvalCase testCase) throws Exception;
    }

    private static final String DEFAULT_SUITE_DIR = "evals/suites";
    private static final String OPT_SUITES = "--suites";
    private static final String OPT_RESPONSES = "--responses";
    private static final String OPT_BASELINE = "--baseline";
    private static final String OPT_OUT = "--out";

    private EvalRunner() {}

    /**
     * Scores every case in {@code suite}, fanning out across virtual threads — the
     * cases are independent and each one blocks on a model, so the sweep costs about
     * as long as its slowest case rather than the sum. Results keep suite order so
     * two runs of the same suite diff line by line.
     */
    public static EvalReport run(EvalSuite suite, Responder responder) {
        List<Future<EvalReport.CaseResult>> futures;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            futures = suite.cases().stream()
                    .map(testCase -> pool.submit(() -> score(testCase, responder)))
                    .toList();
        }
        var results = new ArrayList<EvalReport.CaseResult>(futures.size());
        for (var future : futures) {
            try {
                results.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Eval run interrupted", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("Eval case failed outside scoring", e.getCause());
            }
        }
        return new EvalReport(suite.id(), suite.version(), results);
    }

    private static EvalReport.CaseResult score(EvalCase testCase, Responder responder) {
        var startNs = System.nanoTime();
        try {
            var response = responder.respond(testCase);
            var failures = EvalScorer.failures(testCase, response);
            return new EvalReport.CaseResult(testCase.id(), failures.isEmpty(), failures,
                    elapsedMs(startNs), response.llmCalls());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return failed(testCase, startNs, "responder interrupted");
        } catch (Exception e) {
            // A responder that throws on one case must not cost the sweep the other
            // cases' verdicts — a broken case is a failed case, not a failed run.
            return failed(testCase, startNs, "responder failed: " + e);
        }
    }

    private static EvalReport.CaseResult failed(EvalCase testCase, long startNs, String message) {
        return new EvalReport.CaseResult(testCase.id(), false, List.of(message), elapsedMs(startNs), 0);
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }

    /** The recorded-responses file: {@code {"suite":…, "version":…, "responses":{caseId: {…}}}}. */
    private record ResponseFile(String suite, int version, Map<String, EvalScorer.Response> responses) {}

    /**
     * CLI entry point — see {@code ./jclaw.sh evals} and {@code evals/README.md}.
     * With no {@code --responses} it validates the dataset and exits; with one it
     * scores the recorded run, optionally diffing against a baseline report.
     *
     * <p>Exit codes: 0 clean, 1 invalid dataset / failing case / regression,
     * 2 usage error. Output goes to stdout because it is a CLI result, not logging.
     */
    public static void main(String[] args) {
        Map<String, String> opts;
        try {
            opts = parseOptions(args);
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
            System.out.println("Usage: evals [--suites <dir>] [--responses <file>] [--baseline <file>] [--out <file>]");
            System.exit(2);
            return;
        }

        List<EvalSuite> suites;
        var dir = Path.of(opts.getOrDefault(OPT_SUITES, DEFAULT_SUITE_DIR));
        try {
            suites = EvalDatasetLoader.loadAll(dir);
        } catch (RuntimeException e) {
            System.out.println("Dataset invalid: " + e.getMessage());
            System.exit(1);
            return;
        }

        var responsesPath = opts.get(OPT_RESPONSES);
        if (responsesPath == null) {
            printDatasetSummary(suites);
            return;
        }
        int exit;
        try {
            exit = scoreRecordedRun(suites, opts, Path.of(responsesPath));
        } catch (IOException | JsonParseException e) {
            System.out.println("Cannot read the recorded run or its baseline: " + e);
            exit = 1;
        }
        System.exit(exit);
    }

    private static void printDatasetSummary(List<EvalSuite> suites) {
        var cases = 0;
        var checks = 0;
        for (var suite : suites) {
            var suiteChecks = suite.cases().stream().mapToInt(c -> c.checks().size()).sum();
            System.out.printf("%-28s %2d case(s), %3d check(s)%n",
                    suite.qualifiedId(), suite.cases().size(), suiteChecks);
            cases += suite.cases().size();
            checks += suiteChecks;
        }
        System.out.printf("%d suite(s), %d case(s), %d check(s) — all valid%n", suites.size(), cases, checks);
    }

    private static int scoreRecordedRun(List<EvalSuite> suites, Map<String, String> opts, Path responsesPath)
            throws IOException {
        var recorded = GsonHolder.GSON.fromJson(Files.readString(responsesPath), ResponseFile.class);
        if (recorded == null || recorded.suite() == null || recorded.responses() == null) {
            System.out.println(responsesPath + ": expected {\"suite\":…, \"version\":…, \"responses\":{…}}");
            return 1;
        }
        var suite = suites.stream()
                .filter(s -> s.id().equals(recorded.suite()) && s.version() == recorded.version())
                .findFirst()
                .orElse(null);
        if (suite == null) {
            System.out.println("No suite " + recorded.suite() + ".v" + recorded.version() + " in the dataset");
            return 1;
        }

        // Missing recordings surface through the same path as a broken responder,
        // so a partial run reads as failing cases rather than a silently short suite.
        var report = run(suite, testCase -> {
            var response = recorded.responses().get(testCase.id());
            if (response == null) throw new IllegalStateException("no response recorded");
            return response;
        });
        System.out.println(report.summary());

        var out = opts.get(OPT_OUT);
        if (out != null) {
            var outPath = Path.of(out);
            if (outPath.getParent() != null) Files.createDirectories(outPath.getParent());
            Files.writeString(outPath, report.toJson());
            System.out.println("Report written to " + out);
        }

        var exit = report.passed() == report.results().size() ? 0 : 1;
        var baseline = opts.get(OPT_BASELINE);
        if (baseline != null) {
            var regressions = report.regressionsAgainst(EvalReport.fromJson(Files.readString(Path.of(baseline))));
            if (!regressions.isEmpty()) {
                System.out.println("Regressions against " + baseline + ": " + String.join(", ", regressions));
                exit = 1;
            }
        }
        return exit;
    }

    private static Map<String, String> parseOptions(String[] args) {
        var known = List.of(OPT_SUITES, OPT_RESPONSES, OPT_BASELINE, OPT_OUT);
        var opts = new HashMap<String, String>();
        for (var i = 0; i < args.length; i += 2) {
            if (!known.contains(args[i])) throw new IllegalArgumentException("Unknown option: " + args[i]);
            if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + args[i]);
            opts.put(args[i], args[i + 1]);
        }
        if (opts.containsKey(OPT_BASELINE) && !opts.containsKey(OPT_RESPONSES)) {
            throw new IllegalArgumentException("--baseline needs --responses (there is nothing to compare otherwise)");
        }
        return opts;
    }
}
