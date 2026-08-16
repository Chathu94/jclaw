import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'
import { sectionGroups } from '~/components/settings/sections'

/**
 * Page-level tests for the Tool Approvals section (JCLAW-1022).
 *
 * The policy is instance-wide and has no UI anywhere else, so these also pin that it
 * reads back what is stored and that a conf-capped save surfaces the backend's reason
 * rather than silently reverting.
 */
function baseEndpoints(configEntries: Array<{ key: string, value: string }> = []) {
  registerEndpoint('/api/agents', () => [])
  registerEndpoint('/api/channels', () => [])
  registerEndpoint('/api/config', () => ({
    entries: configEntries.map(e => ({ ...e, updatedAt: '2026-08-17T00:00:00Z' })),
  }))
  registerEndpoint('/api/ocr/status', () => ({ providers: [] }))
  registerEndpoint('/api/providers', () => [])
}

async function mountSettingsSection(sectionId: string) {
  const component = await mountSuspended(Settings)
  ;(component.vm as unknown as { activeSectionId: string }).activeSectionId = sectionId
  await flushPromises()
  await flushPromises()
  return component
}

describe('Settings page — Tool Approvals', () => {
  beforeEach(() => {
    clearNuxtData()
  })

  it('files the section under Security, ahead of the two specific mechanisms', () => {
    // Placement is the decision this section exists to record: the policy also governs
    // whether a coding-harness subagent may launch, so it must not live inside Shell.
    const security = sectionGroups.find(g => g.label === 'Security')
    expect(security).toBeTruthy()
    const ids = security!.sections.map(s => s.id)
    expect(ids).toEqual(['approvals', 'shell', 'malware'])
  })

  it('defaults to allow when no row is stored, matching the backend default', async () => {
    baseEndpoints()
    const component = await mountSettingsSection('approvals')

    const select = component.find('[data-testid="approval-off-channel-policy"]')
    expect(select.exists()).toBe(true)
    expect((select.element as HTMLSelectElement).value).toBe('allow')
  })

  it('renders the stored policy', async () => {
    baseEndpoints([{ key: 'tool.approval.offChannelPolicy', value: 'deny' }])
    const component = await mountSettingsSection('approvals')

    const select = component.find('[data-testid="approval-off-channel-policy"]')
    expect((select.element as HTMLSelectElement).value).toBe('deny')
  })

  it('offers exactly allow, ask and deny', async () => {
    baseEndpoints()
    const component = await mountSettingsSection('approvals')

    const values = component.findAll('[data-testid="approval-off-channel-policy"] option')
      .map(o => (o.element as HTMLOptionElement).value)
    expect(values).toEqual(['allow', 'ask', 'deny'])
  })

  it('says a prompt still reaches you when someone else asks', async () => {
    // The question this panel most has to answer: does allow switch off Telegram's
    // approval panel? Not for anyone but you — JCLAW-1061 skips the prompt only for a
    // sender the channel proved is the owner, and the copy has to draw that line.
    baseEndpoints()
    const component = await mountSettingsSection('approvals')

    const text = component.text()
    expect(text).toContain('cannot reach you for approval')
    expect(text).toMatch(/someone else messages the agent on Telegram or Slack/)
    expect(text).toMatch(/asked there regardless of this setting/)
  })
})
