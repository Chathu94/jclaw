<script setup lang="ts">
/**
 * Non-blocking notice while memories are being re-embedded (JCLAW-934).
 *
 * A re-embed is started from Settings, so the chat has to discover one already in
 * flight rather than being told — hence a poll rather than a prop. It stays slow when
 * idle and speeds up while a run is active, so an operator who never re-embeds pays
 * one request every 15s and someone watching a run gets live progress.
 *
 * The wording is load-bearing. Capture keeps writing throughout: the memory row is the
 * durable artifact and the embedding is a derived index entry, so blocking capture
 * would permanently lose facts stated during a transient reindex. Saying "memories are
 * not being saved" would be false and would push operators to stop using the app for no
 * reason. What actually degrades is duplicate detection and semantic recall.
 */
import type { MemoryReembedStatus } from '~/types/api'

const IDLE_POLL_MS = 15_000
const ACTIVE_POLL_MS = 2_000

const status = ref<MemoryReembedStatus | null>(null)
let timer: ReturnType<typeof setTimeout> | null = null

async function poll() {
  try {
    status.value = await $fetch<MemoryReembedStatus>('/api/memories/reembed')
  }
  catch {
    // A failed poll is not worth surfacing in chat — the next one retries.
  }
  timer = setTimeout(poll, status.value?.running ? ACTIVE_POLL_MS : IDLE_POLL_MS)
}

onMounted(poll)
onBeforeUnmount(() => {
  if (timer) clearTimeout(timer)
})
</script>

<template>
  <div
    v-if="status?.running"
    data-testid="chat-reembed-notice"
    class="mx-auto w-full max-w-3xl px-4 pt-3"
  >
    <div
      class="flex items-center gap-2 px-3 py-2 text-xs bg-amber-50 dark:bg-amber-400/10
             border border-amber-200 dark:border-amber-400/20 text-amber-800 dark:text-amber-300 rounded"
      role="status"
    >
      <span class="font-mono uppercase tracking-wide shrink-0">Re-embedding</span>
      <span>
        <strong>{{ status.processed }}</strong> / {{ status.total }} memories.
        New memories are still being saved — duplicate detection and semantic recall are
        reduced until this finishes.
      </span>
    </div>
  </div>
</template>
