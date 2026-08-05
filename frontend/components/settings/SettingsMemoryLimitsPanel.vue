<script setup lang="ts">
/**
 * How many memories reach the prompt.
 *
 * These two counts are the ONLY bounds on their blocks. The token budgets that used
 * to sit alongside them were removed (JCLAW-955, JCLAW-979) because they dropped
 * whichever memory happened to be verbose rather than whichever ranked lowest, and
 * did it silently. So what is set here decides the whole memory footprint of a turn,
 * which is why it belongs in Settings rather than in the config table alone.
 */
import type { CoreMigrationStatus } from '~/types/api'

const { configValue, saveField, saving } = useSettingsConfig()

/** Mirrors the code defaults in agents.SystemPromptAssembler. */
const MemoryLimitKeys = {
  coreMaxCount: 'memory.coreload.maxCount',
  recallLimit: 'memory.recall.limit',
} as const

const savedCoreMaxCount = computed(() => configValue(MemoryLimitKeys.coreMaxCount, '20'))
const savedRecallLimit = computed(() => configValue(MemoryLimitKeys.recallLimit, '10'))

const coreMaxCount = ref(savedCoreMaxCount.value)
const recallLimit = ref(savedRecallLimit.value)

const isDirty = computed(() =>
  coreMaxCount.value !== savedCoreMaxCount.value || recallLimit.value !== savedRecallLimit.value)

/** Both are counts of whole memories, so below 1 silently disables the block. */
const isValid = computed(() =>
  Number.isInteger(Number(coreMaxCount.value)) && Number(coreMaxCount.value) >= 1
  && Number.isInteger(Number(recallLimit.value)) && Number(recallLimit.value) >= 1)

async function saveLimits() {
  if (!isDirty.value || !isValid.value) return
  await saveField(MemoryLimitKeys.coreMaxCount, String(Number(coreMaxCount.value)))
  await saveField(MemoryLimitKeys.recallLimit, String(Number(recallLimit.value)))
  await refreshMigration()
}

/**
 * Core-memory migration (JCLAW-981).
 *
 * The memory tool refuses a core write past the cap, but it cannot make the agent ask
 * before filing the fact elsewhere — that half is instruction to a model. So bringing an
 * over-cap corpus back in line is an operator action, and this is it. Each overflow memory
 * is recategorised by its own agent's model rather than flattened into one bucket.
 */
const migration = ref<CoreMigrationStatus | null>(null)
const migrationError = ref('')

async function refreshMigration() {
  try {
    migration.value = await $fetch<CoreMigrationStatus>('/api/memories/core-migration')
  }
  catch {
    migration.value = null
  }
}

async function startMigration() {
  migrationError.value = ''
  try {
    migration.value = await $fetch<CoreMigrationStatus>('/api/memories/core-migration',
      { method: 'POST' })
    pollWhileRunning()
  }
  catch (e) {
    migrationError.value = (e as { data?: { message?: string } })?.data?.message
      ?? 'Could not start the migration.'
  }
}

/** Poll only while a run is in flight; the status is otherwise static. */
function pollWhileRunning() {
  if (!migration.value?.running) return
  setTimeout(async () => {
    await refreshMigration()
    pollWhileRunning()
  }, 1000)
}

