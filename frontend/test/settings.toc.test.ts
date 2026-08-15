import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'

/**
 * Tests for the Settings page's TOC + single-section swap shell (JCLAW-680).
 * The 20 per-section suites cover each panel's behavior; this file covers the
 * page-level navigation the swap introduced: rail rendering, the active-item
 * highlight, click-to-swap, and ?section= deep-linking.
 */
function baseEndpoints() {
  registerEndpoint('/api/config', () => ({ entries: [] }))
  registerEndpoint('/api/providers', () => [])
  registerEndpoint('/api/agents', () => [])
  registerEndpoint('/api/ocr/status', () => ({ providers: [] }))
  registerEndpoint('/api/timezones', () => ({ timezones: ['UTC'], default: 'UTC', appDefault: 'UTC' }))
}

describe('Settings page — TOC navigation + section swap', () => {
  beforeEach(() => {
    clearNuxtData()
  })

  it('renders a rail item for every registered section and marks the first active', async () => {
    baseEndpoints()
    const component = await mountSuspended(Settings)
    await flushPromises()

    // Every section has a rail button.
    for (const id of ['timezone', 'providers', 'transcription', 'shell', 'password']) {
      expect(component.find(`[data-testid="settings-toc-item-${id}"]`).exists()).toBe(true)
    }
    // Default active section is the first (timezone).
    const timezone = component.find('[data-testid="settings-toc-item-timezone"]')
    expect(timezone.attributes('aria-current')).toBe('page')
    // A non-active item carries no aria-current.
    const shell = component.find('[data-testid="settings-toc-item-shell"]')
    expect(shell.attributes('aria-current')).toBeUndefined()
  })

  it('renders the functional group headers in the rail', async () => {
    baseEndpoints()
    const component = await mountSuspended(Settings)
    await flushPromises()

    const text = component.text()
    for (const label of ['System', 'Providers', 'Audio', 'Image', 'Video', 'Agents & Automation', 'Security']) {
      expect(text).toContain(label)
    }
  })

  it('swaps the active section when a rail item is clicked', async () => {
    baseEndpoints()
    const component = await mountSuspended(Settings)
    await flushPromises()

    // General is active; Shell is not yet.
    expect(component.find('[data-testid="settings-toc-item-timezone"]').attributes('aria-current')).toBe('page')

    await component.find('[data-testid="settings-toc-item-shell"]').trigger('click')
    await flushPromises()
    await flushPromises()

    // Highlight moved to Shell; General is no longer current.
    expect(component.find('[data-testid="settings-toc-item-shell"]').attributes('aria-current')).toBe('page')
    expect(component.find('[data-testid="settings-toc-item-timezone"]').attributes('aria-current')).toBeUndefined()
    // The Shell panel is now mounted (its Allowlist control renders).
    expect(component.text()).toContain('Shell Execution')
  })

  it('opens the section named by the ?section query param on load', async () => {
    baseEndpoints()
    const component = await mountSuspended(Settings, { route: '/settings?section=malware' })
    await flushPromises()

    expect(component.find('[data-testid="settings-toc-item-malware"]').attributes('aria-current')).toBe('page')
    expect(component.find('[data-testid="settings-toc-item-timezone"]').attributes('aria-current')).toBeUndefined()
    expect(component.text()).toContain('Malware Scanners')
  })

  it('falls back to the first section when ?section is unknown', async () => {
    baseEndpoints()
    const component = await mountSuspended(Settings, { route: '/settings?section=does-not-exist' })
    await flushPromises()

    expect(component.find('[data-testid="settings-toc-item-timezone"]').attributes('aria-current')).toBe('page')
  })

  it('rolls the retired upgrade and restart sections into one Maintenance entry', async () => {
    baseEndpoints()
    const component = await mountSuspended(Settings)
    await flushPromises()

    expect(component.find('[data-testid="settings-toc-item-maintenance"]').exists()).toBe(true)
    expect(component.find('[data-testid="settings-toc-item-upgrade"]').exists()).toBe(false)
    expect(component.find('[data-testid="settings-toc-item-restart"]').exists()).toBe(false)
  })

  it('opens Maintenance for a bookmark predating the merge', async () => {
    baseEndpoints()
    // The real guard. An unrecognised id falls back to the FIRST section, so
    // without the retired-id map these shipped links would land on Timezone and
    // read as though deep-linking had simply stopped working.
    for (const retired of ['upgrade', 'restart']) {
      clearNuxtData()
      const component = await mountSuspended(Settings, { route: `/settings?section=${retired}` })
      await flushPromises()

      expect(
        component.find('[data-testid="settings-toc-item-maintenance"]').attributes('aria-current'),
        `?section=${retired} should open Maintenance`,
      ).toBe('page')
      expect(component.find('[data-testid="settings-toc-item-timezone"]').attributes('aria-current'))
        .toBeUndefined()
    }
  })

  it('shows both controls on the Maintenance section', async () => {
    baseEndpoints()
    registerEndpoint('/api/system/upgrade', () => ({
      available: true, unavailableReason: null, currentVersion: '0.17.73', latestVersion: '0.17.73',
      upgradeAvailable: false, installKind: 'bundle', runningTasks: 0, activeSubagentRuns: 0,
      commit: null,
    }))
    registerEndpoint('/api/system/upgrade/status', () => null)
    registerEndpoint('/api/system/restart', () => ({
      available: true, unavailableReason: null, mode: 'PROD', backendOnly: false,
      rebuildExpected: false, runningTasks: 0, activeSubagentRuns: 0, commit: null,
    }))

    const component = await mountSuspended(Settings, { route: '/settings?section=maintenance' })
    await flushPromises()
    await flushPromises()

    // The upgrade heading names the restart too: an operator reaching for the
    // upgrade needs to know it takes the instance down at the end.
    expect(component.text()).toContain('Upgrade and restart')
    expect(component.text()).toContain('Stops this instance and starts it again')
  })
})
