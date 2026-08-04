package memory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Facts just forgotten on operator request, so auto-capture does not immediately
 * re-learn them (JCLAW-919).
 *
 * <p>Without this, {@code forget} does not work at all. The turn that asks to forget
 * something necessarily states it — "forget what I told you about X" contains X — and
 * capture runs on that same turn, extracts X, and stores it again. Observed live: a
 * forget deleted the memory and capture recreated it eleven seconds later, under a new
 * id, with identical text.
 *
 * <p>Deliberately a short window rather than a permanent tombstone. "Forget that" means
 * drop what you know now, not refuse to learn it ever again; if the operator brings the
 * same fact up in a later conversation, re-learning it is correct. The window only has to
 * outlive the turn that requested the forget and any immediate follow-up about it.
 *
 * <p>In-memory and per-process: a restart clears it, which is harmless — the window is
 * minutes and the memory it was protecting is already deleted.
 */
public final class MemoryForgetLog {

    private MemoryForgetLog() {}

    /** Long enough to cover the requesting turn and a follow-up, short enough not to be a policy. */
    private static final Duration TTL = Duration.ofMinutes(10);

    private static final Cache<String, List<String>> FORGOTTEN = Caffeine.newBuilder()
            .expireAfterWrite(TTL)
            .maximumSize(1000)
            .build();

    /** Note that {@code text} was just forgotten for {@code agentId}. */
    public static void noteForgotten(String agentId, String text) {
        if (agentId == null || text == null || text.isBlank()) return;
        FORGOTTEN.get(agentId, _ -> new CopyOnWriteArrayList<>()).add(text);
    }

    /**
     * Whether {@code text} states something forgotten within the window, judged by the
     * same duplicate test capture dedups on — a re-extraction is rarely word-for-word.
     */
    public static boolean recentlyForgotten(String agentId, String text) {
        var entries = FORGOTTEN.getIfPresent(agentId);
        if (entries == null || entries.isEmpty() || text == null) return false;
        var probe = MemorySimilarity.Tokens.of(text);
        return entries.stream().anyMatch(f -> MemorySimilarity.isDuplicate(
                probe, MemorySimilarity.Tokens.of(f), 0.85, 0.82, 0.5));
    }

    /**
     * Drop any record matching {@code text}, so an explicit re-store takes effect
     * immediately. "Forget X" then "actually, remember X" has to work inside the window,
     * or the second instruction silently does nothing.
     */
    public static void clearMatching(String agentId, String text) {
        var entries = FORGOTTEN.getIfPresent(agentId);
        if (entries == null || text == null) return;
        var probe = MemorySimilarity.Tokens.of(text);
        entries.removeIf(f -> MemorySimilarity.isDuplicate(
                probe, MemorySimilarity.Tokens.of(f), 0.85, 0.82, 0.5));
    }

    /** play1 runs tests concurrently in one JVM, so a test that records must reset. */
    public static void clearForTest() {
        FORGOTTEN.invalidateAll();
    }
}
