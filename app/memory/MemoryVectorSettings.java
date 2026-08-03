package memory;

import services.ConfigService;

/**
 * The vector-memory settings, read from the config store (JCLAW-930).
 *
 * <p>These were the last memory settings on {@code Play.configuration}, which made
 * {@code conf/application.conf} their only source and a JVM restart the only way to
 * change them. Everything else in the memory system already reads {@link ConfigService},
 * which is DB-backed, cached for 60s, seeds the cache on write for read-your-writes, and
 * is safe from {@code @OnApplicationStart} — {@code ConversationQueueEvictionJob} and
 * {@code DefaultConfigJob} already depend on that.
 *
 * <p>A change here does not reach {@link JpaMemoryStore} on its own: the store reads all
 * four in its constructor into final fields and {@link MemoryStoreFactory} caches the
 * instance, so {@code ConfigService.setWithSideEffects} resets that singleton when any of
 * these keys is written.
 */
public final class MemoryVectorSettings {

    private MemoryVectorSettings() {}

    /** Prefix the settings side-effect hook matches on to rebuild the store. */
    public static final String KEY_PREFIX = "memory.jpa.vector.";

    public static final String KEY_ENABLED = KEY_PREFIX + "enabled";
    public static final String KEY_PROVIDER = KEY_PREFIX + "provider";
    public static final String KEY_MODEL = KEY_PREFIX + "model";
    public static final String KEY_DIMENSIONS = KEY_PREFIX + "dimensions";

    /** Off unless an operator turns it on: a fresh install has no embedding provider. */
    public static boolean enabled() {
        return ConfigService.getBoolean(KEY_ENABLED, false);
    }

    /**
     * The provider serving embeddings, or blank to fall back to the registry primary.
     * Naming one matters — {@code getPrimary()} is only the alphabetically-first
     * configured provider unless {@code llm.primaryProvider} pins one, which on a
     * multi-provider host is readily a chat endpoint serving no embedding model.
     */
    public static String provider() {
        return ConfigService.get(KEY_PROVIDER, "").trim();
    }

    public static String model() {
        return ConfigService.get(KEY_MODEL, "text-embedding-3-small");
    }

    /**
     * Dimensions of {@link #model}. Sizes the pgvector column DDL; on the Lucene
     * backend the true dimension comes from the embedding array itself. Authoritative
     * values come from probing the model (JCLAW-931), not from operator typing.
     */
    public static int dimensions() {
        return ConfigService.getInt(KEY_DIMENSIONS, 1536);
    }
}
