import { describe, it, expect, vi } from 'vitest'
import { ref } from 'vue'
import {
  filterPrompts,
  isPromptArgumentContext,
  isPromptCommandText,
  promptCompletionSource,
} from '~/composables/promptCompletionSource'
import { slashCommandSource } from '~/composables/slashCommandSource'
import { useComposerCompleter } from '~/composables/useComposerCompleter'
import type { Prompt, SlashCommand } from '~/types/api'

/**
 * JCLAW-1072: /prompt search — context detection, filtering, insertion, and the
 * guards that keep a half-finished "/prompt …" out of the model's message list.
 */

function makePrompt(id: number, title: string, content: string, tags: string | null = null): Prompt {
  return {
    id,
    title,
    content,
    tags,
    category: 'ENGINEERING',
    categoryLabel: 'Engineering',
    createdAt: null,
    updatedAt: null,
  }
}

const PROMPTS: Prompt[] = [
  makePrompt(1, 'Code review', 'Review this diff for correctness.', 'engineering,quality'),
  makePrompt(2, 'RFP draft', 'Draft an RFP response.', 'sales'),
  makePrompt(3, 'Design review', 'Critique this design.', null),
]

const COMMANDS: SlashCommand[] = [
  { literal: '/new', name: 'new', description: 'Start a fresh conversation' },
]

function source(over: { prompts?: Prompt[], loading?: boolean, ensureLoaded?: () => void } = {}) {
  return promptCompletionSource({
    prompts: ref(over.prompts ?? PROMPTS),
    loading: ref(over.loading ?? false),
    ensureLoaded: over.ensureLoaded ?? (() => {}),
  })
}

// ── Context detection ──

describe('isPromptArgumentContext', () => {
  it('needs the trailing space, like /model', () => {
    expect(isPromptArgumentContext('/prompt ')).toBe(true)
    expect(isPromptArgumentContext('/prompt rev')).toBe(true)
    expect(isPromptArgumentContext('/prompt')).toBe(false)
  })

  it('is case-insensitive and rejects unrelated text', () => {
    expect(isPromptArgumentContext('/PROMPT x')).toBe(true)
    expect(isPromptArgumentContext('/promptx ')).toBe(false)
    expect(isPromptArgumentContext('tell me about /prompt ')).toBe(false)
  })
})

describe('isPromptCommandText', () => {
  it('covers the bare literal as well as the argument form', () => {
    expect(isPromptCommandText('/prompt')).toBe(true)
    expect(isPromptCommandText('/prompt ')).toBe(true)
    expect(isPromptCommandText('/prompt rev')).toBe(true)
  })

  it('leaves real commands and ordinary text sendable', () => {
    expect(isPromptCommandText('/new')).toBe(false)
    expect(isPromptCommandText('/model gpt')).toBe(false)
    expect(isPromptCommandText('write me a prompt')).toBe(false)
  })
})

// ── Filtering ──

describe('filterPrompts', () => {
  it('returns all prompts for an empty query', () => {
    expect(filterPrompts(PROMPTS, '/prompt ')).toHaveLength(3)
  })

  it('matches on title, case-insensitively', () => {
    expect(filterPrompts(PROMPTS, '/prompt REV').map(p => p.title))
      .toEqual(['Code review', 'Design review'])
  })

  it('matches on tags', () => {
    expect(filterPrompts(PROMPTS, '/prompt sales').map(p => p.title)).toEqual(['RFP draft'])
  })

  it('tolerates a null tags column', () => {
    expect(filterPrompts(PROMPTS, '/prompt critique')).toEqual([])
  })

  it('returns empty outside prompt context', () => {
    expect(filterPrompts(PROMPTS, '/prompt')).toEqual([])
  })
})

// ── Inside the shared completer ──

describe('prompt source in the shared completer', () => {
  function completer(over: Parameters<typeof source>[0] = {}) {
    return useComposerCompleter([slashCommandSource(ref(COMMANDS)), source(over)])
  }

  it('lists matches as title + category', () => {
    const ac = completer()
    ac.update('/prompt code')
    expect(ac.open.value).toBe(true)
    expect(ac.activeSourceId.value).toBe('prompt')
    expect(ac.ariaLabel.value).toBe('Saved prompt options')
    expect(ac.options.value).toEqual([
      { value: '1', label: 'Code review', detail: 'Engineering' },
    ])
  })

  it('accepting replaces the whole composer with the prompt body', () => {
    const ac = completer()
    ac.update('/prompt code')
    expect(ac.accept('/prompt code')).toBe('Review this diff for correctness.')
  })

  it('shows a status row while the library is loading, and refuses to accept it', () => {
    const ac = completer({ loading: true })
    ac.update('/prompt ')
    expect(ac.open.value).toBe(true)
    expect(ac.options.value).toEqual([
      { value: '', detail: 'Loading prompts…', disabled: true },
    ])
    expect(ac.accept('/prompt ')).toBeNull()
  })

  it('stays open on a no-match status row instead of closing', () => {
    const ac = completer()
    ac.update('/prompt zzz-nothing')
    expect(ac.open.value).toBe(true)
    expect(ac.options.value[0]?.detail).toBe('No matching prompts')
    expect(ac.accept('/prompt zzz-nothing')).toBeNull()
  })

  it('calls ensureLoaded on entering prompt context, not before', () => {
    const ensureLoaded = vi.fn()
    const ac = completer({ ensureLoaded })
    ac.update('/')
    expect(ensureLoaded).not.toHaveBeenCalled()
    ac.update('/prompt ')
    expect(ensureLoaded).toHaveBeenCalled()
  })

  it('blocks send for any /prompt text, including after close()', () => {
    const ac = completer()
    ac.update('/prompt zzz')
    expect(ac.blocksSend.value).toBe(true)
    ac.close()
    expect(ac.blocksSend.value).toBe(true)
    ac.update('a normal message')
    expect(ac.blocksSend.value).toBe(false)
  })

  it('does not block send for a real backend command', () => {
    const ac = completer()
    ac.update('/new')
    expect(ac.blocksSend.value).toBe(false)
  })
})
