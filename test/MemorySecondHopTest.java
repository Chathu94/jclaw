import memory.JpaMemoryStore;
import memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;

import java.util.List;
import java.util.Map;

/**
 * JCLAW-529: the bridge case — a question asks through a relation ("what is my kid's
 * alias") while the memory holding the answer names only the entity ("Zephyrin goes by
 * Zeph"). The relation and the fact live in different rows, so neither retrieval leg can
 * cross the gap in one shot, and the second hop is what closes it.
 *
 * <p>The control arm is isolated <em>by agent</em> rather than by toggling
 * {@code memory.recall.secondHop.enabled}: the play1 engine runs test lanes
 * concurrently, so flipping a process-global config key would leak into whatever else is
 * running. Both agents get the same query, the same canned vectors and the same fact
 * row; only the bridging relation row differs.
 */
class MemorySecondHopTest extends UnitTest {

    /** Names the entity and its relation to the user — the bridge. */
    private static final String RELATION = "The user has a son named Zephyrin.";

    /** Holds the answer, names no relation. Shares no token with {@link #QUERY}. */
    private static final String FACT = "Zephyrin goes by Zeph.";

    /**
     * Deliberately shares no token with either {@link #RELATION} or {@link #FACT}, so
     * the keyword leg cannot reach them and hop 1 has to arrive through the vector leg.
     */
    private static final String QUERY = "what is my kid's alias";

    /**
     * Enough filler to push FACT out of the vector leg. Both legs are capped at the
     * recall limit, so on a corpus smaller than that cap the KNN leg returns every
     * memory it has and FACT arrives without any hop — the first version of this test
     * passed with the hop disabled for exactly that reason. Each carries no capitalised
     * token, so it cannot pollute the hop's seed names, and no token of {@link #QUERY},
     * so it cannot reach the keyword leg either.
     */
    private static final List<String> FILLER = List.of(
            "the deploy pipeline requires manual approval",
            "the build runs nightly against the staging cluster",
            "the editor uses a dark theme by default",
            "the release notes are drafted before each tag",
            "the backup job writes to cold storage weekly",
            "the dashboard refreshes every thirty seconds",
            "the invoice template was rewritten last quarter",
            "the meeting notes live in a shared folder",
            "the linter runs before every commit",
            "the cache is warmed on startup",
            "the report is exported as a spreadsheet",
            "the queue drains fastest in the afternoon");

    /**
     * 4-dim canned vectors. The query sits on the RELATION axis; filler sits just off it,
     * so the ten nearest are RELATION plus filler and FACT — pointing down a third axis —
     * is out of reach for both legs. Cosine 1.0 on the top hit clears
     * {@code DEFAULT_RECALL_MIN_COSINE}: this test is about fusion, not the floor.
     */
    private static final Map<String, float[]> EMBEDDINGS = buildEmbeddings();

    private static Map<String, float[]> buildEmbeddings() {
        var m = new java.util.HashMap<String, float[]>();
        m.put(RELATION, new float[] {1f, 0f, 0f, 0f});
        m.put(FACT, new float[] {0f, 0f, 1f, 0f});
        m.put(QUERY, new float[] {1f, 0f, 0f, 0f});
        for (var f : FILLER) m.put(f, new float[] {0.99f, 0.14f, 0f, 0f});
        return Map.copyOf(m);
    }

    private MemoryStore store;

    @BeforeEach
    void setup() {
        LuceneTestSync.openForTest();
        Fixtures.deleteDatabase();
        JpaMemoryStore.setEmbedderForTest(EMBEDDINGS::get);
        store = new JpaMemoryStore(true, false);
    }

    @AfterEach
    void teardown() {
        JpaMemoryStore.setEmbedderForTest(null);
        LuceneTestSync.release();
    }

    private String agentId(String name) {
        var a = new models.Agent();
        a.name = name;
        a.modelProvider = "openrouter";
        a.modelId = "gpt-4.1";
        a.save();
        return String.valueOf(a.id);
    }

