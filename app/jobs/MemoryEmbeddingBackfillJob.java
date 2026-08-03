package jobs;

import memory.MemoryReembedService;
import memory.MemoryVectorSettings;
import play.Play;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.EventLogger;

/**
 * JCLAW-922: embed the memories that predate vector memory being switched on, and
 * JCLAW-933: pick up an embedding-model switch made while the instance was down.
 *
 * <p>Embeddings are written only by {@code store}, so every row created while
 * {@code memory.jpa.vector.enabled} was false has no vector and sits outside the KNN
 * graph. Semantic dedup and the vector recall leg would both silently see an empty
 * corpus until each such row happened to be rewritten — which, for durable memories,
 * is never.
 *
 * <p>The work lives in {@link MemoryReembedService}, which the operator can also trigger
 * from Settings. Sharing it means the boot path gets the same mandatory index wipe: a
 * model changed in the config store while the instance was down would otherwise hit
 * Lucene's per-field dimension pin on the first write. The service also owns the
 * marker, which it sets only after a clean sweep, so a provider outage mid-run retries
 * next boot rather than leaving most of the corpus invisible to the KNN leg.
 */
@OnApplicationStart(async = true)
@NoTransaction
public class MemoryEmbeddingBackfillJob extends Job<Void> {

    @Override
    public void doJob() {
        if (Play.runningInTestMode()) return;
        if (!MemoryVectorSettings.enabled()) return;
        if (MemoryReembedService.upToDate()) return;

        var refusal = MemoryReembedService.start();
        if (refusal != null) {
            EventLogger.warn("memory", "Embedding backfill did not start: %s".formatted(refusal));
        }
    }
}
