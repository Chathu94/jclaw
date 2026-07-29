import agents.ToolRegistry;
import jobs.ToolRegistrationJob;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes tool-registry-touching tests across play1's concurrent test lanes
 * (JCLAW-894). Mirrors {@link LuceneTestSync}, which solves the same problem for
 * the shared Lucene index.
 *
 * <h2>Why this is needed</h2>
 *
 * <p>{@link ToolRegistry} holds its tool map in process-global statics. A class
 * that republishes it changes what every concurrently-running class sees. This is
 * not hypothetical: it failed the {@code play autotest} run on the v0.17.18 push,
 * where {@code ToolResultVerifierTest} asked for {@code exec} and got null.
 *
 * <p>The dominant cause was not a narrow race. {@code ToolSystemTest} published a
 * four-tool stub in {@code @BeforeEach} and never restored it, so from its first
 * test onward the native registry stayed stubbed for the remainder of the JVM
 * run. Six other classes assert "tool X is registered by ToolRegistrationJob" with
 * no guard at all, and passed only when the scheduler happened to run them first.
 *
 * <h2>Restore is {@code registerAll()}, not a snapshot</h2>
 *
 * <p>The snapshot idiom several classes used — {@code var saved =
 * ToolRegistry.listTools()} … {@code ToolRegistry.publish(saved)} — is itself
 * wrong. {@code listTools()} returns the MERGED map (natives + MCP-discovered
 * external groups), while {@code publish()} replaces only the NATIVE map. Round-
 * tripping through it therefore promotes every external tool into the native slot,
 * where nothing will ever unpublish it. {@link ToolRegistrationJob#registerAll()}
 * rebuilds the canonical native set and leaves external groups untouched, which is
 * what "restore" actually means here.
 *
 * <h2>Cost</h2>
 *
 * <p>One global lock, so registry-touching classes run one at a time. That is a
 * real wall-clock cost on a suite this size, accepted because these classes were
 * already conflicting — they just failed silently instead of waiting.
 */
public final class ToolRegistrySync {

    private static final ReentrantLock LOCK = new ReentrantLock();

    private ToolRegistrySync() {}

    /**
     * Lock, then install {@code tools} as the native set. Call first in
     * {@code @BeforeEach}; pair with {@link #release()} in {@code @AfterEach}.
     * On failure the lock is released so it never leaks to the next test.
     */
    public static void publishForTest(List<ToolRegistry.Tool> tools) {
        LOCK.lock();
        try {
            ToolRegistry.publish(tools);
        } catch (RuntimeException e) {
            LOCK.unlock();
            throw e;
        }
    }

    /**
     * Lock, then guarantee the canonical native set is installed — for tests that
     * assert a tool is registered rather than stubbing the registry themselves.
     * Without this they inherit whatever the previous class left behind.
     */
    public static void canonicalForTest() {
        LOCK.lock();
        try {
            ToolRegistrationJob.registerAll();
        } catch (RuntimeException e) {
            LOCK.unlock();
            throw e;
        }
    }

    /**
     * Restore the canonical native set and release the lock. Call in
     * {@code @AfterEach} — JUnit runs it even when the test or a later
     * {@code @BeforeEach} fails, which is what stops a stub leaking out of the
     * class the way {@code ToolSystemTest}'s used to. Idempotent: only unlocks
     * when the current thread holds the lock.
     */
    public static void release() {
        try {
            ToolRegistrationJob.registerAll();
        } finally {
            if (LOCK.isHeldByCurrentThread()) {
                LOCK.unlock();
            }
        }
    }

    /**
     * Scoped form for a single test that stubs the registry inline rather than in
     * {@code @BeforeEach}. Restore cannot be forgotten, which is the failure mode
     * this class exists to remove.
     */
    public static void withTools(List<ToolRegistry.Tool> tools, ThrowingBody body) throws Exception {
        publishForTest(tools);
        try {
            body.run();
        } finally {
            release();
        }
    }

    /** Scoped form for a test that needs the canonical registry, not a stub. */
    public static void withCanonicalTools(ThrowingBody body) throws Exception {
        canonicalForTest();
        try {
            body.run();
        } finally {
            release();
        }
    }

    /** Test bodies routinely throw checked exceptions, so {@link Runnable} does not fit. */
    @FunctionalInterface
    public interface ThrowingBody {
        void run() throws Exception;
    }
}
