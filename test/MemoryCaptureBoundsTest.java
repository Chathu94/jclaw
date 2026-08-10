import memory.JpaMemoryStore;
import memory.MemoryAutoCapture;
import memory.MemoryStoreFactory;
import models.Memory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;
import utils.CircuitBreaker;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JCLAW-964, JCLAW-1015 and JCLAW-965: what bounds one turn's capture.
 *
 * <p>Drives {@link MemoryAutoCapture#capture} through a real vector-enabled
 * {@link JpaMemoryStore} on the Lucene HNSW backend with a counting/failing embedder, the
 * same seam {@code MemoryAutoCaptureDeferredEmbeddingTest} uses. LuceneTestSync-serialized
 * (JCLAW-428) because it pins a store behind {@link MemoryStoreFactory}.
 */
class MemoryCaptureBoundsTest extends UnitTest {

    private static final String POISON = "POISON";

    /** Counts embedder invocations so an amplification regression is measurable. */
    private final AtomicInteger embedCalls = new AtomicInteger();
    /** Sightings of the poison text; the embedder throws from the second one on. */
    private final AtomicInteger poisonSeen = new AtomicInteger();

    /** Set by the concurrency test: parks the first embedder call so its capture holds the
     *  per-agent lock while a second turn tries to start. */
    private final java.util.concurrent.atomic.AtomicBoolean parkFirstEmbed =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final CountDownLatch lockHeld = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void setup() {
        LuceneTestSync.openForTest();
        Fixtures.deleteDatabase();
        MemoryStoreFactory.reset();
        embedCalls.set(0);
        poisonSeen.set(0);
        parkFirstEmbed.set(false);
        JpaMemoryStore.setEmbedderForTest(text -> {
            embedCalls.incrementAndGet();
            if (parkFirstEmbed.compareAndSet(true, false)) {
                lockHeld.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            }
            // Throw on the SECOND sighting only. The first is the semantic dedup pass, which
            // embeds every candidate before anything is stored; the second is the post-commit
            // embedStored pass, which is the loop under test. Production cannot throw from the
            // first — generateEmbedding catches provider errors and returns null; only this
            // test seam bypasses that.
            if (text != null && text.contains(POISON) && poisonSeen.incrementAndGet() >= 2) {
                throw new IllegalStateException("simulated embedding persist failure");
            }
            return new float[] {1f, 0f, 0f, 0f};
        });
        MemoryStoreFactory.setForTest(new JpaMemoryStore(true, false));
    }

    @AfterEach
    void teardown() {
        JpaMemoryStore.setEmbedderForTest(null);
        MemoryStoreFactory.reset();
        ConfigService.delete("memory.autocapture.lockWaitSeconds");
        LuceneTestSync.release();
    }

    private CircuitBreaker freshBreaker() {
        return new CircuitBreaker(20, 0.5, 5, 30_000L);
    }

    private String agentId(String name) {
        var a = new models.Agent();
        a.name = name;
        a.modelProvider = "openrouter";
        a.modelId = "gpt-4.1";
        a.save();
        return String.valueOf(a.id);
    }

    /** Extractor output with {@code n} distinct candidates. */
    private static String candidates(int n) {
        var sb = new StringBuilder("{\"memories\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"text\":\"Distinct operator fact number ").append(i)
              .append(" about subject ").append(i)
              .append("\",\"category\":\"fact\",\"importance\":0.6}");
        }
        return sb.append("]}").toString();
    }

    @Test
    void anOverlongCandidateListCannotAmplifyIntoUnboundedProviderCalls() {
        // JCLAW-964: semanticDuplicateIndices embedded EVERY parsed candidate over HTTP before
        // maxPerTurn was applied, so a degenerate extractor returning 200 one-sentence
        // candidates became 200 embedding round-trips for a turn that can store at most 5.
        var agent = agentId("bounds-amplify");
        MemoryAutoCapture.Extractor extractor = _ -> candidates(200);

        MemoryAutoCapture.capture(agent, "bounds-amplify",
                "Here is a great deal of information about my setup", "Noted.",
                extractor, freshBreaker());

        int maxCandidates = ConfigService.getInt("memory.autocapture.maxCandidates", 25);
        int maxPerTurn = ConfigService.getInt("memory.autocapture.maxPerTurn", 5);
        // One embed per surviving candidate in the semantic dedup pass, plus one per stored row
        // in the post-commit pass. The point is that it is bounded by the ceiling, not by what
        // the extractor chose to return.
        assertTrue(embedCalls.get() <= maxCandidates + maxPerTurn,
                "expected at most %d embedder calls, got %d — the ceiling is not bounding the work"
                        .formatted(maxCandidates + maxPerTurn, embedCalls.get()));
        assertTrue(embedCalls.get() < 200, "200 candidates must not become 200 provider calls");
    }

    @Test
    void oneEmbeddingFailureDoesNotAbandonTheRestOfTheBatch() {
        // JCLAW-1015: the loop had no per-id try/catch, and JpaMemoryStore.embedStored does not
        // swallow a throw from its own snapshot transaction — so a failure on one id abandoned
        // every id after it. Those rows are committed and FTS-searchable; what they lose is the
        // vector leg, and nothing re-embeds them because the re-embed marker is untouched.
        var agent = agentId("bounds-partial");
        MemoryAutoCapture.Extractor extractor = _ -> """
                {"memories":[
                  {"text":"The operator keeps the NAS in the basement utility room","category":"fact","importance":0.6},
                  {"text":"The operator POISON breaks the embedding provider outright","category":"fact","importance":0.6},
                  {"text":"The operator drinks espresso from a Silvia Pro machine","category":"fact","importance":0.6}]}
                """;

        var result = MemoryAutoCapture.capture(agent, "bounds-partial",
                "Some things about my house and habits", "Noted.", extractor, freshBreaker());

        assertEquals(3, result.captured(), "every candidate must still be STORED — the rows commit "
                + "before the embedding pass, so an embed failure must not lose them");
        assertEquals(3L, Memory.count("agent.id = ?1", Long.valueOf(agent)),
                "all three rows must be present in the database");
        // 3 dedup embeds + 3 attempted post-commit embeds. Abandoning at the poisoned second id
        // would stop at 5, leaving the third row silently absent from the vector leg for good.
        assertEquals(6, embedCalls.get(),
                "every stored id must be ATTEMPTED — a failure on one must not abandon the rest");
    }

    @Test
    void aSecondCaptureForOneAgentCannotRaceAnInFlightOne() throws Exception {
        // JCLAW-965: capture is check-then-act across three transactions with an LLM judge call
        // in the middle, and captureAsync spawns an unsynchronized virtual thread per turn — so
        // two turns for one agent could both find nothing matching and both store the same fact.
        // Memory's @Table declares only non-unique indexes, so the DB cannot reject the second
        // write either, and the consolidation judge does not pair identical rows.
        ConfigService.set("memory.autocapture.lockWaitSeconds", "0");
        var agent = agentId("bounds-concurrent");
        parkFirstEmbed.set(true);

        var secondResult = new java.util.concurrent.atomic.AtomicReference<MemoryAutoCapture.CaptureResult>();
        MemoryAutoCapture.Extractor extractor = _ ->
                "{\"memories\":[{\"text\":\"The NAS lives in the basement utility room\","
                        + "\"category\":\"fact\",\"importance\":0.6}]}";

        // Thread A parks inside the semantic dedup pass, which runs AFTER the per-agent lock is
        // taken — so A demonstrably holds the lock while B tries.
        var holder = Thread.ofPlatform().start(() -> MemoryAutoCapture.capture(
                agent, "bounds-concurrent", "my NAS lives in the basement", "Noted.",
                extractor, freshBreaker()));

        assertTrue(lockHeld.await(10, TimeUnit.SECONDS),
                "the first capture must reach the embedder, which runs inside the lock");
        var b = Thread.ofPlatform().start(() -> secondResult.set(MemoryAutoCapture.capture(
                agent, "bounds-concurrent", "just so you know, the NAS is in the basement",
                "Noted.", extractor, freshBreaker())));
        b.join(15_000);

        release.countDown();
        holder.join(15_000);

        assertNotNull(secondResult.get(), "the second capture must have returned");
        assertEquals("capture_in_flight", secondResult.get().skipReason(),
                "a second turn must not run the check-then-act window concurrently with the first");
        assertEquals(0, secondResult.get().captured(), "the skipped capture must store nothing");
    }
}
