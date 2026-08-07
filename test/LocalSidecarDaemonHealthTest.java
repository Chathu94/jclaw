import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import services.LocalSidecarDaemon;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** JCLAW-637: the /health identity-parsing path. JCLAW-830: the single-flight /
 *  safe-publication concurrency contract of the daemon lifecycle. JCLAW-989: the
 *  startup-failure message that distinguishes a squatted port from a broken child. */
class LocalSidecarDaemonHealthTest extends UnitTest {

    /** A side-effect-free config: none of the spawn/health paths that would touch
     *  Play or a real process are exercised — only {@code singleFlight}/{@code stop}/
     *  {@code hasProcess}, which are pure lock + field bookkeeping. */
    private static LocalSidecarDaemon.Config testConfig() {
        return new LocalSidecarDaemon.Config(
                "sidecar/none", "data/none", "test.sidecar.jclaw830", 9999, 5,
                "test", "test-sidecar", "test sidecar", "hint", RuntimeException::new);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void healthModel_parsesTheServedModel() {
        assertEquals("pyannote/speaker-diarization-community-1",
                LocalSidecarDaemon.healthModel(
                        "{\"status\":\"ok\",\"device\":\"mps\","
                        + "\"model\":\"pyannote/speaker-diarization-community-1\",\"loaded\":true}"));
    }

    @Test
    void healthModel_toleratesMissingFieldAndGarbage() {
        assertNull(LocalSidecarDaemon.healthModel("{\"status\":\"ok\"}"),
                "older sidecars without the field must not be treated as mismatched");
        assertNull(LocalSidecarDaemon.healthModel("not json"),
                "garbage must parse to null, never throw");
    }

    /** JCLAW-830: two concurrent starters must produce exactly one spawn — the
     *  loser waits on the single-flight lock, then its own health re-check
     *  short-circuits it to a no-op (a double-spawn would poison the cooldown). */
    @Test
    void singleFlight_runsExactlyOneSpawnUnderConcurrentStarters() throws Exception {
        var daemon = new LocalSidecarDaemon(testConfig());
        var healthy = new AtomicBoolean(false);
        var spawns = new AtomicInteger(0);
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);

        Runnable starter = () -> {
            ready.countDown();
            awaitUninterruptibly(go);
            daemon.singleFlight(() -> {
                if (healthy.get()) return null;       // re-check: in-flight spawn already succeeded
                spawns.incrementAndGet();             // the single real spawn
                try {
                    Thread.sleep(50);                 // widen the overlap window
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
                healthy.set(true);
                return null;
            });
        };

        var t1 = Thread.ofPlatform().start(starter);
        var t2 = Thread.ofPlatform().start(starter);
        assertTrue(ready.await(2, TimeUnit.SECONDS), "both starters reached the barrier");
        go.countDown();
        t1.join();
        t2.join();

        assertEquals(1, spawns.get(),
                "single-flight: the second concurrent starter must wait then no-op, not double-spawn");
    }

    /** JCLAW-830: the single-flight lock serializes spawn bodies — no two run at once. */
    @Test
    void singleFlight_actionsNeverOverlap() throws Exception {
        var daemon = new LocalSidecarDaemon(testConfig());
        var inside = new AtomicInteger(0);
        var maxConcurrent = new AtomicInteger(0);
        int n = 8;
        var ready = new CountDownLatch(n);
        var go = new CountDownLatch(1);
        var threads = new ArrayList<Thread>();

        for (int i = 0; i < n; i++) {
            threads.add(Thread.ofPlatform().start(() -> {
                ready.countDown();
                awaitUninterruptibly(go);
                daemon.singleFlight(() -> {
                    int c = inside.incrementAndGet();
                    maxConcurrent.accumulateAndGet(c, Math::max);
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }
                    inside.decrementAndGet();
                    return null;
                });
            }));
        }
        assertTrue(ready.await(2, TimeUnit.SECONDS), "all workers reached the barrier");
        go.countDown();
        for (var t : threads) t.join();

        assertEquals(1, maxConcurrent.get(),
                "single-flight must serialize: never two spawn bodies at once");
    }

