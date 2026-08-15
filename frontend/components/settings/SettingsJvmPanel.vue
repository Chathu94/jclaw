<script setup lang="ts">
// JVM runtime state, shown under Performance above the dispatcher caps that are
// tuned against it (JCLAW-1057).
//
// Polled rather than snapshotted, for two reasons beyond "the numbers move":
// GC counters are cumulative since JVM start and are close to meaningless raw —
// the useful figure is the delta between samples — and getProcessCpuLoad()
// commonly reports "unavailable" on its first call, so a single read is less
// reliable than a polled one.

interface JvmStats {
  heapUsed: number
  heapCommitted: number
  heapMax: number
  nonHeapUsed: number
  nonHeapCommitted: number
  /** Null where the platform has no supported way to report it — never a heap figure. */
  rssBytes: number | null
  gcCount: number
  gcTimeMs: number
  platformThreads: number
  peakPlatformThreads: number
  uptimeMs: number
  /** Null when the JVM declines to report a share. */
  processCpuLoad: number | null
  availableProcessors: number
}

const REFRESH_MS = 5_000

const stats = ref<JvmStats | null>(null)
const previous = ref<JvmStats | null>(null)
const failed = ref(false)

let timer: ReturnType<typeof setInterval> | null = null

async function load() {
  try {
    const next = await $fetch<JvmStats>('/api/metrics/jvm', { retry: 0 })
    // Keep the prior sample so the GC rate below has two points to work from.
    previous.value = stats.value
    stats.value = next
    failed.value = false
  }
  catch {
    failed.value = true
  }
}

function start() {
  if (timer) return
  timer = setInterval(load, REFRESH_MS)
}

function stop() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

// A settings tab left open in the background would otherwise poll for the life of
// the session; nothing here is worth a request nobody is looking at.
function onVisibility() {
  if (document.visibilityState === 'visible') {
    load()
    start()
  }
  else {
    stop()
  }
}

onMounted(() => {
  load()
  start()
  document.addEventListener('visibilitychange', onVisibility)
})

onBeforeUnmount(() => {
  stop()
  document.removeEventListener('visibilitychange', onVisibility)
})

function bytes(n: number | null): string {
  if (n === null || n < 0) return '—'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let v = n
  let u = 0
  while (v >= 1024 && u < units.length - 1) {
    v /= 1024
    u++
  }
  return `${v < 10 && u > 0 ? v.toFixed(1) : Math.round(v)} ${units[u]}`
}

function duration(ms: number): string {
  const s = Math.floor(ms / 1000)
  const d = Math.floor(s / 86400)
  const h = Math.floor((s % 86400) / 3600)
  const m = Math.floor((s % 3600) / 60)
  if (d > 0) return `${d}d ${h}h`
  if (h > 0) return `${h}h ${m}m`
  if (m > 0) return `${m}m ${s % 60}s`
  return `${s}s`
}

const heapLabel = computed(() => {
  const s = stats.value
  if (!s) return '—'
  const max = s.heapMax < 0 ? 'no ceiling' : bytes(s.heapMax)
  return `${bytes(s.heapUsed)} of ${bytes(s.heapCommitted)} held (max ${max})`
})

const cpuLabel = computed(() => {
  const c = stats.value?.processCpuLoad
  // Distinguish "not reported" from "idle": 0% is a measurement, — is the absence of one.
  return c === null || c === undefined ? '—' : `${(c * 100).toFixed(1)}%`
})

/** Collections since the previous sample — the cumulative total says little on its own. */
const gcLabel = computed(() => {
  const s = stats.value
  if (!s) return '—'
  const p = previous.value
  const delta = p ? s.gcCount - p.gcCount : null
  const total = `${s.gcCount.toLocaleString()} total, ${duration(s.gcTimeMs)} spent`
  return delta === null ? total : `+${delta} since last sample · ${total}`
})
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Runtime
    </h2>
    <p class="text-xs text-fg-muted">
      Live state of the JVM serving this page, refreshed every few seconds while this
      section is open. Memory is reported three ways because they measure different
      things — see below.
    </p>

    <div
      v-if="failed && !stats"
      class="bg-surface-elevated border border-border px-4 py-2.5 text-xs text-fg-muted"
    >
      Could not read the JVM metrics.
    </div>

    <div
      v-else
      class="bg-surface-elevated border border-border"
    >
      <dl class="grid grid-cols-2 sm:grid-cols-3 gap-4 p-4">
        <div>
          <dt class="text-[10px] font-medium uppercase tracking-wider text-fg-muted">
            Heap
          </dt>
          <dd
            class="mt-1 text-sm font-mono text-fg-primary"
            data-testid="jvm-heap"
          >
            {{ heapLabel }}
          </dd>
        </div>
        <div>
          <dt class="text-[10px] font-medium uppercase tracking-wider text-fg-muted">
            Non-heap
          </dt>
          <dd
            class="mt-1 text-sm font-mono text-fg-primary"
            data-testid="jvm-nonheap"
          >
            {{ stats ? bytes(stats.nonHeapUsed) : '—' }}
          </dd>
        </div>
        <div>
          <dt class="text-[10px] font-medium uppercase tracking-wider text-fg-muted">
            Process memory
          </dt>
          <dd
            class="mt-1 text-sm font-mono text-fg-primary"
            data-testid="jvm-rss"
          >
            {{ stats ? bytes(stats.rssBytes) : '—' }}
          </dd>
        </div>
        <div>
          <dt class="text-[10px] font-medium uppercase tracking-wider text-fg-muted">
            CPU
          </dt>
          <dd
            class="mt-1 text-sm font-mono text-fg-primary"
            data-testid="jvm-cpu"
          >
            {{ cpuLabel }}
            <span class="text-fg-muted">of {{ stats?.availableProcessors ?? '—' }} cores</span>
          </dd>
        </div>
        <div>
          <dt class="text-[10px] font-medium uppercase tracking-wider text-fg-muted">
            Garbage collection
          </dt>
          <dd
            class="mt-1 text-sm font-mono text-fg-primary"
            data-testid="jvm-gc"
          >
            {{ gcLabel }}
          </dd>
        </div>
        <div>
          <dt class="text-[10px] font-medium uppercase tracking-wider text-fg-muted">
            Uptime
          </dt>
          <dd class="mt-1 text-sm font-mono text-fg-primary">
            {{ stats ? duration(stats.uptimeMs) : '—' }}
          </dd>
        </div>
        <div>
          <dt class="text-[10px] font-medium uppercase tracking-wider text-fg-muted">
            Platform threads
          </dt>
          <dd
            class="mt-1 text-sm font-mono text-fg-primary"
            data-testid="jvm-threads"
          >
            {{ stats ? stats.platformThreads : '—' }}
            <span class="text-fg-muted">peak {{ stats?.peakPlatformThreads ?? '—' }}</span>
          </dd>
        </div>
      </dl>

      <p class="border-t border-border px-4 py-2.5 text-xs text-fg-muted">
        <span class="font-medium">Process memory</span> is what the operating system
        charges JClaw and is the figure to compare against the machine's RAM. It sits
        well above the heap, because the JVM also holds non-heap memory and reserves
        address space the heap has not filled — a large gap is normal, not a leak.
        <span class="font-medium">Platform threads</span> excludes virtual threads,
        which is where chat turns and tool calls actually run, so a low, flat count here
        does not mean the instance is idle.
      </p>
    </div>
  </div>
</template>
