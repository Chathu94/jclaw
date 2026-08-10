package memory;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Whether a captured memory states an <em>identity</em> fact — who the operator is, who
 * and what they are attached to (JCLAW-529).
 *
 * <p>These are the facts a question reaches for by relation rather than by name ("what do
 * my kids go by?", "where do I live?"), and they are asked often enough that leaving them
 * to retrieval is the wrong trade: measured on a live corpus, the memory holding two
 * children's nicknames scored 0.588 cosine against "what do I call my children?" — below
 * the recall floor, and below an unrelated memory stating the operator's own name. The
 * always-loaded core tier exists for exactly this class, and until JCLAW-529 auto-capture
 * could not put anything in it ({@link MemoryCategory#coerceForCapture} demotes core), so
 * the tier sat empty while the facts it was built for competed for vector-search slots.
 *
 * <p><b>Deterministic, and deliberately not the extractor's opinion.</b> Core memories
 * render above the prompt-cache boundary, so each admission invalidates the cacheable
 * prefix for that agent — the cost JCLAW-981 was protecting against. A model that returns
 * categories outside the closed set it was given (JCLAW-927) is not a safe gate for a
 * tier with that price. The patterns below are a closed list that can be read and argued
 * with; widening it is a code change, not a prompt change.
 *
 * <p>Matches the third-person "The user ..." form {@code EXTRACTION_INSTRUCTIONS} mandates,
 * so a memory written any other way simply does not qualify — failing closed, which for a
 * capped always-loaded tier is the right direction.
 */
public final class MemoryIdentityClass {

    private MemoryIdentityClass() {}

    /** Kinship and attachment nouns that make "the user's X" an identity fact. */
    private static final String RELATIONS =
            "son|sons|daughter|daughters|child|children|kid|kids|wife|husband|spouse|partner"
            + "|mother|father|parents|brother|brothers|sister|sisters|employer|company|manager";

    private static final List<Pattern> IDENTITY = List.of(
            // "The user's son Kheshav ...", "The user's wife is named Renu."
            Pattern.compile("\\bthe user's (" + RELATIONS + ")\\b"),
            // "The user has two sons named ...", "The user has a daughter called ..."
            Pattern.compile("\\bthe user has (a|an|one|two|three|four|\\d+) (" + RELATIONS + ")\\b"),
            Pattern.compile("\\bthe user's name is\\b"),
            Pattern.compile("\\bthe user is named\\b"),
            Pattern.compile("\\bthe user (lives|resides) in\\b"),
            Pattern.compile("\\bthe user is based in\\b"),
            Pattern.compile("\\bthe user's (home|address|timezone|time zone|birthday)\\b"),
            Pattern.compile("\\bthe user works (at|for)\\b"),
            Pattern.compile("\\bthe user was born\\b"),
            Pattern.compile("\\bthe user is allergic to\\b"));

    /** Whether {@code text} states an identity fact, and so belongs in the core tier. */
    public static boolean isIdentity(String text) {
        if (text == null || text.isBlank()) return false;
        var lower = text.toLowerCase(Locale.ROOT);
        return IDENTITY.stream().anyMatch(p -> p.matcher(lower).find());
    }
}
