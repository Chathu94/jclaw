package services.evals;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * A set of {@link EvalCase}s loaded from one {@code evals/suites/<id>.json} file
 * (JCLAW-875).
 *
 * <p>A suite is a measuring stick, so a run's numbers only mean something next to
 * another run scored by the same stick. That comparability used to be asserted by
 * hand — a version in the filename and the body, bumped when cases changed
 * (JCLAW-883 removed it). The scheme protected against the author who remembered
 * and was blind to the one who did not: nothing detected an edit made in place
 * under an unchanged version, which is the failure that actually happens.
 *
 * <p>{@link #fingerprint()} replaces it. Git already holds the history and shows
 * which cases changed; the fingerprint supplies the part git cannot, which is
 * telling two <em>runs</em> apart at scoring time.
 */
public record EvalSuite(String id, String description, List<String> requiredTools, List<EvalCase> cases) {

    /**
     * ASCII unit separator between canonical fields. A character that cannot occur
     * in a case id, an input or a check arg, so no two different suites can render
     * to the same canonical string by shifting where one field ends and the next
     * begins.
     */
    private static final char FIELD_SEP = (char) 0x1F;

    public EvalSuite {
        cases = cases == null ? List.of() : List.copyOf(cases);
        requiredTools = requiredTools == null ? List.of() : List.copyOf(requiredTools);
    }

    /** Convenience for suites that need no tools — grounding and structured-output. */
    public EvalSuite(String id, String description, List<EvalCase> cases) {
        this(id, description, List.of(), cases);
    }

    /**
     * Short content hash over everything that decides how this suite scores a run:
     * each case's id and input, and each check's kind, args, schema and limit, in
     * order.
     *
     * <p>Deliberately excludes {@link #description} and {@link EvalCase#rubric()}.
     * Both are prose explaining why a case exists, and neither changes a verdict —
     * so sharpening the wording of a rubric must not invalidate a baseline. A
     * fingerprint that moved on cosmetic edits would produce warnings people learn
     * to click past, which is worse than no warning at all.
     *
     * <p>Inputs are included even though they only affect capture, not scoring: a
     * recording made from different questions is not comparable to one made from
     * these, however the checks are written.
     */
    public String fingerprint() {
        var canonical = new StringBuilder(id);
        // Part of the canonical form: granting a suite a different tool set changes
        // what its pass rate measures just as surely as editing a check does.
        canonical.append(FIELD_SEP).append(requiredTools);
        for (var testCase : cases) {
            canonical.append(FIELD_SEP).append(testCase.id())
                    .append(FIELD_SEP).append(testCase.input());
            for (var check : testCase.checks()) {
                canonical.append(FIELD_SEP).append(check.kind().wire())
                        .append(FIELD_SEP).append(check.args())
                        .append(FIELD_SEP).append(check.schema())
                        .append(FIELD_SEP).append(check.limit());
            }
        }
        return sha256Prefix(canonical.toString());
    }

    /** {@code <id>@<fingerprint>} — how a report names exactly which stick it used. */
    public String qualifiedId() {
        return id + "@" + fingerprint();
    }

    /**
     * First 12 hex chars of the SHA-256. Long enough that two suites colliding is not
     * a practical concern, short enough to read in a CLI summary and a report diff.
     */
    private static String sha256Prefix(String canonical) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable — JDK install broken?", e);
        }
    }
}
