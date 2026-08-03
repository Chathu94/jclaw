package jobs;

import memory.MemoryStoreFactory;
import memory.MemoryVectorSettings;
import models.Memory;
import play.Play;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.ConfigService;
import services.EventLogger;
import services.Tx;

import java.util.List;

/**
 * JCLAW-922: embed the memories that predate vector memory being switched on.
 *
 * <p>Embeddings are written only by {@code store}, so every row created while
 * {@code memory.jpa.vector.enabled} was false has no vector and sits outside the KNN
 * graph. Semantic dedup and the vector recall leg would both silently see an empty
 * corpus until each such row happened to be rewritten — which, for durable memories,
 * is never. This job closes that gap once.
 *
 * <p>Guarded by a config marker rather than by inspecting the index: neither backend
 * can cheaply answer "which rows lack a vector" (Lucene has no negative-field query
 * on a KNN field, and on Postgres the column is invisible to JPA). Re-embedding an
 * already-embedded row is harmless — {@code store()} is deterministic per (model,
 * text) and the write is an upsert — so a marker is enough and cannot corrupt state
 * if it is cleared to force a rerun after a model change.
 */
@OnApplicationStart(async = true)
@NoTransaction
public class MemoryEmbeddingBackfillJob extends Job<Void> {

    private static final String MARKER = "memory.jpa.vector.backfilledForModel";

    @Override
    public void doJob() {
        if (Play.runningInTestMode()) return;
        if (!MemoryVectorSettings.enabled()) return;

        var model = MemoryVectorSettings.model();
        if (model.equals(ConfigService.get(MARKER, ""))) return;

        // Ids only, in a short read tx: embedStored re-reads each row itself, and the
        // embedding round-trip must not run with this connection held.
        List<Long> ids = Tx.run(() -> Memory.<Memory>find("supersededAt IS NULL ORDER BY id").<Memory>fetch()
                .stream().map(m -> m.id).toList());
        if (ids.isEmpty()) {
            ConfigService.set(MARKER, model);
            return;
        }

        EventLogger.info("memory", "Embedding backfill starting for %d memories (model %s)"
                .formatted(ids.size(), model));
        var store = MemoryStoreFactory.get();
        int done = 0;
        for (var id : ids) {
            try {
                store.embedStored(String.valueOf(id));
                done++;
            } catch (Exception e) {
                EventLogger.warn("memory", "Embedding backfill failed for memory %d: %s"
                        .formatted(id, e.getMessage()));
            }
        }
        // Marker only on a clean sweep, so a provider outage mid-run retries next boot
        // instead of leaving most of the corpus permanently invisible to the KNN leg.
        if (done == ids.size()) {
            ConfigService.set(MARKER, model);
        }
        EventLogger.info("memory", "Embedding backfill complete: %d/%d memories embedded"
                .formatted(done, ids.size()));
    }
}
