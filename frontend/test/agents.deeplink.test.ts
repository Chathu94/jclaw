import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import Agents from '~/pages/agents/[[id]].vue'

/**
 * URL-addressable agent detail: /agents lists, /agents/<id> opens that agent.
 *
 * The route is the source of truth, so two arrival paths have to work and they
 * are not the same code path:
 *
 *   - the id is already in the URL when the page mounts (deep link, reload, or
 *     the command palette arriving from another page) — only the watcher's
 *     `immediate` run sees it;
 *   - the id changes while already on the page — nothing remounts, so only the
 *     watcher proper fires.
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
// Two live pages both watch the route; the older one reacts too and the case
// under test races against it.
enableAutoUnmount(afterEach)

beforeEach(async () => {
  // useFetch caches by URL across mounts; clear so each case refetches.
  clearNuxtData()
  // The router is shared across cases in this file, so a case that navigated
  // would otherwise leak its URL into the next one's mount.
  await useRouter().replace('/agents')
})

/** The edit form is the only place an <input> carries an agent's name. */
// eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: mountSuspended returns a proxy wrapper.
const openAgentName = (component: any): string[] =>
  component.findAll('input').map((i: { element: HTMLInputElement }) => i.element.value)

describe('Agents page — URL-addressable agent detail', () => {
  // Mounting the component with a `route` proves the page reacts to the param;
  // it does not prove Nuxt maps the URL to this page at all. Without this, a
  // filename that stopped generating the optional-param route would leave every
  // other case here green while /agents/2 404s in the browser.
  it('registers /agents and /agents/<id> as routes served by this page', () => {
    const router = useRouter()
    for (const path of ['/agents', '/agents/2']) {
      const matched = router.resolve(path).matched
      expect(matched.length, `${path} should match a route`).toBeGreaterThan(0)
    }
    expect(router.resolve('/agents/2').matched[0]?.components?.default)
      .toBe(router.resolve('/agents').matched[0]?.components?.default)
  })

  it('opens the agent named in the URL when the page mounts there', async () => {
    setupApi()
    const component = await mountSuspended(Agents, { route: '/agents/2' })
    await flushPromises()

    expect(openAgentName(component)).toContain('helper')
  })

  it('opens the agent when the URL changes while already on the page', async () => {
    setupApi()
    const component = await mountSuspended(Agents, { route: '/agents' })
    await flushPromises()
    expect(openAgentName(component)).not.toContain('helper')

    await useRouter().push('/agents/2')
    await flushPromises()

    expect(openAgentName(component)).toContain('helper')
  })

  it('returns the URL to /agents when the form is closed, so the same agent can be re-picked', async () => {
    setupApi()
    const router = useRouter()
    const component = await mountSuspended(Agents, { route: '/agents/2' })
    await flushPromises()
    expect(openAgentName(component)).toContain('helper')

    // Leave the form the way the breadcrumb does. Closing has to move the URL
    // too: if it stayed at /agents/2 with the form shut, re-picking that agent
    // would be a same-URL push the watcher never sees.
    useBreadcrumbExtra().value = null
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/agents'))
    expect(openAgentName(component)).not.toContain('helper')

    await router.push('/agents/2')
    await flushPromises()
    expect(openAgentName(component)).toContain('helper')
  })

  it('falls back to the listing for an id that matches no agent', async () => {
    setupApi()
    const router = useRouter()
    const component = await mountSuspended(Agents, { route: '/agents/999' })
    await flushPromises()

    expect(openAgentName(component)).not.toContain('helper')
    // The listing is still rendered — an unknown id must not strand the page on
    // an empty editor — and the URL is corrected rather than left lying.
    expect(component.text()).toContain('helper')
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/agents'))
  })
})
