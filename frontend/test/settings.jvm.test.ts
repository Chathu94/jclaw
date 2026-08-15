import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import SettingsJvmPanel from '~/components/settings/SettingsJvmPanel.vue'

/**
 * Settings → Maintenance → Runtime (JCLAW-1057).
 *
 * The assertions concentrate on the ways this panel could quietly lie: reporting an
 * unavailable reading as a real number, collapsing the three memory figures into one,
 * or presenting a cumulative GC counter as though it described the present.
 */

let stats: Record<string, unknown> | null
let requests = 0

registerEndpoint('/api/metrics/jvm', () => {
  requests += 1
  if (stats === null) throw new Error('metrics unavailable')
  return stats
})

function sample(over: Record<string, unknown> = {}) {
  return {
    heapUsed: 268_435_456,
    heapCommitted: 536_870_912,
    heapMax: 2_147_483_648,
    nonHeapUsed: 134_217_728,
    nonHeapCommitted: 150_000_000,
    rssBytes: 1_610_612_736,
    gcCount: 412,
    gcTimeMs: 3_200,
    platformThreads: 44,
    peakPlatformThreads: 61,
    uptimeMs: 9_000_000,
    processCpuLoad: 0.0734,
    availableProcessors: 10,
    ...over,
  }
}

beforeEach(() => {
  clearNuxtData()
  requests = 0
  stats = sample()
})

describe('SettingsJvmPanel — memory is three figures, not one', () => {
  it('reports heap, non-heap and process memory as distinct values', async () => {
    const c = await mountSuspended(SettingsJvmPanel)
    await flushPromises()

    // 256 MB live inside a 512 MB heap, 128 MB non-heap, 1.5 GB charged by the OS.
    expect(c.find('[data-testid="jvm-heap"]').text()).toContain('256 MB')
    expect(c.find('[data-testid="jvm-heap"]').text()).toContain('512 MB')
    expect(c.find('[data-testid="jvm-nonheap"]').text()).toContain('128 MB')
    expect(c.find('[data-testid="jvm-rss"]').text()).toContain('1.5 GB')
  })

  it('says process memory is not the heap, so the gap does not read as a leak', async () => {
    const c = await mountSuspended(SettingsJvmPanel)
    await flushPromises()
    // Without this the panel invites the opposite conclusion: RSS far above heap-used
    // is the normal ZGC picture, not evidence of a problem.
    expect(c.text()).toContain('what the operating system')
    expect(c.text()).toContain('not a leak')
  })

  it('shows a dash when the platform cannot report process memory', async () => {
    stats = sample({ rssBytes: null })
    const c = await mountSuspended(SettingsJvmPanel)
    await flushPromises()

    // The one substitution that must never happen: falling back to a heap figure would
    // report 256 MB as the process footprint.
    expect(c.find('[data-testid="jvm-rss"]').text()).toBe('—')
    expect(c.find('[data-testid="jvm-rss"]').text()).not.toContain('256')
  })

  it('renders an absent heap ceiling as "no ceiling" rather than -1 bytes', async () => {
    stats = sample({ heapMax: -1 })
    const c = await mountSuspended(SettingsJvmPanel)
    await flushPromises()
    expect(c.find('[data-testid="jvm-heap"]').text()).toContain('no ceiling')
    expect(c.find('[data-testid="jvm-heap"]').text()).not.toContain('-1')
  })
})

describe('SettingsJvmPanel — absent readings stay absent', () => {
  it('distinguishes an unreported CPU share from an idle one', async () => {
    stats = sample({ processCpuLoad: null })
    const c = await mountSuspended(SettingsJvmPanel)
    await flushPromises()
    // "0.0%" would assert the process is idle, which is a different claim from
    // "the JVM did not tell us".
    expect(c.find('[data-testid="jvm-cpu"]').text()).toContain('—')
    expect(c.find('[data-testid="jvm-cpu"]').text()).not.toContain('0.0%')
  })

  it('reports a genuine zero CPU share as a measurement', async () => {
    stats = sample({ processCpuLoad: 0 })
    const c = await mountSuspended(SettingsJvmPanel)
    await flushPromises()
    expect(c.find('[data-testid="jvm-cpu"]').text()).toContain('0.0%')
  })
})

describe('SettingsJvmPanel — threads', () => {
  it('labels the count as platform threads and says virtual ones are excluded', async () => {
    const c = await mountSuspended(SettingsJvmPanel)
    await flushPromises()

    expect(c.find('[data-testid="jvm-threads"]').text()).toContain('44')
    expect(c.find('[data-testid="jvm-threads"]').text()).toContain('61')
    // A low flat number here is expected on a virtual-thread-only fork; unlabelled it
    // reads as an idle instance.
    expect(c.text()).toContain('excludes virtual threads')
  })
})

describe('SettingsJvmPanel — garbage collection', () => {
  it('shows the cumulative counters on the first sample', async () => {
    const c = await mountSuspended(SettingsJvmPanel)
    await flushPromises()
    expect(c.find('[data-testid="jvm-gc"]').text()).toContain('412')
    // No delta is claimed until there are two samples to subtract.
    expect(c.find('[data-testid="jvm-gc"]').text()).not.toContain('since last sample')
  })

  it('derives collections-since-last-sample once a second sample arrives', async () => {
    vi.useFakeTimers()
    try {
      const c = await mountSuspended(SettingsJvmPanel)
      await flushPromises()

      stats = sample({ gcCount: 419 })
      await vi.advanceTimersByTimeAsync(5_000)
      await flushPromises()

      // The reason this panel polls at all: 419 on its own says nothing, +7 does.
      expect(c.find('[data-testid="jvm-gc"]').text()).toContain('+7 since last sample')
    }
    finally {
      vi.useRealTimers()
    }
  })
})

describe('SettingsJvmPanel — polling discipline', () => {
  it('stops polling once unmounted', async () => {
    vi.useFakeTimers()
    try {
      const c = await mountSuspended(SettingsJvmPanel)
      await flushPromises()
      const afterMount = requests

      c.unmount()
      await vi.advanceTimersByTimeAsync(20_000)

      // A leaked interval outlives the section and keeps requesting forever.
      expect(requests).toBe(afterMount)
    }
    finally {
      vi.useRealTimers()
    }
  })

  it('surfaces a failure instead of rendering an empty grid', async () => {
    stats = null
    const c = await mountSuspended(SettingsJvmPanel)
    await flushPromises()
    expect(c.text()).toContain('Could not read the JVM metrics')
  })
})
