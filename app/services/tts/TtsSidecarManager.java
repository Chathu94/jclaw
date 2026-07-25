package services.tts;

import play.Logger;
import services.LocalSidecarDaemon;
import services.Tx;
import services.UvProbe;

/**
 * Lifecycle owner for the TTS Python sidecar (JCLAW-789): Qwen3-TTS / Kokoro via
 * mlx-audio on Apple silicon (the NVIDIA/vLLM leg is deferred to the JCLAW-788
 * RTX 4090 validation). One sidecar per JVM, reached over
 * {@code 127.0.0.1:<tts.local.port>}. The spawn/drain/health/stop mechanism is
 * shared with the ASR/imagegen/videogen daemons via {@link LocalSidecarDaemon}.
 *
 * <p>Registered for graceful shutdown via {@code jobs.ShutdownJob}. The daemon
 * self-evicts after its idle timeout, so callers always go through
 * {@link #ensureRunning()} rather than caching the running state. Mirrors
 * {@link services.transcription.AsrSidecarManager}.
 */
public final class TtsSidecarManager {

    /** Identity string the daemon reports on /health — a stable fingerprint,
     *  not a model id (the TTS model is chosen per request). */
    public static final String IDENTITY = "tts";
    static final String CONFIG_PREFIX = "tts.local";

    private static final LocalSidecarDaemon DAEMON = new LocalSidecarDaemon(new LocalSidecarDaemon.Config(
            "sidecar/tts", "data/tts-models", CONFIG_PREFIX, 9531, 300,
            "tts", "tts-sidecar", "TTS sidecar",
            "the first launch installs the Python TTS deps (mlx-audio) via uv",
            TtsException::new));

    private TtsSidecarManager() {}

    /**
     * Ensure the sidecar is up and return its base URL. Idempotent and
     * single-flight (JCLAW-830): the spawn + health-await run under the daemon's
     * {@code startLock} — a separate lock from the one {@code stop()} uses — so a
     * concurrent starter waits for the in-flight spawn and then no-ops on the
     * re-check, while {@code stop()}/idle-respawn never stall behind the startup
     * poll. Throws {@link TtsException} when uv is absent, the script is missing,
     * or the daemon doesn't become healthy in time.
     */
    public static String ensureRunning() {
        if (DAEMON.isHealthy(IDENTITY)) return DAEMON.baseUrl();
        return DAEMON.singleFlight(() -> {
            if (DAEMON.isHealthy(IDENTITY)) return DAEMON.baseUrl();
            if (!UvProbe.isAvailable()) {
                throw new TtsException(
                        "the TTS sidecar requires 'uv' on PATH: " + UvProbe.lastResult().reason());
            }
            DAEMON.spawn(IDENTITY, null);
            DAEMON.awaitHealthy();
            // Deliberately NOT prewarming here. ensureRunning is only reachable
            // from synthesizeLocked, which already holds the sidecar lock — so
            // the request that triggered this spawn is ahead of any warm we start,
            // pays the cold load itself, and leaves the warm a no-op. Measured:
            // 18.0s for the triggering synthesis, then 2.0s for the warm behind
            // it. Prewarming has to happen where no request is waiting; see
            // prewarmModelAsync's callers.
            return DAEMON.baseUrl();
        });
    }

    /** Short utterance used to force a model load. Non-blank because the sidecar
     *  rejects empty text, and short so the wasted synthesis is negligible. */
    private static final String PREWARM_TEXT = "ok";

    /**
     * Load the configured sidecar model off the request path (JCLAW-863).
     *
     * <p>The sidecar's own {@code _prewarm} resolves the uv environment but not
     * the model, which is the expensive half — measured at 51.6s cold for
     * Chatterbox against 6-7s warm. In voice mode that cold load lands at or past
     * the client's 60s stall watchdog once STT and generation are added, so the
     * turn is abandoned with no audio and nothing in the server log, because
     * nothing actually failed. Kokoro hides the problem by loading quickly.
     *
     * <p>Warms via a real (tiny) synthesis rather than a load-only worker op, on
     * purpose: engines defer work past model construction. Kokoro installs its
     * spaCy G2P model when the pipeline is first built <em>during synthesis</em> —
     * the JCLAW-859 incident — so a load-only warm would have left exactly that
     * step cold. Warming through the same path the first real request takes is
     * the only way to be sure nothing is left lazy.
     *
     * <p>Detached and best-effort — a model that cannot load is a problem for the
     * request that needs it, not for whoever triggered the warm. The synthesis
     * takes the normal sidecar lock, so a real request arriving mid-warm queues
     * behind it rather than racing, which is the same wait it would have paid
     * loading the model itself.
     *
     * <p><b>Where this is worth calling.</b> Only from a point where no synthesis
     * is already waiting, because the lock is FIFO. Calling it right after a spawn
     * is useless: {@code ensureRunning} is reachable only from inside
     * {@code TtsSidecarClient}'s locked section, so the request that caused the
     * spawn is always ahead of the warm and absorbs the cold load itself. The two
     * useful moments are the operator selecting a model in Settings, and a voice
     * session opening — both leave a gap before anything needs audio.
     */
    public static void prewarmModelAsync() {
        Thread.ofVirtual().name("tts-prewarm").start(() -> {
            try {
                // Config reads inside a transaction: this runs off the request
                // path with no ambient EntityManager, and a cache miss would
                // otherwise throw (the JCLAW-849 / JCLAW-852 failure mode).
                var model = Tx.run(() -> TtsRouter.modelFor(TtsEngine.SIDECAR));
                var voice = Tx.run(() -> TtsRouter.voiceFor(TtsEngine.SIDECAR));
                if (model == null || model.isBlank()) return;

                long t0 = System.nanoTime();
                // Warm through the same shape a real request takes, reference clip
                // included: cloning loads extra state, so warming without it would
                // leave that work for the first real utterance.
                var refAudio = Tx.run(() -> TtsRouter.refAudioFor(TtsEngine.SIDECAR));
                var refText = Tx.run(() -> TtsRouter.refTextFor(TtsEngine.SIDECAR));
                new TtsSidecarClient().synthesize(PREWARM_TEXT, model, voice, "wav", refAudio, refText);
                Logger.info("TtsSidecarManager: prewarmed sidecar model '%s' in %.1fs",
                        model, (System.nanoTime() - t0) / 1e9);
            } catch (RuntimeException e) {
                Logger.warn("TtsSidecarManager: prewarm failed (first real synthesis will pay "
                        + "the load instead): %s", e.getMessage());
            }
        });
    }

    /** Cheap liveness probe for {@code /api/tts/state} — reports whether the
     *  sidecar is already up without spawning it. */
    public static boolean isRunning() {
        return DAEMON.isHealthy(IDENTITY);
    }

    /** Stop the sidecar if running. Wired into {@code jobs.ShutdownJob}. */
    public static void stop() {
        DAEMON.stop();
    }
}