    /** JCLAW-830: the core fix — {@code stop()} must not block behind an in-flight
     *  spawn holding the single-flight lock (it uses a separate short lock). */
    @Test
    void stop_doesNotStallBehindInFlightSpawn() throws Exception {
        var daemon = new LocalSidecarDaemon(testConfig());
        var inSpawn = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        var spawner = Thread.ofPlatform().start(() -> daemon.singleFlight(() -> {
            inSpawn.countDown();
            awaitUninterruptibly(release); // hold the single-flight lock (simulate a long health-await)
            return null;
        }));
        assertTrue(inSpawn.await(2, TimeUnit.SECONDS), "spawn entered the single-flight section");

        var stopper = Thread.ofPlatform().start(daemon::stop);
        stopper.join(1000); // stop() must return promptly, NOT wait for the spawner
        boolean stopReturned = !stopper.isAlive();

        release.countDown();
        spawner.join();
        stopper.join();

        assertTrue(stopReturned,
                "stop() must not stall behind an in-flight spawn holding the single-flight lock");
    }

    /** As {@link #testConfig()} but on a caller-chosen port, so the JCLAW-989 message
     *  tests can hold that port for the duration of the assertion. */
    private static LocalSidecarDaemon.Config configOnPort(int port) {
        return new LocalSidecarDaemon.Config(
                "sidecar/none", "data/none", "test.sidecar.jclaw989", port, 5,
                "test", "test-sidecar", "test sidecar", "hint", RuntimeException::new);
    }

