import { describe, it, expect } from 'vitest'
import { toolIconFor, toolIconClassFor } from '~/utils/tool-icons'

/**
 * utils/tool-icons.ts — the backend icon-key → Heroicons dictionary.
 *
 * The regression these cover: the agent detail page used to return null for a
 * key it didn't know, and `<component :is="null">` renders nothing, so `memory`
 * and `printer` appeared as empty boxes. Resolution must therefore be total.
 * Which keys exist is enforced JVM-side by ToolIconContractTest.
 */
describe('toolIconFor', () => {
  it('resolves the keys that previously rendered blank', () => {
    expect(toolIconFor('brain')).toBeTruthy()
    expect(toolIconFor('printer')).toBeTruthy()
  })

  it('never returns a falsy component, whatever the key', () => {
    for (const key of ['no-such-icon', '', null, undefined]) {
      expect(toolIconFor(key)).toBeTruthy()
    }
  })

  it('falls back to the same component the wrench key maps to', () => {
    expect(toolIconFor('no-such-icon')).toBe(toolIconFor('wrench'))
  })

  it('distinguishes known keys rather than collapsing them onto the fallback', () => {
    expect(toolIconFor('printer')).not.toBe(toolIconFor('wrench'))
    expect(toolIconFor('brain')).not.toBe(toolIconFor('document'))
  })
})

describe('toolIconClassFor', () => {
  it('rotates the send icon so it reads horizontally', () => {
    expect(toolIconClassFor('send')).toBe('-rotate-45')
  })

  it('returns an empty string for keys with no override', () => {
    expect(toolIconClassFor('printer')).toBe('')
    expect(toolIconClassFor(undefined)).toBe('')
  })
})