onMounted(refreshMigration)
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Limits
    </h2>
    <p class="text-xs text-fg-muted">
      How many memories reach the prompt each turn. Core memories load on every turn regardless of
      what was said; recalled memories are matched against the current message. Both are counts of
      whole memories — a memory that is selected is always injected in full, never truncated.
    </p>

    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-3 space-y-3">
        <div>
          <label
            for="memory-core-max-count"
            class="block"
          >
            <span class="block text-xs font-medium text-fg-strong mb-1">Core memories per turn</span>
            <input
              id="memory-core-max-count"
              v-model="coreMaxCount"
              type="number"
              min="1"
              class="w-full px-2 py-1.5 text-sm bg-surface border border-input text-fg-strong"
              data-testid="memory-core-max-count"
            >
          </label>
          <p class="mt-1 text-[11px] text-fg-muted">
            The highest-importance durable facts, always in context. Ordered by importance, so
            lowering this drops the least important first.
          </p>
        </div>

        <div>
          <label
            for="memory-recall-limit"
            class="block"
          >
            <span class="block text-xs font-medium text-fg-strong mb-1">Recalled memories per turn</span>
            <input
              id="memory-recall-limit"
              v-model="recallLimit"
              type="number"
              min="1"
              class="w-full px-2 py-1.5 text-sm bg-surface border border-input text-fg-strong"
              data-testid="memory-recall-limit"
            >
          </label>
          <p class="mt-1 text-[11px] text-fg-muted">
            Matched against each message and ranked by relevance, importance and age. Lowering this
            drops the lowest-ranked first.
          </p>
        </div>

        <div class="flex items-center gap-2">
          <button
            type="button"
            class="px-3 py-1.5 text-xs border border-border hover:bg-muted/40 transition-colors disabled:opacity-50"
            :disabled="!isDirty || !isValid || saving"
            data-testid="memory-limits-save"
            @click="saveLimits"
          >
            Save
          </button>
          <span
            v-if="!isValid"
            class="text-[11px] text-red-700 dark:text-red-400"
            data-testid="memory-limits-invalid"
          >Both limits must be a whole number of at least 1.</span>
        </div>
      </div>
    </div>

    <!-- Core-memory migration: the enforceable half of the cap -->
    <div
      v-if="migration"
      class="bg-surface-elevated border border-border"
    >
      <div class="px-4 py-3 space-y-2">
        <div class="flex items-center justify-between gap-3">
          <div class="min-w-0">
            <span class="text-sm font-medium text-fg-strong">Stored core memories</span>
            <span
              v-if="migration.running"
              class="ml-2 text-[10px] text-amber-700 dark:text-amber-400 border border-amber-400/40 px-1"
            >migrating</span>
            <span
              v-else-if="migration.overCap"
              class="ml-2 text-[10px] text-amber-700 dark:text-amber-400 border border-amber-400/40 px-1"
              data-testid="memory-core-over-cap"
            >over the limit</span>
            <span
              v-else
              class="ml-2 text-[10px] text-green-700 dark:text-green-400 border border-green-400/30 px-1"
            >within the limit</span>
          </div>
          <button
            type="button"
            class="px-3 py-1.5 text-xs border border-border hover:bg-muted/40 transition-colors disabled:opacity-50"
            :disabled="!migration.overCap || migration.running"
            data-testid="memory-core-migrate"
            @click="startMigration"
          >
            {{ migration.running ? 'Migrating…' : 'Migrate excess' }}
          </button>
        </div>

        <p class="text-xs text-fg-muted">
          {{ migration.liveCore }} stored, {{ migration.cap }} allowed.
          <template v-if="migration.overCap">
            The excess is not loaded into any turn — it holds the core category without the
            benefit. Migrating asks each memory's own agent to file it under the category that
            fits it best; nothing is deleted, and anything the model cannot classify stays core
            so you can run this again.
          </template>
          <template v-else>
            Core memories are added only when you ask the agent to remember something. Anything
            it notices on its own is captured under another category.
          </template>
        </p>

        <p
          v-if="migration.running && migration.total > 0"
          class="text-xs text-fg-muted"
          data-testid="memory-core-migrate-progress"
        >
          Recategorised {{ migration.processed }} of {{ migration.total }}.
        </p>
        <p
          v-if="migrationError"
          class="text-xs text-red-700 dark:text-red-400"
          data-testid="memory-core-migrate-error"
        >
          {{ migrationError }}
        </p>
        <p
          v-else-if="migration.error"
          class="text-xs text-red-700 dark:text-red-400"
        >
          Last run failed: {{ migration.error }}
        </p>
      </div>
    </div>
  </div>
</template>
