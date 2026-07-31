import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { clearNuxtData } from '#app'
import SettingsRestartPanel from '~/components/settings/SettingsRestartPanel.vue'
import ConfirmDialog from '~/components/ConfirmDialog.vue'

/**
 * Settings → Restart panel. The reboot is irreversible from the operator's
 * seat, so what's covered here is the gate around it: the button is dead when
 * the install can't restart, the confirmation states what is about to be
 * interrupted, and a cancelled dialog issues no POST.
 *
 * The reconnect poll is not driven here — it's a wall-clock interval against a
 * backend that is genuinely down, which a mocked-timer test would assert about
 * itself rather than about the restart.
 */

/** Mount alongside ConfirmDialog so confirm() actually renders — the dialog
 *  reads useConfirm()'s module singleton, which lives at app root in prod. */
const Harness = defineComponent({
  setup() {
    return () => h('div', [h(SettingsRestartPanel), h(ConfirmDialog)])
  },
})

let preflight: Record<string, unknown>
let restartPosts = 0

registerEndpoint('/api/system/restart', {
  method: 'GET',
  handler: () => preflight,
})
registerEndpoint('/api/system/restart', {
  method: 'POST',
  handler: () => {
    restartPosts += 1
    return { status: 'ok', mode: 'PROD', backendOnly: false, rebuildExpected: false }
  },
})

function available(over: Record<string, unknown> = {}) {
  return {
    available: true,
    unavailableReason: null,
    mode: 'PROD',
    backendOnly: false,
    rebuildExpected: false,
    runningTasks: 0,
    activeSubagentRuns: 0,
    ...over,
  }
}

/** The button carrying `label` inside the confirm dialog, or null. Scoped to
 *  [role=dialog] because the panel's own trigger is labelled 'Restart' too — an
 *  unscoped search matches it first and silently tests the wrong button. */
function dialogButton(label: string): HTMLButtonElement | null {
  const buttons = [...document.querySelectorAll('[role="dialog"] button')]
  return (buttons.find(b => (b.textContent ?? '').trim() === label) ?? null) as HTMLButtonElement | null
}

beforeEach(() => {
  clearNuxtData()
  restartPosts = 0
  preflight = available()
  document.body.innerHTML = ''
})

describe('SettingsRestartPanel — availability', () => {
  it('disables the button and explains why when the install has no jclaw.sh', async () => {
    preflight = available({
      available: false,
      unavailableReason: 'jclaw.sh not found at /srv/jclaw/jclaw.sh — this installation is not managed by jclaw.sh.',
    })
    const c = await mountSuspended(Harness)
    await flushPromises()

    expect(c.text()).toContain('not managed by jclaw.sh')
    // A button that the POST would refuse anyway must not be clickable — the
    // preflight and the POST share one availability rule on the backend.
    expect(c.find('button').attributes('disabled')).toBeDefined()
  })

  it('enables the button and reports the mode when a restart is possible', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()

    expect(c.text()).toContain('Mode: PROD')
    expect(c.find('button').attributes('disabled')).toBeUndefined()
  })

  it('says the dev server survives a dev-mode restart', async () => {
    preflight = available({ mode: 'DEV', backendOnly: true })
    const c = await mountSuspended(Harness)
    await flushPromises()

    expect(c.text()).toContain('backend only')
  })

  it('warns that a source checkout takes minutes', async () => {
    preflight = available({ rebuildExpected: true })
    const c = await mountSuspended(Harness)
    await flushPromises()

    expect(c.text()).toContain('minutes')
  })
})

describe('SettingsRestartPanel — confirmation', () => {
  it('names the in-flight work the restart will interrupt', async () => {
    preflight = available({ runningTasks: 2, activeSubagentRuns: 1 })
    const c = await mountSuspended(Harness)
    await flushPromises()

    await c.find('button').trigger('click')
    await flushPromises()

    // The operator is confirming against these counts, so they have to be in
    // the dialog rather than merely available on the endpoint.
    const dialog = document.body.textContent ?? ''
    expect(dialog).toContain('2 task runs')
    expect(dialog).toContain('1 subagent run')
    expect(restartPosts).toBe(0)
  })

  it('says so plainly when nothing is in flight', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()

    await c.find('button').trigger('click')
    await flushPromises()

    expect(document.body.textContent ?? '').toContain('No task or subagent runs are currently active')
  })

  it('issues no POST when the dialog is cancelled', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()

    await c.find('button').trigger('click')
    await flushPromises()
    dialogButton('Cancel')?.click()
    await flushPromises()

    expect(restartPosts).toBe(0)
  })

  it('POSTs and moves into the waiting state once confirmed', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()

    await c.find('button').trigger('click')
    await flushPromises()
    dialogButton('Restart')?.click()
    await flushPromises()

    expect(restartPosts).toBe(1)
    // The button must latch — a second POST would race a JVM already inside
    // its shutdown hooks.
    expect(c.find('button').attributes('disabled')).toBeDefined()

    // The panel hands off, then waits. vi.waitFor because the ack has to make
    // a round trip through the mocked endpoint before the phase advances.
    await vi.waitFor(() => expect(c.text()).toContain('Waiting for the backend'))

    // Stop the reconnect poll this started; nothing here is serving /api/status.
    c.unmount()
  })
})
