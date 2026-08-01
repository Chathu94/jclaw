import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import type { McpServer } from '~/types/api'
import { useMcpStatusWatcher, type UseMcpStatusWatcher } from '~/composables/useMcpStatusWatcher'

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
})

function server(status: McpServer['status']): McpServer {
  return { id: 1, name: 'jira', status } as unknown as McpServer
}

/** Advance fake time and let the loop's awaited continuation run. */
async function tick(ms: number) {
  await vi.advanceTimersByTimeAsync(ms)
  await flushPromises()
}

function mountWatcher(initial: McpServer[] | null) {
  const servers = ref<McpServer[] | null>(initial)
  const refresh = vi.fn(() => Promise.resolve())
  let api!: UseMcpStatusWatcher
  const wrapper = mount(
    defineComponent({
      setup() {
        api = useMcpStatusWatcher(servers, refresh)
        return () => h('div')
      },
    }),
  )
  return { wrapper, servers, refresh, api }
}

describe('useMcpStatusWatcher', () => {
  it('refreshes on its own after mount, with no user action', async () => {
    // The regression this exists for: polling was armed only by the operator's own
    // save/toggle, so arriving mid-connect froze the badge until a manual reload.
    const { wrapper, refresh } = mountWatcher([server('CONNECTING')])
    expect(refresh).not.toHaveBeenCalled()
    await tick(600)
    expect(refresh).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('polls fast while a server is CONNECTING', async () => {
    const { wrapper, refresh } = mountWatcher([server('CONNECTING')])
    await tick(600 * 3)
    expect(refresh).toHaveBeenCalledTimes(3)
    wrapper.unmount()
  })

  it('idles on the slow heartbeat when nothing is CONNECTING', async () => {
    const { wrapper, refresh } = mountWatcher([server('CONNECTED')])
    // Well past the fast cadence, but short of the heartbeat.
    await tick(5_000)
    expect(refresh).not.toHaveBeenCalled()
    await tick(5_000)
    expect(refresh).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('keeps watching after a connection settles, so a later reconnect is caught', async () => {
    // A watchdog-driven reconnect starts long after load. A watcher that
    // stopped once the list went quiet would never see it.
    const { wrapper, servers, refresh } = mountWatcher([server('CONNECTED')])
    await tick(10_000)
    expect(refresh).toHaveBeenCalledTimes(1)

    servers.value = [server('CONNECTING')]
    await tick(10_000) // still on the slow wait it had already begun
    await tick(600)
    expect(refresh.mock.calls.length).toBeGreaterThanOrEqual(3)
    wrapper.unmount()
  })

  it('kick() collapses the idle wait so a mutation shows up at once', async () => {
    const { wrapper, refresh, api } = mountWatcher([server('CONNECTED')])
    await tick(100)
    expect(refresh).not.toHaveBeenCalled()

    api.kick()
    await flushPromises()
    expect(refresh).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('survives a failing refresh and keeps polling', async () => {
    // The loop is the page's only source of updates: an escaping rejection ends it.
    const servers = ref<McpServer[] | null>([server('CONNECTING')])
    const refresh = vi.fn()
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValue(undefined)
    const wrapper = mount(
      defineComponent({
        setup() {
          useMcpStatusWatcher(servers, refresh)
          return () => h('div')
        },
      }),
    )
    await tick(600)
    expect(refresh).toHaveBeenCalledTimes(1)
    await tick(600)
    expect(refresh).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('stops its timer on unmount', async () => {
    const { wrapper, refresh } = mountWatcher([server('CONNECTING')])
    await tick(600)
    expect(refresh).toHaveBeenCalledTimes(1)

    wrapper.unmount()
    await flushPromises()
    expect(vi.getTimerCount()).toBe(0)

    await tick(600 * 5)
    expect(refresh).toHaveBeenCalledTimes(1) // frozen after unmount
  })
})
