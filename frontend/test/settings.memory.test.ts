import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'
import { looksLikeEmbeddingModel } from '~/utils/embeddingModels'

/**
 * Settings → Memory Embeddings (JCLAW-932).
 *
 * The panel's whole job is to stop an operator committing an embedding model that
 * will not actually be used: the dimension has to come from the model rather than
 * from typing, and a provider that silently serves a different model has to be
 * rejected. These tests pin that gate.
 */
const EMBED_MODEL = 'text-embedding-nomic-embed-text-v1.5'
const CHAT_MODEL = 'qwen3.5-4b-mlx'

function baseEndpoints(configEntries: { key: string, value: string }[] = []) {
  registerEndpoint('/api/agents', () => [])
  registerEndpoint('/api/channels', () => [])
  registerEndpoint('/api/ocr/status', () => ({ providers: [] }))
  registerEndpoint('/api/config', () => ({ entries: configEntries }))
  registerEndpoint('/api/providers', () => [
    { name: 'lm-studio', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 0, supportedModalities: ['SUBSCRIPTION'] },
  ])
}

function vectorEnabledConfig() {
  return [
    { key: 'memory.jpa.vector.enabled', value: 'true' },
    {
      key: 'provider.lm-studio.models',
      value: JSON.stringify([{ id: EMBED_MODEL, name: EMBED_MODEL }, { id: CHAT_MODEL, name: CHAT_MODEL }]),
    },
  ]
}

async function mountSettingsSection(sectionId: string) {
  const component = await mountSuspended(Settings)
  ;(component.vm as unknown as { activeSectionId: string }).activeSectionId = sectionId
  await flushPromises()
  await flushPromises()
  return component
}

describe('embedding-model shortlist heuristic', () => {
  it('shortlists ids that name a known embedding family', () => {
    expect(looksLikeEmbeddingModel('text-embedding-3-small')).toBe(true)
    expect(looksLikeEmbeddingModel(EMBED_MODEL)).toBe(true)
    expect(looksLikeEmbeddingModel('bge-large-en-v1.5')).toBe(true)
    expect(looksLikeEmbeddingModel('mxbai-embed-large')).toBe(true)
  })

  it('does not shortlist chat models', () => {
    expect(looksLikeEmbeddingModel(CHAT_MODEL)).toBe(false)
    expect(looksLikeEmbeddingModel('gpt-4o')).toBe(false)
  })

  it('matches on the display name too, since ids are not always descriptive', () => {
    expect(looksLikeEmbeddingModel('provider-internal-id-42', 'Nomic Embed Text')).toBe(true)
  })
})

