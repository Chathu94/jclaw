import memory.MemorySimilarity;
import memory.MemorySimilarity.Tokens;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/**
 * JCLAW-922: the capture-time duplicate rule. Fixtures are real pairs taken from a
 * 1248-row production store — the eight that a Jaccard-only NOOP let through, and
 * the two shapes that make a naive containment test over-fire.
 */
class MemorySimilarityTest extends UnitTest {

    private static final double JACCARD = 0.85;
    private static final double CONTAINMENT = 0.82;
    private static final double MIN_RATIO = 0.5;

    private static boolean dup(String a, String b) {
        return MemorySimilarity.isDuplicate(Tokens.of(a), Tokens.of(b),
                JACCARD, CONTAINMENT, MIN_RATIO);
    }

    // ─── the pre-existing Jaccard leg still decides what it always decided ────

    @Test
    void identicalTextIsDuplicate() {
        assertTrue(dup("The user's name is Sam.", "The user's name is Sam."));
    }

    @Test
    void unrelatedFactsAreNotDuplicates() {
        assertFalse(dup("The user's name is Sam.",
                "The prod database runs PostgreSQL 17 behind pgbouncer."));
    }

    // ─── containment leg: subset restatements Jaccard structurally misses ─────

    @Test
    void subsetRestatementIsDuplicate() {
        // Jaccard 0.53 — the longer text's extra qualifiers sink it below threshold.
        assertTrue(dup("The user enjoys action, sci-fi, and thriller movies, but not horror or romance as primary genres.",
                "The user enjoys Action, Sci-Fi, and Thriller genres."));
    }

    @Test
    void restatementWithAddedDetailIsDuplicate() {
        // Jaccard 0.50: same fact, one side carries a fuller address.
        assertTrue(dup("The user lives in Marlow Court.",
                "The user lives at Marlow Court, Santa Rosa, Portugal."));
    }

    /**
     * The documented ceiling of the lexical rule. This pair IS a duplicate, and the
     * rule does not catch it: containment lands at 9/11 = 0.818, just under the 0.82
     * threshold, because "scheduled"/"recurring" and "5 PM"/"5:00 PM" share no
     * tokens. Reaching it needs a 0.80 threshold, which admits three false positives
     * on the same corpus — unrelated facts built from the same sentence template.
     * Closing this gap properly needs embedding cosine, not a looser lexical bound.
     */
    @Test
    void paraphraseWithNoSharedWordingIsBeyondTheLexicalRule() {
        assertFalse(dup("Tarun has a reminder scheduled for the last Friday of every month at 5 PM to pay salaries.",
                "The user has a recurring reminder to pay salaries on the last Friday of every month at 5:00 PM."));
    }

    @Test
    void restatementWithDifferentQualifiersIsDuplicate() {
        // Containment 0.83, Jaccard 0.71 — caught at 0.82, missed at 0.85.
        assertTrue(dup("The user has a daily briefing skill configured that covers weather, local events, and news (local Malaysian, tech, business, and world).",
                "The user uses a daily briefing skill that includes weather, local events, local news, tech news, business news, and world news."));
    }

    @Test
    void abbreviatedRestatementIsDuplicate() {
        // Jaccard 0.55.
        assertTrue(dup("The user has a NAS at home with IP address 192.168.0.50 with various SMB shares.",
                "The user's NAS/SMB server is at IP address 192.168.0.50."));
    }

    @Test
    void nearVerbatimRestatementIsDuplicate() {
        // Jaccard 0.83 — two words apart and the old threshold still missed it.
        assertTrue(dup("The user is interested in how to build agentic harnesses that induce System 2-like reasoning from a frozen LLM without retraining.",
                "The user is interested in building agentic harnesses that induce System 2-like reasoning from a frozen LLM without retraining."));
    }

    // ─── length-ratio guard: the containment leg's false-positive shape ───────

    @Test
    void shortMemorySwallowedByLongDigestIsNotDuplicate() {
        // Containment 0.91 — the digest covers every content token of the reminder
        // without being about it. The ratio guard is the only thing separating them.
        var digest = "The user has 10 recurring tasks: pay-salaries-last-friday (last Friday 5 PM, web "
                + "reminder for payroll), dgx-spark-price-hunt (Sunday 3 PM, Telegram, hunts NVIDIA DGX "
                + "Spark prices from Malaysian retailers), email-labeler (every 30 mins, auto-labels unread "
                + "Gmail emails as Expense, Software, etc.), hourly-expense-tracker (every hour at :15, "
                + "Telegram, scans Gmail for expense receipts), daily-briefing (9 AM, 1 PM, 5 PM daily, "
                + "Slack, tech/business/world news + weather), yoga-martial-arts-reminder (Sunday 9 AM).";
        assertFalse(dup("The user has a recurring reminder to pay salaries on the last Friday of every month at 5:00 PM.",
                digest));
    }

    @Test
    void sameShapedFactsAboutDifferentSubjectsAreNotDuplicates() {
        // Identical boilerplate, different app — containment 0.75 on content tokens.
        assertFalse(dup("The user has a hosted app called \"Color Picker\" located at public/apps/color-picker/.",
                "The user has a hosted app called \"Proposal Generator\" located at public/apps/proposal-generator/."));
    }

    // ─── metric edges ────────────────────────────────────────────────────────

    @Test
    void emptyAndBoilerplateOnlyTextsDoNotFalselyMatch() {
        // All tokens stripped as boilerplate → empty content sets. containment()
        // returns 0 for an empty side, so these must not read as duplicates.
        assertFalse(dup("The user has it.", "They are from that."));
    }

    // ─── JCLAW-1054: one normalization, shared with search ───────────────────

    @Test
    void tokenizationMatchesTheSearchAnalyzer() throws Exception {
        // The point of the change: this rule and the keyword leg must agree on what "the
        // same text" means. Compared against the analyzer directly rather than against a
        // restatement of it, so swapping the analyzer moves both or fails here.
        var text = "The user's daughters were reading books about volcanoes.";
        var viaAnalyzer = new java.util.HashSet<String>();
        try (var ts = services.search.LuceneIndexer.ANALYZER.tokenStream("memory", text)) {
            var term = ts.addAttribute(org.apache.lucene.analysis.tokenattributes.CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) viaAnalyzer.add(term.toString());
            ts.end();
        }
        assertEquals(viaAnalyzer, MemorySimilarity.tokenize(text),
                "memory tokenization diverged from the search analyzer");
    }

    @Test
    void boilerplateIsStrippedAfterNormalizationNotBefore() {
        // If the stopword list were matched unnormalized it would silently stop removing
        // anything the analyzer rewrites, inflating every content set at once — the failure
        // mode is invisible because nothing throws, the thresholds just quietly change meaning.
        var content = MemorySimilarity.contentTokens("The user has it and they were at that assistant.");
        assertTrue(content.isEmpty(),
                "every token here is boilerplate; content set should be empty, got: " + content);
    }

    @Test
    void pluralAndSingularFormsCountAsTheSameToken() {
        // What the shared normalization buys dedup: a restatement that only differs by
        // inflection now overlaps instead of reading as fresh vocabulary.
        assertTrue(MemorySimilarity.contentTokens("The user's nicknames are recorded.")
                        .containsAll(MemorySimilarity.contentTokens("The user's nickname is recorded.")),
                "singular and plural must normalize to the same token");
    }
}
