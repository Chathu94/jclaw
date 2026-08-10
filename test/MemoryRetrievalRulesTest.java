import memory.JpaMemoryStore;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.List;

/**
 * JCLAW-529: the two pure rules the memory retrieval path depends on — how a query is
 * embedded, and what counts as an entity name.
 *
 * <p>Both are tested as functions rather than through recall because each is decided by
 * config a test must not flip: the play1 engine runs lanes concurrently, so setting a
 * process-global key would leak into whatever else is running.
 */
class MemoryRetrievalRulesTest extends UnitTest {

    // --- query embedding is asymmetric ---

    @Test
    void prefixesOnlyWhenAModelNeedsIt() {
        // Measured on a live corpus with snowflake-arctic-embed: the memory answering
        // "what do I call my children?" was the 54th nearest neighbour of that query
        // unprefixed and the 16th prefixed, so a KNN leg capped at the recall limit could
        // never return it. The index was returning its true nearest neighbours throughout.
        assertEquals("Q: hello", JpaMemoryStore.prefixQuery("Q: ", "hello"));
        assertEquals("hello", JpaMemoryStore.prefixQuery("", "hello"),
                "empty is the default: the right string is model-specific, and a wrong one is worse than none");
        assertEquals("hello", JpaMemoryStore.prefixQuery(null, "hello"));
    }

    // --- entity names: what the key backfill uses to find a memory's neighbours ---

    @Test
    void liftsCapitalisedEntityNames() {
        assertEquals(List.of("Zephyrin"),
                JpaMemoryStore.entityNames("The user has a son named Zephyrin."));
        assertEquals(List.of("Zephyrin", "Zeph"),
                JpaMemoryStore.entityNames("Zephyrin goes by Zeph."));
    }

    @Test
    void skipsThirdPersonBoilerplate() {
        // Every auto-captured memory opens "The user ...", so without the boilerplate
        // filter every memory would look like it names an entity called "The".
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

    @Test
    void toleratesNullAndEmptyText() {
        assertEquals(List.of(), JpaMemoryStore.entityNames(null));
        assertEquals(List.of(), JpaMemoryStore.entityNames(""));
    }
}
