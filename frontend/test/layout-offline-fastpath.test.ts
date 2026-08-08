import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import DefaultLayout from '~/layouts/default.vue'

const OFFLINE_BANNER = 'API is unreachable'

let statusCalls = 0

registerEndpoint('/api/status', () => {
  statusCalls++
  return { status: 'ok', applicationVersion: '0.17.47' }
})
registerEndpoint('/api/auth/status', () => ({ passwordSet: true }))
registerEndpoint('/api/onboarding/tour-status', () => ({ completed: true, step: 0 }))
registerEndpoint('/api/config', () => ({}))

/** Flip navigator.onLine and fire the matching window event VueUse listens on. */
function setLinkState(up: boolean) {
  Object.defineProperty(navigator, 'onLine', { value: up, configurable: true })
  window.dispatchEvent(new Event(up ? 'online' : 'offline'))
}

let layout: Awaited<ReturnType<typeof mountSuspended>> | null = null

beforeEach(() => {
  clearNuxtData()
  statusCalls = 0
  setLinkState(true)
})

// A mounted layout keeps its online/offline listeners for as long as it lives,
// so leaving one behind makes the next test's link events fire two probes.
afterEach(() => {
  layout?.unmount()
  layout = null
})

describe('default layout — navigator.onLine fast path for the API dot', () => {
  it('marks the API offline on link loss without waiting for the 30s poll', async () => {
    layout = await mountSuspended(DefaultLayout)
    await flushPromises()
    expect(layout!.text()).not.toContain(OFFLINE_BANNER)

    setLinkState(false)
    await flushPromises()

    // No timer advanced here — if this passes only after the interval fires,
    // the fast path is not wired and the dot stays green for up to 30s.
    expect(layout!.text()).toContain(OFFLINE_BANNER)
  })

  it('re-probes the backend on link recovery rather than assuming it is up', async () => {
    layout = await mountSuspended(DefaultLayout)
    await flushPromises()
    const afterMount = statusCalls

    setLinkState(false)
    await flushPromises()
    // A dropped link must not trigger a request that is guaranteed to fail.
    expect(statusCalls).toBe(afterMount)

    setLinkState(true)
    await flushPromises()
    // Recovery only re-probes: navigator.onLine === true is not proof the
    // backend answers, so the banner clears on the probe's result, not the event.
    expect(statusCalls).toBe(afterMount + 1)

    // Second flush: the first only gets the probe dispatched. The banner is
    // still up at this point, which is the behaviour we want — it clears on
    // the response, not on the event.
    await flushPromises()
    expect(layout!.text()).not.toContain(OFFLINE_BANNER)
  })
})
