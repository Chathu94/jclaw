import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'

/**
 * Settings → LLM Providers, Remote/Local grouping (JCLAW-182, JCLAW-1102).
 *
 * The classification moved from a hardcoded name Set to provider.<name>.local — the same key
 * that lets memory embeddings use a provider, so the sections an operator sees here and the
 * providers the embedding picker offers cannot disagree. These tests drive it against names
 * that contradict the old Set, which is what proves the grouping is no longer name-based.
 */
function declare(name: string, local: boolean) {
  return { key: `provider.${name}.local`, value: String(local) }
}

function baseEndpoints(providers: { name: string }[], entries: { key: string, value: string }[]) {
  registerEndpoint('/api/agents', () => [])
  registerEndpoint('/api/channels', () => [])
  registerEndpoint('/api/ocr/status', () => ({ providers: [] }))
  registerEndpoint('/api/providers', () => providers)
  registerEndpoint('/api/config', { method: 'GET', handler: () => ({ entries }) })
}

async function mountProviders() {
  const component = await mountSuspended(Settings)
  ;(component.vm as unknown as { activeSectionId: string }).activeSectionId = 'providers'
  await flushPromises()
  await flushPromises()
  return component
}

/** The name of the section heading a provider's card is rendered under. */
function sectionOf(component: Awaited<ReturnType<typeof mountProviders>>, label: string) {
  const headings = component.findAll('h3').filter(h => ['Remote', 'Local'].includes(h.text().trim()))
  let current: string | null = null
  for (const el of component.findAll('h3, .bg-surface-elevated')) {
    const text = el.text().trim()
    if (headings.some(h => h.element === el.element)) current = text
    else if (text.startsWith(label)) return current
  }
  return null
}

describe('Remote/Local grouping', () => {
  beforeEach(() => clearNuxtData())

  it('groups by the served classification, not by provider name', async () => {
    // Deliberately contradicts the legacy name Set: llama-cpp was hardcoded local, openai
    // hardcoded remote. If either lands in the old section the grouping is still name-based.
    baseEndpoints(
      [],
      [
        { key: 'provider.llama-cpp.baseUrl', value: 'http://100.108.220.119:8080/v1' },
        declare('llama-cpp', false),
        { key: 'provider.openai.baseUrl', value: 'https://api.openai.com/v1' },
        declare('openai', true),
      ],
    )
    const component = await mountProviders()

    expect(sectionOf(component, 'llama.cpp')).toBe('Remote')
    expect(sectionOf(component, 'OpenAI')).toBe('Local')
  })

  it('groups a provider that is absent from /api/providers', async () => {
    baseEndpoints(
      [],
      [{ key: 'provider.llama-cpp.baseUrl', value: 'http://100.108.220.119:8080/v1' }, declare('llama-cpp', true)],
    )
    const component = await mountProviders()

    expect(sectionOf(component, 'llama.cpp')).toBe('Local')
  })

  it('renders the classification as an editable row, which is how it is changed', async () => {
    // There is no bespoke control (JCLAW-1102): the generic provider.<name>.* row is the
    // escape hatch, and ConfigService rejects a non-boolean on the way in.
    baseEndpoints(
      [],
      [{ key: 'provider.llama-cpp.baseUrl', value: 'http://100.108.220.119:8080/v1' }, declare('llama-cpp', true)],
    )
    const component = await mountProviders()

    expect(component.findAll('span').filter(el => el.text().trim() === 'local')).toHaveLength(1)
  })
})
