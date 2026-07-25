import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { defineComponent, h, nextTick, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { useStreamProgress, type UseStreamProgress } from '~/composables/useStreamProgress'

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
})

function mountProgress(localValue = true) {
  const streaming = ref(false)
  const producing = ref(false)
  const local = ref(localValue)
  let api!: UseStreamProgress
  const wrapper = mount(
    defineComponent({
      setup() {
        api = useStreamProgress(streaming, producing, local)
        return () => h('div')
      },
    }),
  )
  return { wrapper, streaming, producing, local, api }
}

describe('useStreamProgress', () => {
  it('is inactive, prefill, and reads 0:00 before a stream starts', () => {
    const { api } = mountProgress()
    expect(api.active.value).toBe(false)
    expect(api.phase.value).toBe('prefill')
    expect(api.elapsed.value).toBe('0:00')
  })

  it('activates and labels "Prefilling…" while streaming with no token yet', async () => {
    const { api, streaming } = mountProgress()
    streaming.value = true
    await nextTick()
    expect(api.active.value).toBe(true)
    expect(api.phase.value).toBe('prefill')
    expect(api.label.value).toBe('Prefilling…')
  })

  it('flips to "Generating…" once the first token lands', async () => {
    const { api, streaming, producing } = mountProgress()
    streaming.value = true
    await nextTick()
    expect(api.label.value).toBe('Prefilling…')
    producing.value = true // first reasoning/content delta
    await nextTick()
    expect(api.phase.value).toBe('generating')
    expect(api.label.value).toBe('Generating…')
  })

  it('never shows "Prefilling…" for a cloud (non-local) provider', async () => {
    const { api, streaming, producing } = mountProgress(false)
    streaming.value = true
    await nextTick()
    // Same pre-first-token window, but the label reads "Generating…" from the
    // first frame — cloud has no visible prefill.
    expect(api.phase.value).toBe('prefill')
    expect(api.label.value).toBe('Generating…')
    producing.value = true
    await nextTick()
    expect(api.label.value).toBe('Generating…')
  })

  it('ticks the elapsed timer up in m:ss for the whole turn', async () => {
    const { api, streaming } = mountProgress()
    streaming.value = true
    await nextTick()
    vi.advanceTimersByTime(7000)
    expect(api.elapsed.value).toBe('0:07')
    vi.advanceTimersByTime(68000) // 75s total — crosses the minute boundary
    expect(api.elapsed.value).toBe('1:15')
  })

  it('resets elapsed and stops ticking when the stream ends', async () => {
    const { api, streaming } = mountProgress()
    streaming.value = true
    await nextTick()
    vi.advanceTimersByTime(5000)
    expect(api.elapsed.value).toBe('0:05')

    streaming.value = false
    await nextTick()
    expect(api.active.value).toBe(false)
    expect(api.elapsed.value).toBe('0:00')
    expect(vi.getTimerCount()).toBe(0) // interval torn down
    vi.advanceTimersByTime(5000)
    expect(api.elapsed.value).toBe('0:00') // stays frozen after stop
  })

  it('clears the interval on unmount without throwing', async () => {
    const { wrapper, streaming } = mountProgress()
    streaming.value = true
    await nextTick()
    expect(() => {
      wrapper.unmount()
      vi.advanceTimersByTime(3000)
    }).not.toThrow()
    expect(vi.getTimerCount()).toBe(0)
  })
})
