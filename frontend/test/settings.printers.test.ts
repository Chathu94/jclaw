import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { readBody } from 'h3'
import { clearNuxtData } from '#app'
import SettingsPrintersPanel from '~/components/settings/SettingsPrintersPanel.vue'

/**
 * The Printers settings panel (JCLAW-911).
 *
 * <p>The panel's real logic is in what it *sends*: which job options survive into the
 * saved default, how a hand-entered address is normalised, and what a cleared default
 * looks like on the wire. Those are asserted against the captured PUT body rather than
 * against rendering, because a printer rejects a job option it never offered — so a
 * stale key reaching the API is a defect the screen cannot show.
 */

interface Opts {
  saved?: unknown
  reach?: unknown
  options?: unknown
  printers?: unknown[]
  scanFails?: boolean
  saveFails?: boolean
}

let put: Record<string, unknown> | null = null
let scans = 0

const SIDES = {
  name: 'sides',
  label: 'Sides',
  values: [
    { value: 'one-sided', label: 'One sided' },
    { value: 'two-sided-long-edge', label: 'Two sided' },
  ],
  min: null,
  max: null,
  defaultValue: 'one-sided',
}

const COPIES = { name: 'copies', label: 'Copies', values: [], min: 1, max: 99, defaultValue: '1' }

function setupApi(opts: Opts = {}) {
  put = null
  scans = 0
  registerEndpoint('/api/printers/default', {
    handler: async (event) => {
      if (event.method === 'PUT') {
        put = await readBody(event) as Record<string, unknown>
        if (opts.saveFails) throw new Error('save refused')
        return { ok: true }
      }
      return opts.saved ?? { name: null, host: null, port: 0, protocol: null, options: {} }
    },
  })
  registerEndpoint('/api/printers/default/status', () =>
    opts.reach ?? { configured: false, reachable: false, host: null, port: 0 })
  registerEndpoint('/api/printers/options', () =>
    opts.options ?? { options: [], protocols: [], mediaReady: null, fromPrinter: false })
  registerEndpoint('/api/printers', () => {
    scans++
    if (opts.scanFails) throw new Error('mDNS blew up')
    return opts.printers ?? []
  })
}

async function mountPanel() {
  const c = await mountSuspended(SettingsPrintersPanel)
  await flushPromises()
  await flushPromises()
  return c
}

function buttonByText(c: VueWrapper, text: string) {
  return c.findAll('button').filter(b => b.text().trim() === text)
}

async function click(c: VueWrapper, text: string, index = 0) {
  const btns = buttonByText(c, text)
  expect(btns.length, `no button reading "${text}"`).toBeGreaterThan(index)
  await btns[index]!.trigger('click')
  await flushPromises()
}

const OFFICE = {
  name: 'Office LaserJet',
  host: '10.0.0.5',
  port: 631,
  protocol: 'IPP',
  formats: null,
  isDefault: false,
}

