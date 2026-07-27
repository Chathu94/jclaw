package services.evals;

import java.util.List;

/**
 * One eval case: a user turn, why it is in the suite, and the checks its response
 * must satisfy (JCLAW-875).
 *
 * <p>{@code id} is the identity reports and regression comparisons key on, so
 * renaming a case discards its history — add a case rather than rename one.
 * {@code rubric} is the human-readable pass criterion; the {@code checks} are the
 * machine-readable one, and the loader requires both so a case cannot enter the
 * suite without stating what it is for.
 */
public record EvalCase(String id, String input, String rubric, List<EvalCheck> checks) {

    public EvalCase {
        checks = checks == null ? List.of() : List.copyOf(checks);
    }
}
