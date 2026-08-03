/**
 * Embedding-model naming heuristics for the Settings picker (JCLAW-932).
 *
 * There is no capability flag to filter on: ProviderModelDef carries
 * supportsThinking/Vision/Audio/Video but nothing about embeddings, and no
 * provider's /v1/models marks which models embed. This shortlist only keeps the
 * dropdown short — the probe (JCLAW-931) is what actually accepts a model, so a
 * false negative here costs a click on "show all", not a blocked selection.
 */

/** The config keys backing the panel, mirroring memory.MemoryVectorSettings. */
export const MemoryVectorKeys = {
  enabled: 'memory.jpa.vector.enabled',
  provider: 'memory.jpa.vector.provider',
  model: 'memory.jpa.vector.model',
  dimensions: 'memory.jpa.vector.dimensions',
} as const

/**
 * Substrings that appear in embedding-model ids across the providers seen so far:
 * OpenAI (text-embedding-3-*), Nomic, BAAI BGE, MixedBread, Google GTE,
 * sentence-transformers MiniLM, Cohere embed-*, Voyage.
 */
const EMBEDDING_HINTS = [
  'embed', 'embedding', 'bge', 'nomic', 'mxbai', 'gte', 'minilm', 'e5-', 'voyage',
]

export function looksLikeEmbeddingModel(id: string, name?: string): boolean {
  const haystack = `${id} ${name ?? ''}`.toLowerCase()
  return EMBEDDING_HINTS.some(h => haystack.includes(h))
}