describe('Settings — Printers panel', () => {
  beforeEach(() => clearNuxtData())

  // ─────── the saved default ────────────────────────────────────────────────

  it('does not scan the network on mount', async () => {
    setupApi()
    await mountPanel()
    // An mDNS browse takes seconds; opening Settings for anything else must not pay it.
    expect(scans).toBe(0)
  })

  it('shows the saved default as name, address and protocol', async () => {
    setupApi({ saved: { name: 'Office LaserJet', host: '10.0.0.5', port: 631, protocol: 'IPP', options: {} } })
    const c = await mountPanel()
    expect(c.text()).toContain('Office LaserJet — 10.0.0.5:631 (IPP)')
  })

  it('does not print the address twice when the name is the host', async () => {
    // Manual entry saves name === host, so the naive interpolation read "10.0.0.5 — 10.0.0.5:631".
    setupApi({ saved: { name: '10.0.0.5', host: '10.0.0.5', port: 631, protocol: 'IPP', options: {} } })
    const c = await mountPanel()
    expect(c.text()).toContain('10.0.0.5:631 (IPP)')
    expect(c.text()).not.toContain('10.0.0.5 — 10.0.0.5')
  })

  it('flags a saved default that has stopped answering', async () => {
    setupApi({
      saved: { name: 'Office', host: '10.0.0.5', port: 631, protocol: 'IPP', options: {} },
      reach: { configured: true, reachable: false, host: '10.0.0.5', port: 631 },
    })
    const c = await mountPanel()
    // A default outlives the DHCP lease it was saved under; without this the only
    // symptom is a print that times out.
    expect(c.find('[data-testid="printer-reachability"]').text()).toContain('Not answering')
  })

  it('reports a reachable default as online', async () => {
    setupApi({
      saved: { name: 'Office', host: '10.0.0.5', port: 631, protocol: 'IPP', options: {} },
      reach: { configured: true, reachable: true, host: '10.0.0.5', port: 631 },
    })
    const c = await mountPanel()
    expect(c.find('[data-testid="printer-reachability"]').text()).toContain('Online')
  })

  it('shows no reachability badge when nothing is configured', async () => {
    setupApi()
    const c = await mountPanel()
    expect(c.find('[data-testid="printer-reachability"]').exists()).toBe(false)
  })

  // ─────── discovery ────────────────────────────────────────────────────────

  it('explains an empty scan as a possible blocked multicast route', async () => {
    setupApi({ printers: [] })
    const c = await mountPanel()
    await click(c, 'Scan')
    // "No printers found" would be a lie on a VPN or in a container, where mDNS
    // cannot work at all — the operator needs pointing at manual entry.
    expect(c.text()).toContain('mDNS is link-local')
  })

  it('surfaces a failed discovery as a failure, not as an empty list', async () => {
    setupApi({ scanFails: true })
    const c = await mountPanel()
    await click(c, 'Scan')
    expect(c.text()).toContain('Discovery failed')
    expect(c.text()).not.toContain('mDNS is link-local')
  })

  it('lists discovered printers', async () => {
    setupApi({ printers: [OFFICE] })
    const c = await mountPanel()
    await click(c, 'Scan')
    expect(c.text()).toContain('Office LaserJet')
    expect(c.text()).toContain('10.0.0.5')
  })

  it('saves a discovered printer as the default', async () => {
    setupApi({ printers: [OFFICE] })
    const c = await mountPanel()
    await click(c, 'Scan')
    await click(c, 'Set default')
    await vi.waitFor(() => expect(put).toBeTruthy())
    expect(put).toMatchObject({ name: 'Office LaserJet', host: '10.0.0.5', port: 631, protocol: 'IPP' })
  })

  // ─────── manual entry ─────────────────────────────────────────────────────

  it('will not save a manual entry until a host is typed', async () => {
    setupApi()
    const c = await mountPanel()
    const save = buttonByText(c, 'Set default')[0]!
    expect(save.attributes('disabled')).toBeDefined()

    // Whitespace is not a host — trim() decides, so the control must stay shut.
    await c.find('input[placeholder="10.0.0.5"]').setValue('   ')
    expect(buttonByText(c, 'Set default')[0]!.attributes('disabled')).toBeDefined()

    await c.find('input[placeholder="10.0.0.5"]').setValue('10.0.0.9')
    expect(buttonByText(c, 'Set default')[0]!.attributes('disabled')).toBeUndefined()
    expect(put).toBeNull()
  })

  it('saves a hand-entered address, naming the printer after its host', async () => {
    setupApi()
    const c = await mountPanel()
    await c.find('input[placeholder="10.0.0.5"]').setValue(' 10.0.0.9 ')
    await c.find('input[placeholder="auto"]').setValue('9100')
    await click(c, 'Set default')
    await vi.waitFor(() => expect(put).toBeTruthy())
    expect(put).toMatchObject({ name: '10.0.0.9', host: '10.0.0.9', port: 9100 })
    // The fields empty on success, so the form does not sit there looking unsaved.
    await vi.waitFor(() =>
      expect((c.find('input[placeholder="10.0.0.5"]').element as HTMLInputElement).value).toBe(''))
  })

  it('treats an unparseable port as "let the protocol decide"', async () => {
    setupApi()
    const c = await mountPanel()
    await c.find('input[placeholder="10.0.0.5"]').setValue('10.0.0.9')
    await c.find('input[placeholder="auto"]').setValue('')
    await click(c, 'Set default')
    await vi.waitFor(() => expect(put).toBeTruthy())
    // 0, not NaN — NaN serialises to null and the backend cannot tell it from "unset".
    expect(put!.port).toBe(0)
  })

  // ─────── job options ──────────────────────────────────────────────────────

  it('renders only the options the printer announced', async () => {
    setupApi({
      saved: { name: 'Office', host: '10.0.0.5', port: 631, protocol: 'IPP', options: {} },
      options: { options: [SIDES, COPIES], protocols: ['IPP'], mediaReady: null, fromPrinter: true },
    })
    const c = await mountPanel()
    expect(c.text()).toContain('Sides')
    expect(c.text()).toContain('Copies')
    // Ranges get a number input rather than a select — copies is 1–99, not a list.
    expect(c.find('input[type="number"]').exists()).toBe(true)
  })

  it('seeds an unset option with the placeholder rather than leaving it blank', async () => {
    setupApi({
      saved: { name: 'Office', host: '10.0.0.5', port: 631, protocol: 'IPP', options: {} },
      options: { options: [SIDES], protocols: ['IPP'], mediaReady: null, fromPrinter: true },
    })
    const c = await mountPanel()
    // An undefined key matches no <option> at all, so the select renders empty and
    // reads as broken rather than as "not set".
    expect((c.find('select#printer-opt-sides').element as HTMLSelectElement).value).toBe('')
  })

  it('preselects the saved value for an option', async () => {
    setupApi({
      saved: {
        name: 'Office', host: '10.0.0.5', port: 631, protocol: 'IPP',
        options: { sides: 'two-sided-long-edge' },
      },
      options: { options: [SIDES], protocols: ['IPP'], mediaReady: null, fromPrinter: true },
    })
    const c = await mountPanel()
    expect((c.find('select#printer-opt-sides').element as HTMLSelectElement).value)
      .toBe('two-sided-long-edge')
  })

  it('sends only options the printer offers, dropping the ones left on default', async () => {
    setupApi({
      saved: {
        name: 'Office', host: '10.0.0.5', port: 631, protocol: 'IPP',
        // 'media-source' is a leftover from a previously-saved printer that offered it.
        options: { 'sides': 'two-sided-long-edge', 'media-source': 'tray-2' },
      },
      options: { options: [SIDES, COPIES], protocols: ['IPP'], mediaReady: null, fromPrinter: true },
    })
    const c = await mountPanel()
    await click(c, 'Save options')
    await vi.waitFor(() => expect(put).toBeTruthy())

    expect(put!.options).toEqual({ sides: 'two-sided-long-edge' })
    // 'copies' is offered but unset, so it stays out — sending '' would pin the job to
    // an empty value instead of the printer's own default.
    expect(put!.options).not.toHaveProperty('copies')
    // 'media-source' is saved but not offered by THIS printer, which would reject it.
    expect(put!.options).not.toHaveProperty('media-source')
  })

  // ─────── clearing and failure ─────────────────────────────────────────────

  it('clears the default by sending an explicit null host', async () => {
    setupApi({ saved: { name: 'Office', host: '10.0.0.5', port: 631, protocol: 'IPP', options: {} } })
    const c = await mountPanel()
    await click(c, 'Clear')
    // The notice lands only after persist() has re-read the default and re-probed it.
    await vi.waitFor(() => expect(c.text()).toContain('Default printer cleared'))
    expect(put).toEqual({ host: null })
  })

  it('reports a rejected save instead of claiming success', async () => {
    setupApi({
      saved: { name: 'Office', host: '10.0.0.5', port: 631, protocol: 'IPP', options: {} },
      saveFails: true,
    })
    const c = await mountPanel()
    await click(c, 'Clear')
    await vi.waitFor(() => expect(c.text()).toContain('Could not save'))
    expect(c.text()).not.toContain('Default printer cleared')
  })
})
