package services.evals;

import java.util.List;

/**
 * A versioned set of {@link EvalCase}s loaded from one {@code evals/suites/<id>.v<version>.json}
 * file (JCLAW-875).
 *
 * <p>The version lives in the filename as well as the body because a suite is a
 * measuring stick: editing published cases in place silently redefines what a
 * pass rate means, so a changed suite ships as a new file beside the old one and
 * the two sets of numbers stay comparable. {@link EvalDatasetLoader} enforces
 * that the two agree.
 */
public record EvalSuite(String id, int version, String description, List<EvalCase> cases) {

    public EvalSuite {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    /** {@code <id>.v<version>} — the form used in reports and in filenames. */
    public String qualifiedId() {
        return id + ".v" + version;
    }
}
