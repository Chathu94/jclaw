package agents;

import llm.LlmTypes.ChatMessage;
import models.MessageRole;
import services.TimezoneResolver;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Owns the "current date and time" block and splices it into the outgoing
 * message list at send time.
 *
 * <p>The block used to live in the system prompt, below
 * {@link SystemPromptAssembler}'s cache boundary. That boundary is real for the
 * system message itself, but the system message is element 0 of the chat array —
 * so a value that changes every minute still sat ahead of the entire
 * conversation history in the token stream, and an LLM prefix cache had to
 * re-process all of that history whenever the minute ticked over.
 *
 * <p>Verified on prompts captured off the shipped path. With the clock in the
 * system message the cacheable prefix is pinned at a constant length — it can
 * never extend past the clock's position — so the re-processed region grows
 * every turn (247, 300, 351, 405 chars over four turns). With the clock on the
 * last user message the cacheable prefix grows with the conversation and the
 * re-processed tail stays constant (~346 chars). Flat versus linear is the
 * verified property; the absolute numbers are small only because those captured
 * turns carried very short messages.
 *
 * <p>A synthetic harness against a local 7B put the same effect in milliseconds
 * (+334 ms/turn with the clock in the system message versus +12 ms/turn on the
 * last user message, 5 reps, per-turn medians). Treat that as illustrative
 * rather than a promise: it used simulated prompts on a contended machine, and
 * the realized win depends on message sizes and how often turns cross a minute
 * boundary. Both llama.cpp and MLX were separately confirmed to reuse a stable
 * prefix incrementally, which is the engine behavior this relies on.
 *
 * <h2>Call-site contract</h2>
 * <p>Every path that finalizes a message list for an LLM call MUST end with
 * {@link #inject}, or that path's model sees no clock at all. There are three,
 * and it has to be the <em>last</em> step in each — compaction, context trim,
 * and the media rewrites all rebuild the list and would drop an earlier splice:
 * <ul>
 *   <li>{@code AgentPromptPreparer.applyMediaRewrite} — streaming chat</li>
 *   <li>{@code AgentPromptPreparer.rewriteSyncMedia} — synchronous chat</li>
 *   <li>{@code AgentRunner} task-fire, which builds its own two-message list</li>
 * </ul>
 *
 * <p>The block is deliberately <b>not</b> persisted. The stored user message
 * keeps its bare text, so the prefix diverges only at the previous user turn —
 * roughly three messages of re-processing, constant regardless of depth.
 * Persisting it instead would pin the prefix one message later (cheaper still:
 * ~582 ms by turn 6) but leave one stale clock per turn in the transcript,
 * costing ~80 tokens/turn of permanent context and leaving the model to read a
 * pile of contradictory "Now:" statements that all claim to be current.
 */
public final class CurrentTimeInjector {

    private CurrentTimeInjector() {}

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd HH:mm (xxx)");

    /** Section heading, shared with the tests that assert placement. */
    public static final String HEADING = "## Current Date and Time";

    /**
     * The live clock block in the operator's configured zone
     * ({@link TimezoneResolver#appZone()}), captured fresh on every call so the
     * model always sees real wall-clock time instead of guessing from its
     * training cutoff.
     */
    public static String block() {
        var zone = TimezoneResolver.appZone();
        var now = ZonedDateTime.now(zone);
        return "\n" + HEADING + "\n"
                + "- Now: %s\n".formatted(now.format(FORMAT))
                + "- Timezone: %s\n".formatted(zone.getId())
                + "- This is the live wall-clock time captured when this prompt was built. "
                + "Treat it as the current date and time; do not guess or rely on "
                + "training-cutoff assumptions.\n";
    }

    /**
     * Return a copy of {@code messages} with the clock block prepended to the
     * last user message. Returns the input unchanged when there is no user
     * message to attach to — a tool-only or system-only list has nowhere
     * sensible to put it, and inventing a message would change the shape
     * providers expect.
     */
    public static List<ChatMessage> inject(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return messages;

        int idx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (MessageRole.USER.value.equals(messages.get(i).role())) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return messages;

        var target = messages.get(idx);
        var merged = prepend(target.content());
        if (merged == null) return messages;

        var out = new ArrayList<>(messages);
        out.set(idx, new ChatMessage(target.role(), merged, target.toolCalls(),
                target.toolCallId(), target.toolName()));
        return out;
    }

    /**
     * Text turns carry a String; multimodal turns carry a List of OpenAI-style
     * content parts (see {@link VisionAudioAssembler}). Both are handled.
     * Anything else returns null so the caller leaves the message untouched
     * rather than guessing at an unknown shape.
     */
    private static Object prepend(Object content) {
        if (content instanceof String s) return block() + "\n" + s;
        if (content instanceof List<?> parts) {
            var out = new ArrayList<Object>();
            out.add(Map.of("type", "text", "text", block()));
            out.addAll(parts);
            return out;
        }
        return null;
    }
}
