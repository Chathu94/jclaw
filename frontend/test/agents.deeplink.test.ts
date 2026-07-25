import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import Agents from '~/pages/agents.vue'

/**
 * Command-palette → agent edit handoff.
 *
 * The palette sets usePendingAgentEdit() and navigates to /agents; this spec
 * covers the consuming half. Two arrival paths have to work, and they are not
 * the same code path:
 *
 *   - from another page — the id is already set when /agents mounts, so only
 *     the watcher's `immediate` run sees it;
 *   - from /agents itself — nothing remounts, so only the watcher proper fires.
 */

function setupApi() {
  registerEndpoint('/api/agents', () => [
    {
      id: 1,
      name: 'main',
      modelProvider: 'ollama-cloud',
      modelId: 'kimi-k2.5',
      enabled: true,
      isMain: true,
      providerConfigured: true,
      thinkingMode: null,
      compressionEnabled: true,
      compressionJson: true,
      compressionCode: true,
      compressionText: true,
      compressionTargetRatio: 0.3,
      acpAllowed: false,
      memoryAutocaptureEnabled: true,
      memoryAutocaptureModelInherited: true,
      memoryAutocaptureProvider: 'ollama-cloud',
      memoryAutocaptureModel: 'kimi-k2.5',
      createdAt: '2026-04-01T10:00:00Z',
      updatedAt: '2026-04-22T10:00:00Z',
    },
    {
      id: 2,
      name: 'helper',
      modelProvider: 'openai',
      modelId: 'gpt-4',
      enabled: true,
      isMain: false,
      providerConfigured: true,
      thinkingMode: null,
      compressionEnabled: false,
      compressionJson: false,
      compressionCode: false,
      compressionText: false,
      compressionTargetRatio: 0.3,
      acpAllowed: false,
      memoryAutocaptureEnabled: true,
      memoryAutocaptureModelInherited: true,
      memoryAutocaptureProvider: 'openai',
      memoryAutocaptureModel: 'gpt-4',
      createdAt: '2026-04-10T10:00:00Z',
      updatedAt: '2026-04-20T10:00:00Z',
    },
  ])
  registerEndpoint('/api/config', () => ({ entries: [] }))
  registerEndpoint('/api/tools/meta', () => [])
  registerEndpoint('/api/agents/2/tools', () => [])
  registerEndpoint('/api/agents/2/skills', () => [])
  registerEndpoint('/api/agents/2/shell/effective-allowlist', () => ({ global: [], bySkill: {} }))
  registerEndpoint('/api/agents/2/workspace/AGENT.md', () => ({ content: 'helper instructions' }))
  registerEndpoint('/api/config/agent.helper.queue.mode', () => ({ value: 'collect' }))
  registerEndpoint('/api/config/agent.helper.shell.bypassAllowlist', () => ({ value: 'false' }))
  registerEndpoint('/api/config/agent.helper.shell.allowGlobalPaths', () => ({ value: 'false' }))
}

// Without this, the previous case's Agents instance stays mounted and alive.
// Two live pages both watch the pending id; the older one wins the race, nulls
// it, and the case under test re-reads null and never opens its form.
enableAutoUnmount(afterEach)

beforeEach(() => {
  // useFetch caches by URL across mounts; clear so each case refetches.
  clearNuxtData()
  usePendingAgentEdit().value = null
})

/** The edit form is the only place an <input> carries an agent's name. */
// eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: mountSuspended returns a proxy wrapper.
const openAgentName = (component: any): string[] =>
  component.findAll('input').map((i: { element: HTMLInputElement }) => i.element.value)

describe('Agents page — command-palette agent handoff', () => {
  it('opens the target agent form when the id is already pending on mount', async () => {
    setupApi()
    usePendingAgentEdit().value = 2
    const component = await mountSuspended(Agents)
    await flushPromises()

    expect(openAgentName(component)).toContain('helper')
  })

  it('opens the target agent form when the id arrives while already on the page', async () => {
    setupApi()
    const component = await mountSuspended(Agents)
    await flushPromises()
    expect(openAgentName(component)).not.toContain('helper')

    usePendingAgentEdit().value = 2
    await flushPromises()

    expect(openAgentName(component)).toContain('helper')
  })

  it('consumes the id so the same agent can be picked twice in a row', async () => {
    setupApi()
    const component = await mountSuspended(Agents)
    await flushPromises()

    usePendingAgentEdit().value = 2
    await flushPromises()
    expect(usePendingAgentEdit().value).toBeNull()

    // Leave the form the way the breadcrumb does, then pick the same agent
    // again. An un-consumed id would make this second pick a no-op write, and
    // the palette would look dead for whichever agent was opened last.
    useBreadcrumbExtra().value = null
    await flushPromises()
    expect(openAgentName(component)).not.toContain('helper')

    usePendingAgentEdit().value = 2
    await flushPromises()
    expect(openAgentName(component)).toContain('helper')
  })

  it('ignores an id that matches no agent instead of blanking the list', async () => {
    setupApi()
    usePendingAgentEdit().value = 999
    const component = await mountSuspended(Agents)
    await flushPromises()

    expect(openAgentName(component)).not.toContain('helper')
    // The listing is still rendered — an unknown id must not strand the page on
    // an empty editor.
    expect(component.text()).toContain('helper')
    expect(usePendingAgentEdit().value).toBeNull()
  })
})
