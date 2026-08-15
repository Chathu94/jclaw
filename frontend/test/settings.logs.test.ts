import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { clearNuxtData } from '#app'
import SettingsLogsPanel from '~/components/settings/SettingsLogsPanel.vue'
import ConfirmDialog from '~/components/ConfirmDialog.vue'

/**
 * Settings → Logging → Logs (JCLAW-1057).
 *
 * The panel exists because the archives were invisible: 70 unpruned files and 97 MB had
 * accumulated with nothing in the UI to show it. So the assertions are about the archive
 * figures being reported honestly — including the empty and unreadable cases, where a
 * bare "0 B" would look like a healthy answer rather than a missing one.
 */

/** Mounted with ConfirmDialog so confirm() resolves — useConfirm holds module state. */
const Harness = defineComponent({
  setup() {
    return () => h('div', [h(SettingsLogsPanel), h(ConfirmDialog)])
  },
})

let footprint: Record<string, unknown>
let purges = 0

registerEndpoint('/api/metrics/logs', {
  method: 'GET',
  handler: () => footprint,
})
registerEndpoint('/api/metrics/logs', {
  method: 'DELETE',
  handler: () => {
    purges += 1
    return { deleted: 70, freedBytes: 99_614_720 }
  },
})

function dialogButton(label: string): HTMLButtonElement | null {
  const buttons = [...document.querySelectorAll('[role="dialog"] button')]
  return (buttons.find(b => (b.textContent ?? '').trim() === label) ?? null) as HTMLButtonElement | null
}

function sample(over: Record<string, unknown> = {}) {
  return {
    liveBytes: 200_704,
    archiveCount: 70,
    archiveBytes: 99_614_720,
    totalBytes: 101_711_872,
    retentionDays: 30,
    ...over,
  }
}

beforeEach(() => {
  clearNuxtData()
  purges = 0
  footprint = sample()
  document.body.innerHTML = ''
})

describe('SettingsLogsPanel', () => {
  it('separates the capped live file from the archives that actually accumulate', async () => {
    const c = await mountSuspended(SettingsLogsPanel)
    await flushPromises()

    // 196 KB live against 95 MB of archives — the whole point of showing both.
    expect(c.find('[data-testid="logs-live"]').text()).toContain('196 KB')
    expect(c.find('[data-testid="logs-archives"]').text()).toContain('70 files')
    expect(c.find('[data-testid="logs-archives"]').text()).toContain('95 MB')
  })

  it('reports the whole directory, not just the two named categories', async () => {
    const c = await mountSuspended(SettingsLogsPanel)
    await flushPromises()
    // totalBytes exceeds live + archives because ad-hoc files live there too; a total
    // derived from the two categories would under-report the disk actually used.
    expect(c.find('[data-testid="logs-total"]').text()).toContain('97 MB')
  })

  it('states the retention window the appender implements', async () => {
    const c = await mountSuspended(SettingsLogsPanel)
    await flushPromises()
    expect(c.text()).toContain('30 days')
  })

  it('reflects a retention window other than the default', async () => {
    // Guards against the days being hardcoded in the template rather than read from
    // the server, which would let the panel promise a window nobody implements.
    footprint = sample({ retentionDays: 7 })
    const c = await mountSuspended(SettingsLogsPanel)
    await flushPromises()
    expect(c.text()).toContain('7 days')
    expect(c.text()).not.toContain('30 days')
  })

  it('says "none yet" rather than 0 B on a fresh install', async () => {
    footprint = sample({ archiveCount: 0, archiveBytes: 0 })
    const c = await mountSuspended(SettingsLogsPanel)
    await flushPromises()
    expect(c.find('[data-testid="logs-archives"]').text()).toBe('none yet')
  })

  it('singularises a lone archive', async () => {
    footprint = sample({ archiveCount: 1, archiveBytes: 1024 })
    const c = await mountSuspended(SettingsLogsPanel)
    await flushPromises()
    expect(c.find('[data-testid="logs-archives"]').text()).toContain('1 file,')
  })

  it('shows a dash when the live file is not on disk', async () => {
    footprint = sample({ liveBytes: -1 })
    const c = await mountSuspended(SettingsLogsPanel)
    await flushPromises()
    // -1 means "absent". Rendered raw it would read as a negative file size.
    expect(c.find('[data-testid="logs-live"]').text()).toBe('—')
  })
})

describe('SettingsLogsPanel — deleting archives', () => {
  it('deletes nothing until the operator confirms', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()

    await c.findAll('button').find(b => b.text().includes('Delete archives'))!.trigger('click')
    await flushPromises()

    // The dialog has to state what goes and what stays, because "delete logs" is
    // ambiguous about the file currently being written.
    const dialog = document.body.textContent ?? ''
    expect(dialog).toContain('70 archived log files')
    expect(dialog).toContain('The current log is kept')
    expect(purges).toBe(0)
  })

  it('issues no request when the dialog is cancelled', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()

    await c.findAll('button').find(b => b.text().includes('Delete archives'))!.trigger('click')
    await flushPromises()
    dialogButton('Cancel')?.click()
    await flushPromises()

    expect(purges).toBe(0)
  })

  it('reports what was reclaimed once confirmed', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()

    await c.findAll('button').find(b => b.text().includes('Delete archives'))!.trigger('click')
    await flushPromises()
    dialogButton('Delete archives')?.click()

    await vi.waitFor(() => expect(purges).toBe(1))
    await vi.waitFor(() => expect(c.text()).toContain('Deleted 70 archives'))
    expect(c.text()).toContain('95 MB')
  })

  it('offers no delete when there is nothing archived', async () => {
    footprint = sample({ archiveCount: 0, archiveBytes: 0 })
    const c = await mountSuspended(Harness)
    await flushPromises()

    // Enabled, it would open a dialog promising to delete zero files.
    const btn = c.findAll('button').find(b => b.text().includes('Delete archives'))
    expect(btn!.attributes('disabled')).toBeDefined()
  })
})
