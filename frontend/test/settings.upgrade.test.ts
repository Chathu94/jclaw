import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { clearNuxtData } from '#app'
import SettingsUpgradePanel from '~/components/settings/SettingsUpgradePanel.vue'
import ConfirmDialog from '~/components/ConfirmDialog.vue'

/**
 * Settings → Upgrade panel. What's covered is the gate: the button only exists
 * when there is something to install, the confirmation states what is
 * preserved and what is interrupted, and a cancelled dialog issues no POST.
 *
 * The progress poll is not driven here — it's a wall-clock interval against a
 * backend that genuinely goes away mid-upgrade, which a mocked-timer test would
 * assert about itself rather than about the upgrade.
 */

const Harness = defineComponent({
  setup() {
    return () => h('div', [h(SettingsUpgradePanel), h(ConfirmDialog)])
  },
})

let preflight: Record<string, unknown>
let status: Record<string, unknown> | null
let upgradePosts = 0

registerEndpoint('/api/system/upgrade', {
  method: 'GET',
  handler: () => preflight,
})
registerEndpoint('/api/system/upgrade', {
  method: 'POST',
  handler: () => {
    upgradePosts += 1
    return { status: 'ok', currentVersion: '0.17.49', targetVersion: '0.17.50', installKind: 'bundle' }
  },
})
registerEndpoint('/api/system/upgrade/status', {
  method: 'GET',
  handler: () => status,
})

function available(over: Record<string, unknown> = {}) {
  return {
    available: true,
    unavailableReason: null,
    currentVersion: '0.17.49',
    latestVersion: '0.17.50',
    upgradeAvailable: true,
    installKind: 'bundle',
    runningTasks: 0,
    activeSubagentRuns: 0,
    ...over,
  }
}

/** The button carrying `label` inside the confirm dialog, or null. Scoped to
 *  [role=dialog] because the panel's own trigger is labelled 'Upgrade…' too. */
function dialogButton(label: string): HTMLButtonElement | null {
  const buttons = [...document.querySelectorAll('[role="dialog"] button')]
  return (buttons.find(b => (b.textContent ?? '').trim() === label) ?? null) as HTMLButtonElement | null
}

function panelButton(c: { findAll: (s: string) => { text: () => string, trigger: (e: string) => Promise<void> }[] }, match: RegExp) {
  return c.findAll('button').find(b => match.test(b.text()))
}

beforeEach(() => {
  clearNuxtData()
  upgradePosts = 0
  preflight = available()
  status = null
  document.body.innerHTML = ''
})

describe('SettingsUpgradePanel — availability', () => {
  it('explains why a source checkout cannot upgrade, and offers no button', async () => {
    preflight = available({
      available: false,
      unavailableReason: 'This is a source checkout — update it with \'git pull\'.',
      latestVersion: null,
      upgradeAvailable: false,
    })
    const c = await mountSuspended(Harness)
    await flushPromises()

    expect(c.text()).toContain('git pull')
    // Neither control may render: the POST would refuse, and "Check again"
    // would spend a GitHub call for an install that can never act on it.
    expect(c.findAll('button')).toHaveLength(0)
  })

  it('explains why a container cannot upgrade in place', async () => {
    preflight = available({
      available: false,
      unavailableReason: 'This instance runs in a container — upgrade the image instead '
        + '(docker compose pull && docker compose up -d).',
      latestVersion: null,
      upgradeAvailable: false,
    })
    const c = await mountSuspended(Harness)
    await flushPromises()

    expect(c.text()).toContain('docker compose pull')
  })

  it('offers only a re-check when already on the newest release', async () => {
    preflight = available({ latestVersion: '0.17.49', upgradeAvailable: false })
    const c = await mountSuspended(Harness)
    await flushPromises()

    expect(c.text()).toContain('up to date')
    expect(panelButton(c, /Check again/)).toBeTruthy()
    expect(panelButton(c, /Upgrade to/)).toBeFalsy()
  })

  it('names the target version on the button when one is available', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()

    expect(c.text()).toContain('0.17.50 is available')
    expect(panelButton(c, /Upgrade to 0\.17\.50/)).toBeTruthy()
  })

  it('reports the current version without claiming an update when GitHub is unreachable', async () => {
    // latestVersion is null whenever the release check fails. The panel must
    // not silently read that as "up to date" — an operator would stop looking.
    preflight = available({ latestVersion: null, upgradeAvailable: false })
    const c = await mountSuspended(Harness)
    await flushPromises()

    expect(c.text()).toContain('could not reach GitHub')
    expect(c.text()).not.toContain('up to date')
  })
})