    private static ServerSocket boundLoopbackSocket() throws Exception {
        var s = new ServerSocket();
        s.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1);
        return s;
    }

    /** JCLAW-989: the probe the startup message depends on — it must not read as
     *  always-occupied, or every failure would blame a squatter. */
    @Test
    void portOccupied_distinguishesALiveListenerFromAFreePort() throws Exception {
        int port;
        try (var held = boundLoopbackSocket()) {
            port = held.getLocalPort();
            assertTrue(LocalSidecarDaemon.portOccupied(port), "a live listener must read as occupied");
        }
        assertFalse(LocalSidecarDaemon.portOccupied(port), "the port must read as free once released");
    }

    /** JCLAW-989: a child that died of EADDRINUSE must name the squatter. "check the logs"
     *  is unactionable there — the logs belong to the squatter's long-dead JVM. */
    @Test
    void startupExitMessage_namesTheHeldPortWhenSomethingElseHasIt() throws Exception {
        try (var held = boundLoopbackSocket()) {
            var msg = new LocalSidecarDaemon(configOnPort(held.getLocalPort())).startupExitMessage(1);
            assertTrue(msg.contains("already held"), msg);
            assertTrue(msg.contains(String.valueOf(held.getLocalPort())), msg);
        }
    }

    /** JCLAW-989: a child that died of its own startup error keeps the original message —
     *  the port is free, so there is no squatter to blame. */
    @Test
    void startupExitMessage_fallsBackToTheLogHintWhenThePortIsFree() throws Exception {
        int port;
        try (var s = boundLoopbackSocket()) {
            port = s.getLocalPort();
        }
        var msg = new LocalSidecarDaemon(configOnPort(port)).startupExitMessage(2);
        assertTrue(msg.endsWith("check the logs"), msg);
        assertFalse(msg.contains("already held"), msg);
    }

    // ---- JCLAW-990: reaping an ownerless sidecar that squats the configured port ----

    /** The absolute cache dir {@link #configOnPort} resolves to — the argv token that pins a
     *  match to this application and domain. */
    private static String testCacheDir() {
        return new File(Play.applicationPath, "data/none").getAbsolutePath();
    }

    private static String[] listenerArgv(int port, String cacheDir) {
        return new String[] {"serve.py", "--host", "127.0.0.1", "--port", String.valueOf(port),
                "--model", "asr", "--cache-dir", cacheDir, "--idle-timeout-min", "15"};
    }

    /** JCLAW-990: both real process shapes must match — the {@code uv run} launcher and the
     *  python listener it forks (argv observed on macOS 25.5 / JDK 25). */
    @Test
    void argvIdentifiesSidecar_matchesBothTheLauncherAndTheListener() {
        assertTrue(LocalSidecarDaemon.argvIdentifiesSidecar(
                listenerArgv(9529, "/app/data/asr-models"), 9529, "/app/data/asr-models"),
                "the forked python listener must match");
        assertTrue(LocalSidecarDaemon.argvIdentifiesSidecar(
                new String[] {"run", "serve.py", "--host", "127.0.0.1", "--port", "9529",
                        "--model", "asr", "--cache-dir", "/app/data/asr-models"},
                9529, "/app/data/asr-models"),
                "the uv launcher must match");
    }

    /** JCLAW-990: the safety-critical negative. Sidecar ports are fixed config, not per-worktree
     *  like PLAY_TEST_PORT, so a sibling clone can legitimately hold the same port — the cache
     *  directory is the only thing distinguishing it, and it must be decisive. */
    @Test
    void argvIdentifiesSidecar_refusesASiblingCloneOnTheSamePort() {
        assertFalse(LocalSidecarDaemon.argvIdentifiesSidecar(
                listenerArgv(9529, "/other-clone/data/asr-models"), 9529, "/app/data/asr-models"),
                "same port, different clone — must never match");
    }

    @Test
    void argvIdentifiesSidecar_refusesAnotherPortAndNonSidecarProcesses() {
        assertFalse(LocalSidecarDaemon.argvIdentifiesSidecar(
                listenerArgv(9530, "/app/data/asr-models"), 9529, "/app/data/asr-models"),
                "a sidecar on a different port is not ours to kill");
        assertFalse(LocalSidecarDaemon.argvIdentifiesSidecar(
                new String[] {"--port", "9529", "--cache-dir", "/app/data/asr-models"},
                9529, "/app/data/asr-models"),
                "without serve.py it is not a sidecar at all");
    }

    /** JCLAW-990: a process with a live parent belongs to that JVM — possibly a sibling
     *  worktree's — so it must never be reapable. */
    @Test
    void hasNoLiveOwner_isFalseForAProcessThisJvmOwns() throws Exception {
        var child = new ProcessBuilder("/bin/sh", "-c", "sleep 30; exit 0").start();
        try {
            assertFalse(LocalSidecarDaemon.hasNoLiveOwner(child.toHandle()),
                    "a child of the running JVM has a live owner and is never reapable");
        } finally {
            child.destroyForcibly();
        }
    }

    /** A listener that accepts then closes without replying — the JCLAW-989 wedge shape, so
     *  {@code isHealthy()} fails fast instead of burning the full read timeout. */
    private static ServerSocket wedgedListener() throws Exception {
        var socket = new ServerSocket();
        socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 4);
        Thread.ofVirtual().start(() -> {
            while (!socket.isClosed()) {
                try {
                    socket.accept().close();
                } catch (IOException _) {
                    return;
                }
            }
        });
        return socket;
    }

    /** Spawn a genuinely ownerless process carrying a sidecar's argv. The inner shell is
     *  backgrounded and the outer exits, so init adopts it; the command is compound because a
     *  shell given a single simple command exec's it and loses the argv that identifies it. */
    private static ProcessHandle spawnOwnerlessSidecar(int port, String cacheDir) throws Exception {
        new ProcessBuilder("/bin/sh", "-c",
                "/bin/sh -c 'sleep 60; exit 0' serve.py --port %d --cache-dir %s &"
                        .formatted(port, cacheDir)).start().waitFor();
        for (int i = 0; i < 60; i++) {
            // Require the child too: the shell forks it a moment after exec, and it stands in
            // for the python listener that actually holds the socket.
            var found = matching(port, cacheDir)
                    .filter(h -> h.descendants().findAny().isPresent())
                    .findFirst();
            if (found.isPresent()) return found.get();
            Thread.sleep(100);
        }
        throw new IllegalStateException("ownerless sidecar fixture never appeared");
    }

    /** A kill is asynchronous; wait for it rather than racing it. */
    private static boolean awaitDead(ProcessHandle h) throws InterruptedException {
        for (int i = 0; i < 50 && h.isAlive(); i++) {
            Thread.sleep(100);
        }
        return !h.isAlive();
    }

    private static Stream<ProcessHandle> matching(int port, String cacheDir) {
        return ProcessHandle.allProcesses().filter(h -> h.info().arguments()
                .map(args -> LocalSidecarDaemon.argvIdentifiesSidecar(args, port, cacheDir))
                .orElse(false));
    }

    /** JCLAW-990 AC1: an ownerless sidecar squatting the port is reaped, along with the
     *  descendant that actually holds the socket. */
    @Test
    void reapOrphanedSquatter_killsAnOwnerlessSidecarAndItsDescendants() throws Exception {
        var squatted = wedgedListener();
        int port = squatted.getLocalPort();
        var cacheDir = testCacheDir();
        ProcessHandle orphan = null;
        try {
            orphan = spawnOwnerlessSidecar(port, cacheDir);
            var descendants = orphan.descendants().toList();
            assertFalse(descendants.isEmpty(), "fixture must have a child standing in for the listener");

            assertTrue(new LocalSidecarDaemon(configOnPort(port)).reapOrphanedSquatter(),
                    "an ownerless, unresponsive sidecar on our port must be reaped");

            assertTrue(awaitDead(orphan), "the ownerless sidecar must be dead");
            for (var d : descendants) {
                assertTrue(awaitDead(d), "descendants hold the socket and must die too: pid " + d.pid());
            }
        } finally {
            if (orphan != null) orphan.destroyForcibly();
            matching(port, cacheDir).forEach(ProcessHandle::destroyForcibly);
            squatted.close();
        }
    }

    /** JCLAW-990 AC2/AC3: a port that answers /health is an adopted (JCLAW-637) or busy sidecar.
     *  Even with a matching ownerless process present, nothing may be killed. */
    @Test
    void reapOrphanedSquatter_neverTouchesAPortThatAnswersHealth() throws Exception {
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/health", exchange -> {
            var body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        var cacheDir = testCacheDir();
        ProcessHandle orphan = null;
        try {
            orphan = spawnOwnerlessSidecar(port, cacheDir);

            assertFalse(new LocalSidecarDaemon(configOnPort(port)).reapOrphanedSquatter(),
                    "a port answering /health must never be reaped");
            assertTrue(orphan.isAlive(),
                    "the health gate, not the absence of a match, is what protects it");
        } finally {
            if (orphan != null) orphan.destroyForcibly();
            matching(port, cacheDir).forEach(ProcessHandle::destroyForcibly);
            server.stop(0);
        }
    }

    /** JCLAW-990: a free port means there is nothing to recover from — no process scan, no kill. */
    @Test
    void reapOrphanedSquatter_isANoopWhenThePortIsFree() throws Exception {
        int port;
        try (var s = boundLoopbackSocket()) {
            port = s.getLocalPort();
        }
        assertFalse(new LocalSidecarDaemon(configOnPort(port)).reapOrphanedSquatter(),
                "nothing is squatting a free port");
    }

    /** JCLAW-830: stop() on a daemon that never spawned is a safe, idempotent no-op. */
    @Test
    void stop_onIdleDaemonIsSafeNoop() {
        var daemon = new LocalSidecarDaemon(testConfig());
        daemon.stop();
        daemon.stop();
        assertFalse(daemon.hasProcess(), "no process handle after stop on an idle daemon");
    }
}
