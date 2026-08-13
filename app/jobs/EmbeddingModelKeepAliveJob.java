package jobs;

import play.Play;
import play.db.jpa.NoTransaction;
import play.jobs.Every;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.EmbeddingModelKeepAlive;

/**
 * Hold a locally-served embedding model in memory so no chat turn pays a model load.
 *
 * <p>Recurring rather than boot-only, which is where this differs from
 * {@link OllamaLocalProbeJob}: that probe reports configuration, which does not change while
 * the process runs, whereas residency lapses on its own timer and has to be renewed.
 * Renewing also means warmth survives the model server being restarted underneath a running
 * JClaw.
 *
 * <p>Half-hourly, not minutes: the pin is a directive rather than a touch, so the interval
 * only has to outlast the ways a pin is lost rather than beat an idle timer. Renewed for
 * every backend including Ollama, because {@code keep_alive: -1} is best-effort — the
 * scheduler evicts under model-load pressure regardless of it, and once that happens nothing
 * else would restore the pin until JClaw itself restarted.
 */
@OnApplicationStart(async = true)
@Every("30mn")
@NoTransaction
public class EmbeddingModelKeepAliveJob extends Job<Void> {

    @Override
    public void doJob() {
        // Same reasoning as OllamaLocalProbeJob: tests point at mock servers, so the real
        // call buys no signal and adds boot latency plus a path for external flakiness.
        if (Play.runningInTestMode()) return;
        EmbeddingModelKeepAlive.pin();
    }
}
