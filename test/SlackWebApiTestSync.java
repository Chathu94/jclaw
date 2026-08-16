import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes tests that swap {@link channels.SlackWebApi}'s static seams across play1's
 * concurrent test lanes (JCLAW-1060). Mirrors {@link LuceneTestSync}, for the same reason:
 * play1 runs test classes concurrently, so two classes assigning the same static clobber
 * each other's fake and the loser sees the wrong one.
 *
 * <p>Two classes now share those seams. {@code SlackWebApiResolveTest} swaps
 * {@code channelLister} / {@code scopeProber} / {@code dmOpener} directly, and
 * {@code SlackFileUploaderTest} needs {@code dmOpener} too because JCLAW-1060 routed
 * uploads through {@code SlackWebApi.resolveChannel} instead of a second resolver of
 * their own — one implementation, so one seam.
 *
 * <p>Distinct bot tokens per test remain necessary regardless: the resolution cache is
 * process-wide and keyed by (token-hash, target), and this lock does not clear it.
 */
public final class SlackWebApiTestSync {

    private static final ReentrantLock LOCK = new ReentrantLock();

    private SlackWebApiTestSync() {}

    public static void acquire() {
        LOCK.lock();
    }

    public static void release() {
        if (LOCK.isHeldByCurrentThread()) {
            LOCK.unlock();
        }
    }
}
