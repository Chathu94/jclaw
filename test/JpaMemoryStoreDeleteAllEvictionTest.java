import memory.JpaMemoryStore;
import memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.search.LuceneIndexer;

import java.util.Map;

/**
 * JCLAW-820: {@code deleteAll} evicts the agent's MEMORY-scope Lucene docs. The
 * bulk JPQL DELETE bypasses {@code @PostRemove}, so without the explicit
 * {@link LuceneIndexer#removeByAgent} pass the agent's FTS + HNSW vector docs
 * orphan. Runs on the real Lucene index (LuceneTestSync-serialized, JCLAW-428)
 * with vector memory on and canned embeddings, mirroring
 * {@code JpaMemoryStoreVectorTest}.
 */
class JpaMemoryStoreDeleteAllEvictionTest extends UnitTest {

    private static final String BERLIN = "The user lives in Berlin and enjoys hiking";
    private static final String FINANCE = "Quarterly finance report is due in October";
    /** Lexically matches NOTHING stored — only the KNN leg can recall via it. */
    private static final String NONSENSE_QUERY = "zebra quix flurble";

    /** Canned 4-dim embeddings; the query vector is near both memories. */
    private static final Map<String, float[]> EMBEDDINGS = Map.of(
            BERLIN, new float[] {1f, 0f, 0f, 0f},
            FINANCE, new float[] {0.95f, 0.05f, 0f, 0f},
            NONSENSE_QUERY, new float[] {0.9f, 0.1f, 0f, 0f});

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

    /** Create a real agent (memories carry a real FK, JCLAW-537). */
    private String agentId(String name) {
        var a = new models.Agent();
        a.name = name;
        a.modelProvider = "openrouter";
        a.modelId = "gpt-4.1";
        a.save();
        return String.valueOf(a.id);
    }

    /** Commit on a separate thread, as {@code CascadeLuceneCleanupTest} does — the eviction is
     *  registered for after-completion (JCLAW-1014), so it only fires on a real commit. */
    private static <T> T commitInFreshTx(java.util.function.Supplier<T> block) {
        var ref = new java.util.concurrent.atomic.AtomicReference<T>();
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                ref.set(services.Tx.run(block::get));
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }

    @Test
    void deleteAllEvictsTheAgentsMemoryDocsOnceCommitted() {
        var suffix = String.valueOf(System.nanoTime());
        var ids = commitInFreshTx(() -> {
            var m = agentId("evict-mine-" + suffix);
            var o = agentId("evict-other-" + suffix);
            store.store(m, BERLIN, "fact", 0.6);
            store.store(o, FINANCE, "fact", 0.6);
            return new String[] {m, o};
        });
        var mine = ids[0];
        var other = ids[1];

        // Both agents are recallable before the delete.
        assertFalse(store.search(mine, NONSENSE_QUERY, 5).isEmpty(),
                "precondition: the target agent's memory is recallable");
        assertFalse(store.search(other, NONSENSE_QUERY, 5).isEmpty(),
                "precondition: the other agent's memory is recallable");

        var deleted = commitInFreshTx(() -> store.deleteAll(mine));

        assertEquals(1, deleted, "one Memory row removed for the target agent");
        // The contract is agent-isolated eviction: the deleted agent's MEMORY docs
        // (FTS + HNSW vector) become unrecallable while the other agent's survive.
        // Assert that by recall rather than exact Lucene doc counts, which straddle
        // two indexes (FTS + vector) with independent NRT-commit visibility and are
        // an implementation detail, not the contract. If removeByAgent over-deleted
        // across agents, the second assertion below catches it.
        assertTrue(store.search(mine, NONSENSE_QUERY, 5).isEmpty(),
                "deleteAll must evict the deleted agent's orphaned MEMORY docs");
        assertFalse(store.search(other, NONSENSE_QUERY, 5).isEmpty(),
                "another agent's memory must be untouched");
    }

    @Test
    void aRolledBackDeleteLeavesTheDocumentsIntact() {
        // JCLAW-1014: removeByAgent used to delete AND fsync inline, so an agent-delete that
        // threw after this point left the rows alive in the DB with their documents gone for
        // good — unreachable, because init() only backfills a scope whose docCount() is 0.
        var suffix = String.valueOf(System.nanoTime());
        var mine = commitInFreshTx(() -> {
            var m = agentId("evict-rollback-" + suffix);
            store.store(m, BERLIN, "fact", 0.6);
            return m;
        });
        assertFalse(store.search(mine, NONSENSE_QUERY, 5).isEmpty(),
                "precondition: the agent's memory is recallable");

        assertThrows(RuntimeException.class, () -> commitInFreshTx(() -> {
            store.deleteAll(mine);
            throw new IllegalStateException("cascade failed after the memory delete");
        }), "the seeded failure must roll the transaction back");

        assertFalse(store.search(mine, NONSENSE_QUERY, 5).isEmpty(),
                "a rolled-back delete must leave the agent's documents in place");
    }
}
