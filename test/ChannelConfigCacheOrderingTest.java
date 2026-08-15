import models.ChannelConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.UnitTest;
import services.Tx;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-1042 / VULN-113: {@link ChannelConfig}'s lifecycle callbacks fire at flush, so the
 * cache eviction they perform lands before the commit. A reader racing that window loads the
 * row the commit has not written yet and re-caches the result for the full 60 s TTL, so the
 * stale answer outlives the write that was supposed to replace it.
 *
 * <p>The write runs on its own thread through {@link Tx#run}, which opens and commits a
 * thread-bound transaction — the shape the production write path actually has. A detached
 * {@code JPA.newEntityManager} would not do: the deferred eviction resolves the
 * <em>thread-bound</em> EntityManager, so a write on a detached one registers against
 * whatever transaction the calling thread happens to hold instead.
 *
 * <p>The channel type is unique per run, so a sibling class wiping the database (JCLAW-1012)
 * can only remove this row, never substitute another test's.
 */
class ChannelConfigCacheOrderingTest extends UnitTest {

    private String channelType;

    @BeforeEach
    void setup() {
        channelType = "jclaw1042-vuln113-" + System.nanoTime();
    }

    @AfterEach
    void cleanup() {
        ChannelConfig.evictCache(channelType);
    }

    @Test
    void aReadRacingTheFlushWindowDoesNotSurviveTheCommit() throws Exception {
        var flushed = new CountDownLatch(1);
        var readerDone = new CountDownLatch(1);
        var writerFailure = new AtomicReference<Throwable>();

        var writer = new Thread(() -> {
            try {
                Tx.run(() -> {
                    var config = new ChannelConfig();
                    config.channelType = channelType;
                    config.configJson = "{}";
                    config.enabled = true;
                    config.save();
                    // Fires @PostPersist: the immediate eviction, plus the deferred one.
                    JPA.em().flush();
                    flushed.countDown();
                    try {
                        // Hold the transaction open while the racing read happens.
                        if (!readerDone.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("racing reader never finished");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                });
            } catch (Throwable t) {
                writerFailure.set(t);
                flushed.countDown();
            }
        }, "jclaw1042-writer");

        writer.start();
        assertTrue(flushed.await(10, TimeUnit.SECONDS), "the writer must reach its flush");
        assertNull(writerFailure.get(), "the writer must not fail before the racing read");

        // This thread is on a different transaction, so it cannot see the uncommitted row and
        // memoises the absence — the poisoned entry the commit has to clear.
        assertNull(ChannelConfig.findByType(channelType),
                "precondition: the racing read cannot see the uncommitted row, so it caches "
                        + "the absence");

        readerDone.countDown();
        writer.join(15_000);
        assertFalse(writer.isAlive(), "the writer must commit and finish");
        assertNull(writerFailure.get(), "the writer must commit cleanly");

        // Only the after-commit eviction can clear what that read cached: the immediate one
        // fired at flush, before the read happened.
        assertNotNull(ChannelConfig.findByType(channelType),
                "the committed row must be visible — an absence cached during the "
                        + "flush-to-commit window must not outlive the commit");
    }
}
