import services.LoadTestHarness;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes tests that touch the mock load-test server across play1's concurrent
 * lanes (JCLAW-890). Sibling of {@link LuceneTestSync}, which solves the same
 * shape for the Lucene index.
 *
 * <p>{@link LoadTestHarness} is a JVM-global singleton by design — one mock HTTP
 * server per process, with {@code server}, {@code port} and {@code scenario} held
 * in statics and {@code start()} deliberately idempotent. That is right for
 * production, where {@code LoadTestRunner} and {@code ApiMetricsController} both
 * address one server. It means two tests cannot own it at once.
 *
 * <p>Two classes reach it, and they sit in different play1 lanes so JUnit's own
 * {@code @Isolated} / {@code @ResourceLock} cannot span them:
 *
 * <ul>
 *   <li>{@code LoadTestHarnessTest} (unit lane) calls {@code start}, {@code stop}
 *       and {@code setScenario} directly.</li>
 *   <li>{@code ApiMetricsControllerLoadtestRunTest} (functional lane) POSTs
 *       {@code /api/metrics/loadtest}, which reaches {@code LoadTestRunner}'s
 *       {@code setScenario}, and also calls {@code stop()} itself.</li>
 * </ul>
 *
 * <p>The observed failure was a stream that could not have come from one
 * scenario: {@code completion_tokens: 3} with the {@code idx == 0} "Hello" chunk
 * absent and only {@code tok1}/{@code tok2} present. Worth noting the direction
 * that is NOT visible — the same race can leave a test asserting against another
 * test's scenario and still passing, which is silent. Serializing removes both.
 *
 * <p>{@link #acquire()} also stops any server left running, so each test starts
 * from a known-clean harness rather than inheriting the previous one's port and
 * scenario.
 */
public final class LoadTestHarnessSync {

    private static final ReentrantLock LOCK = new ReentrantLock();

    private LoadTestHarnessSync() {}

    /**
     * Lock the harness and reset it to a stopped state. Call first in
     * {@code @BeforeEach}. On failure the lock is released so it never leaks to
     * the next test.
     */
    public static void acquire() {
        LOCK.lock();
        try {
            LoadTestHarness.stop();
        } catch (RuntimeException e) {
            LOCK.unlock();
            throw e;
        }
    }

    /**
     * Stop the harness and release the lock. Call in {@code @AfterEach} — JUnit
     * runs it even when the test or a later {@code @BeforeEach} fails. Idempotent:
     * only unlocks when the current thread holds the lock, so a test that never
     * acquired cannot unlock someone else's window.
     */
    public static void release() {
        try {
            LoadTestHarness.stop();
        } finally {
            if (LOCK.isHeldByCurrentThread()) {
                LOCK.unlock();
            }
        }
    }
}