describe('Settings page — Memory Embeddings', () => {
  beforeEach(() => {
    clearNuxtData()
  })

  it('shows the section with vector memory off by default', async () => {
    baseEndpoints()
    const component = await mountSettingsSection('memory')

    expect(component.text()).toContain('Memory Embeddings')
    expect(component.find('[data-testid="memory-vector-toggle"]').text()).toBe('Enable')
    expect(component.find('[data-testid="memory-embedding-provider"]').exists()).toBe(false)
  })

  it('offers provider and model pickers once vector memory is enabled', async () => {
    baseEndpoints(vectorEnabledConfig())
    const component = await mountSettingsSection('memory')

    expect(component.find('[data-testid="memory-embedding-provider"]').exists()).toBe(true)
    expect(component.text()).toContain('lm-studio')
  })

  it('lists models discovered from the provider, not the curated chat catalog', async () => {
    // The bug this covers: provider.<name>.models is the operator's chat list, and
    // embedding models are never added to it — so on a real instance the picker
    // offered only chat models and the embedding model in use was unselectable.
    baseEndpoints([...vectorEnabledConfig(), { key: 'memory.jpa.vector.provider', value: 'lm-studio' }])
    registerEndpoint('/api/providers/lm-studio/discover-models', () => ({
      models: [{ id: EMBED_MODEL, name: EMBED_MODEL }, { id: CHAT_MODEL, name: CHAT_MODEL }],
      count: 2,
    }))
    const component = await mountSettingsSection('memory')
    await flushPromises()

    const values = component.find('[data-testid="memory-embedding-model"]')
      .findAll('option').map(o => o.attributes('value'))
    expect(values).toContain(EMBED_MODEL)
  })

  it('keeps the saved model selectable even when the catalog omits it', async () => {
    // Otherwise the panel cannot represent the configuration it is editing.
    baseEndpoints([
      { key: 'memory.jpa.vector.enabled', value: 'true' },
      { key: 'memory.jpa.vector.provider', value: 'lm-studio' },
      { key: 'memory.jpa.vector.model', value: EMBED_MODEL },
      { key: 'provider.lm-studio.models', value: JSON.stringify([{ id: CHAT_MODEL, name: CHAT_MODEL }]) },
    ])
    const component = await mountSettingsSection('memory')
    await flushPromises()

    const values = component.find('[data-testid="memory-embedding-model"]')
      .findAll('option').map(o => o.attributes('value'))
    expect(values).toContain(EMBED_MODEL)
  })

  it('shortlists the model dropdown but keeps the rest reachable', async () => {
    baseEndpoints(vectorEnabledConfig())
    const component = await mountSettingsSection('memory')

    await component.find('[data-testid="memory-embedding-provider"]').setValue('lm-studio')
    await flushPromises()
    await flushPromises()

    const options = component.find('[data-testid="memory-embedding-model"]').findAll('option')
    const values = options.map(o => o.attributes('value'))
    expect(values).toContain(EMBED_MODEL)
    expect(values).not.toContain(CHAT_MODEL)

    // The heuristic narrows, it does not decide — the hidden model stays reachable.
    await component.find('[data-testid="memory-embedding-show-all"]').trigger('click')
    await flushPromises()
    const afterValues = component.find('[data-testid="memory-embedding-model"]')
      .findAll('option').map(o => o.attributes('value'))
    expect(afterValues).toContain(CHAT_MODEL)
  })

  it('fills dimensions from the probe and only then allows saving', async () => {
    baseEndpoints(vectorEnabledConfig())
    registerEndpoint('/api/providers/lm-studio/embedding-probe', () => ({
      provider: 'lm-studio', model: EMBED_MODEL, ok: true, dimensions: 768, error: null,
    }))
    const component = await mountSettingsSection('memory')

    await component.find('[data-testid="memory-embedding-provider"]').setValue('lm-studio')
    await flushPromises()
    await flushPromises()
    await component.find('[data-testid="memory-embedding-model"]').setValue(EMBED_MODEL)
    await flushPromises()

    // Unprobed: the model is picked but not yet confirmed, so Save stays shut.
    expect(component.find('[data-testid="memory-embedding-save"]').attributes('disabled')).toBeDefined()

    await component.find('[data-testid="memory-embedding-probe"]').trigger('click')
    await flushPromises()

    expect(component.find('[data-testid="memory-embedding-probe-ok"]').text()).toContain('768')
    expect(component.find('[data-testid="memory-embedding-dimensions"]').attributes('value')).toBe('768')
    expect(component.find('[data-testid="memory-embedding-save"]').attributes('disabled')).toBeUndefined()
  })

  it('refuses to save a model the provider does not honour', async () => {
    baseEndpoints(vectorEnabledConfig())
    // The LM Studio behaviour from JCLAW-931: a chat model returns a valid vector,
    // but for a different model than the one requested.
    registerEndpoint('/api/providers/lm-studio/embedding-probe', () => ({
      provider: 'lm-studio',
      model: CHAT_MODEL,
      ok: false,
      dimensions: 0,
      error: `Provider served '${EMBED_MODEL}' instead of '${CHAT_MODEL}'`,
    }))
    const component = await mountSettingsSection('memory')

    await component.find('[data-testid="memory-embedding-provider"]').setValue('lm-studio')
    await flushPromises()
    await flushPromises()
    await component.find('[data-testid="memory-embedding-show-all"]').trigger('click')
    await flushPromises()
    await component.find('[data-testid="memory-embedding-model"]').setValue(CHAT_MODEL)
    await flushPromises()
    await component.find('[data-testid="memory-embedding-probe"]').trigger('click')
    await flushPromises()

    expect(component.find('[data-testid="memory-embedding-probe-error"]').text()).toContain('instead of')
    expect(component.find('[data-testid="memory-embedding-save"]').attributes('disabled')).toBeDefined()
  })

  it('warns that changing the model strands existing vectors', async () => {
    baseEndpoints([...vectorEnabledConfig(),
      { key: 'memory.jpa.vector.provider', value: 'lm-studio' },
      { key: 'memory.jpa.vector.model', value: EMBED_MODEL },
    ])
    registerEndpoint('/api/providers/lm-studio/embedding-probe', () => ({
      provider: 'lm-studio', model: CHAT_MODEL, ok: true, dimensions: 1024, error: null,
    }))
    const component = await mountSettingsSection('memory')

    // No change yet — no warning.
    expect(component.find('[data-testid="memory-embedding-reembed-warning"]').exists()).toBe(false)

    await component.find('[data-testid="memory-embedding-show-all"]').trigger('click')
    await flushPromises()
    await component.find('[data-testid="memory-embedding-model"]').setValue(CHAT_MODEL)
    await flushPromises()

    expect(component.find('[data-testid="memory-embedding-reembed-warning"]').exists()).toBe(true)
  })
})
