<script setup lang="ts">
// Log disk footprint, shown under Logging beside the levels that produce it
// (JCLAW-1057).
//
// Read once on mount rather than polled: unlike the JVM figures next to it, this
// moves at kilobytes per minute and pruning happens on daily rollover, so a
// refreshing number would be motion without information.

interface LogFootprint {
  /** -1 when the appender's current file is not on disk yet. */
  liveBytes: number
  archiveCount: number
  archiveBytes: number
  /** Whole directory, so ad-hoc files cannot hide from the total. */
  totalBytes: number
  retentionDays: number
}

const { data: logs, refresh } = useLazyFetch<LogFootprint>('/api/metrics/logs')
const { confirm } = useConfirm()
const { mutate } = useApiMutation()

const purging = ref(false)
const purgeResult = ref<string | null>(null)

async function purgeArchives() {
  const l = logs.value
  if (!l || l.archiveCount === 0) return

  const ok = await confirm({
    title: 'Delete archived logs?',
    message: `${l.archiveCount} archived log file${l.archiveCount === 1 ? '' : 's'} `
      + `(${bytes(l.archiveBytes)}) will be deleted. The current log is kept. This `
      + 'discards the history you would need to investigate anything that has already '
      + 'happened, and cannot be undone.',
    confirmText: 'Delete archives',
    variant: 'danger',
  })
  if (!ok) return

  purging.value = true
  const res = await mutate<{ deleted: number, freedBytes: number }>('/api/metrics/logs',
    { method: 'DELETE' })
  purging.value = false
  if (!res) {
    purgeResult.value = 'Could not delete the archives.'
    return
  }
  purgeResult.value = `Deleted ${res.deleted} archive${res.deleted === 1 ? '' : 's'}, `
    + `freeing ${bytes(res.freedBytes)}.`
  await refresh()
}

function bytes(n: number): string {
  if (n < 0) return '—'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let v = n
  let u = 0
  while (v >= 1024 && u < units.length - 1) {
    v /= 1024
    u++
  }
  return `${v < 10 && u > 0 ? v.toFixed(1) : Math.round(v)} ${units[u]}`
}

const archiveLabel = computed(() => {
  const l = logs.value
  if (!l) return '—'
  if (l.archiveCount === 0) return 'none yet'
  return `${l.archiveCount} file${l.archiveCount === 1 ? '' : 's'}, ${bytes(l.archiveBytes)}`
})
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Logs
    </h2>
    <p class="text-xs text-fg-muted">
      Disk used by the log directory. The current file is capped and rolled over
      automatically; the archives are what accumulate.
    </p>

    <div class="bg-surface-elevated border border-border">
      <dl class="grid grid-cols-2 sm:grid-cols-3 gap-4 p-4">
        <div>
          <dt class="text-[10px] font-medium uppercase tracking-wider text-fg-muted">
            Current log
          </dt>
          <dd
            class="mt-1 text-sm font-mono text-fg-primary"
            data-testid="logs-live"
          >
            {{ logs ? bytes(logs.liveBytes) : '—' }}
          </dd>
        </div>
        <div>
          <dt class="text-[10px] font-medium uppercase tracking-wider text-fg-muted">
            Archives
          </dt>
          <dd
            class="mt-1 text-sm font-mono text-fg-primary"
            data-testid="logs-archives"
          >
            {{ archiveLabel }}
          </dd>
        </div>
        <div>
          <dt class="text-[10px] font-medium uppercase tracking-wider text-fg-muted">
            Total on disk
          </dt>
          <dd
            class="mt-1 text-sm font-mono text-fg-primary"
            data-testid="logs-total"
          >
            {{ logs ? bytes(logs.totalBytes) : '—' }}
          </dd>
        </div>
      </dl>

      <div class="border-t border-border px-4 py-2.5 flex items-center justify-between gap-4">
        <p class="text-xs text-fg-muted">
          Archives older than {{ logs?.retentionDays ?? 30 }} days are deleted at the next
          daily rollover, so this is only for reclaiming the disk sooner.
          <span
            v-if="purgeResult"
            class="text-fg-strong"
          >{{ purgeResult }}</span>
        </p>
        <button
          type="button"
          class="shrink-0 px-3 py-1.5 text-xs font-medium text-fg-muted hover:text-fg-strong
                 border border-border rounded-full transition-colors disabled:opacity-40
                 disabled:cursor-not-allowed"
          :disabled="purging || !logs?.archiveCount"
          @click="purgeArchives"
        >
          {{ purging ? 'Deleting…' : 'Delete archives' }}
        </button>
      </div>
    </div>
  </div>
</template>
