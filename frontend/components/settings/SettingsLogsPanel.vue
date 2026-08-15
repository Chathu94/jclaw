<script setup lang="ts">
// Log disk footprint for the Maintenance section (JCLAW-1057).
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

const { data: logs } = useLazyFetch<LogFootprint>('/api/metrics/logs')

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

      <p class="border-t border-border px-4 py-2.5 text-xs text-fg-muted">
        Archives older than {{ logs?.retentionDays ?? 30 }} days are deleted at the next
        daily rollover. Nothing here needs a manual action.
      </p>
    </div>
  </div>
</template>
