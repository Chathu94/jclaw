package services.evals;

import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One assertion about an agent response inside an {@link EvalCase} (JCLAW-875).
 *
 * <p>Every kind here is decidable from the response alone — no judge model. That
 * is deliberate: JCLAW-833 gates this epic on LLM-call count, so a ruler that
 * spent a call per assertion would tax the very thing it measures, and would make
 * eval results depend on the model being evaluated. Model-judged checks (semantic
 * equivalence, free-form rubric grading) arrive with the critic in JCLAW-836.
 *
 * <p>Which field carries the payload depends on {@link Kind}; {@link
 * EvalDatasetLoader} is the only place that maps wire JSON onto them, and it
 * rejects a check whose payload does not fit its kind.
 */
public record EvalCheck(Kind kind, List<String> args, @Nullable JsonObject schema, int limit) {

    /** Wire {@code kind} values, lowercased ({@code CONTAINS_ALL} → {@code contains_all}). */
    public enum Kind {
        /** Every arg appears in the response text (case-insensitive). */
        CONTAINS_ALL,
        /** No arg appears in the response text (case-insensitive). */
        NOT_CONTAINS_ANY,
        /** The single arg, as a regex, finds a match in the response text. */
        MATCHES,
        /** The response text parses as JSON and satisfies {@link #schema()}. */
        JSON_SCHEMA,
        /** The single arg names a tool the agent called. */
        TOOL_CALLED,
        /** The single arg names a tool the agent did not call. */
        TOOL_NOT_CALLED,
        /** The turn used at most {@link #limit()} model calls (JCLAW-833's NFR). */
        MAX_LLM_CALLS;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Optional<Kind> fromWire(String wire) {
            for (var k : values()) {
                if (k.wire().equals(wire)) return Optional.of(k);
            }
            return Optional.empty();
        }
    }

    public EvalCheck {
        args = args == null ? List.of() : List.copyOf(args);
    }

    /** A check whose payload is string arguments — the contains/matches/tool kinds. */
    public static EvalCheck of(Kind kind, List<String> args) {
        return new EvalCheck(kind, args, null, 0);
    }

    /** A {@link Kind#JSON_SCHEMA} check over the response's JSON body. */
    public static EvalCheck schema(JsonObject schema) {
        return new EvalCheck(Kind.JSON_SCHEMA, List.of(), schema, 0);
    }

    /** A {@link Kind#MAX_LLM_CALLS} budget check. */
    public static EvalCheck maxLlmCalls(int limit) {
        return new EvalCheck(Kind.MAX_LLM_CALLS, List.of(), null, limit);
    }

    /** The single argument of a one-arg kind (matches, tool_called, tool_not_called). */
    public String arg() {
        return args.getFirst();
    }
}