describe('SettingsUpgradePanel — confirmation', () => {
  it('states what is preserved and what the restart interrupts', async () => {
    preflight = available({ runningTasks: 2, activeSubagentRuns: 1 })
    const c = await mountSuspended(Harness)
    await flushPromises()

    await panelButton(c, /Upgrade to/)!.trigger('click')
    await flushPromises()

    const dialog = document.body.textContent ?? ''
    // The operator is confirming against these specifics, so they belong in the
    // dialog rather than merely on the endpoint.
    expect(dialog).toContain('2 task runs')
    expect(dialog).toContain('1 subagent run')
    expect(dialog).toContain('preserved')
    expect(dialog).toContain('backed up')
    expect(dialog).toContain('rolled back automatically')
    expect(upgradePosts).toBe(0)
  })

  it('says the instance keeps running during the download', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()

    await panelButton(c, /Upgrade to/)!.trigger('click')
    await flushPromises()

    // The whole reason the download precedes the stop. If the copy ever loses
    // this, operators will assume the instance is down for the entire upgrade.
    expect(document.body.textContent ?? '').toContain('keeps running')
  })

  it('issues no POST when the dialog is cancelled', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()

    await panelButton(c, /Upgrade to/)!.trigger('click')
    await flushPromises()
    dialogButton('Cancel')?.click()
    await flushPromises()

    expect(upgradePosts).toBe(0)
  })

  it('POSTs once and latches the button when confirmed', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()

    await panelButton(c, /Upgrade to/)!.trigger('click')
    await flushPromises()
    dialogButton('Upgrade')?.click()
    await flushPromises()

    expect(upgradePosts).toBe(1)
    // A second POST would spawn a second helper against a tree the first is
    // already swapping.
    await vi.waitFor(() => expect(panelButton(c, /Upgrading…/)).toBeTruthy())

    c.unmount()
  })
})

describe('SettingsUpgradePanel — outcome of a previous upgrade', () => {
  it('reports the upgrade that installed the running version', async () => {
    // logs/upgrade-status.json is carried across the tree swap, so the instance
    // that comes back serves the file its own upgrade wrote on the way there.
    status = {
      phase: 'done',
      pct: 100,
      message: 'Upgraded to 0.17.49.',
      fromVersion: '0.17.48',
      toVersion: '0.17.49',
      startedAt: '2026-08-08T10:00:00Z',
    }
    preflight = available({ latestVersion: '0.17.49', upgradeAvailable: false })
    const c = await mountSuspended(Harness)
    await flushPromises()

    expect(c.text()).toContain('Upgraded 0.17.48 → 0.17.49')
  })

  it('reports a rollback prominently rather than as a silent no-op', async () => {
    status = {
      phase: 'rolled-back',
      pct: 0,
      message: 'Upgrade to 0.17.50 failed; rolled back to 0.17.49.',
      fromVersion: '0.17.49',
      toVersion: '0.17.50',
      startedAt: '2026-08-08T10:00:00Z',
    }
    const c = await mountSuspended(Harness)
    await flushPromises()

    // Without this the operator sees an unchanged version number and no reason,
    // which reads as "the button did nothing".
    expect(c.text()).toContain('rolled back')
    expect(c.text()).toContain('logs/upgrade.log')
  })
})