    private boolean recalls(String agent, String text) {
        return store.search(agent, QUERY, 10).stream().anyMatch(e -> e.text().equals(text));
    }

    private void seedFiller(String agent) {
        for (var f : FILLER) store.store(agent, f, "fact", 0.5);
    }

    @Test
    void bridgeRowLetsTheHopReachTheFact() {
        var agent = agentId("hop-bridged");
        store.store(agent, RELATION, "entity", 0.7);
        store.store(agent, FACT, "entity", 0.7);
        seedFiller(agent);

        assertTrue(recalls(agent, RELATION), "precondition: hop 1 reaches the bridge via the vector leg");
        assertTrue(recalls(agent, FACT),
                "the hop seeds on 'Zephyrin' from the bridge row and must reach the fact that answers the question");
    }

    @Test
    void withoutTheBridgeRowTheFactStaysUnreachable() {
        // Same query, same vectors, same fact row — only the bridge is missing. This is
        // the pre-fix behaviour the live corpus showed, and it must stay reproducible or
        // the test above proves nothing.
        var agent = agentId("hop-unbridged");
        store.store(agent, FACT, "entity", 0.7);
        seedFiller(agent);

        assertFalse(recalls(agent, FACT),
                "with no row naming the entity's relation there is nothing for the hop to seed on");
    }

    @Test
    void hopDoesNotLeakAcrossAgents() {
        // The hop runs a second search; if it were not agent-bounded it would be a
        // cross-tenant read of exactly the kind hydrateMissing already guards against.
        var owner = agentId("hop-owner");
        var other = agentId("hop-other");
        store.store(owner, RELATION, "entity", 0.7);
        store.store(other, FACT, "entity", 0.7);

        assertFalse(recalls(owner, FACT), "the hop must not reach another agent's memory");
    }

    // --- entityNames: the rule that decides what the hop searches for ---

    @Test
    void liftsCapitalisedEntityNames() {
        assertEquals(List.of("Zephyrin"), JpaMemoryStore.entityNames(RELATION));
        assertEquals(List.of("Zephyrin", "Zeph"), JpaMemoryStore.entityNames(FACT));
    }

    @Test
    void skipsThirdPersonBoilerplate() {
        // Every auto-captured memory opens "The user ...", so without the boilerplate
        // filter every hop would seed on "The" and match the entire corpus.
        assertEquals(List.of(), JpaMemoryStore.entityNames("The user is happy."));
    }

    @Test
    void skipsMonthsAndWeekdays() {
        assertEquals(List.of("Kavi"),
                JpaMemoryStore.entityNames("The user's son Kavi was born on Feb 7, and visits on Monday."));
    }

    @Test
    void deduplicatesAndIgnoresShortOrLowercaseTokens() {
        assertEquals(List.of("Renu"), JpaMemoryStore.entityNames("Renu and Renu, aka R, met renu."));
    }

    // --- asymmetric query embedding ---

    @Test
    void prefixesOnlyWhenAModelNeedsIt() {
        // Measured on a live corpus with snowflake-arctic-embed: the memory answering
        // "what do I call my children?" ranked 54th of 89 unprefixed and 16th prefixed —
        // the index was returning its true nearest neighbours the whole time, and the gold
        // simply was not among them.
        assertEquals("Q: hello", JpaMemoryStore.prefixQuery("Q: ", "hello"));
        assertEquals("hello", JpaMemoryStore.prefixQuery("", "hello"),
                "empty is the default: a wrong prefix is worse than none");
        assertEquals("hello", JpaMemoryStore.prefixQuery(null, "hello"));
    }

    @Test
    void toleratesNullAndEmptyText() {
        assertEquals(List.of(), JpaMemoryStore.entityNames(null));
        assertEquals(List.of(), JpaMemoryStore.entityNames(""));
    }
}
