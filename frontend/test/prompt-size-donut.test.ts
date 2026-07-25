import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import PromptSizeDonut from '~/components/PromptSizeDonut.vue'
import type { PromptBreakdownEntry } from '~/types/api'

const entry = (name: string, tokens: number): PromptBreakdownEntry =>
  ({ name, tokens, chars: tokens * 4 })

function mountDonut(entries: PromptBreakdownEntry[], props: Record<string, unknown> = {}) {
  return mount(PromptSizeDonut, { props: { entries, label: 'Prompt sections', ...props } })
}

describe('PromptSizeDonut', () => {
  it('draws one arc per entry, largest first', () => {
    const w = mountDonut([entry('small', 100), entry('big', 900)])
    // First circle is the background track; the rest are slices.
    const arcs = w.findAll('circle').slice(1)
    expect(arcs).toHaveLength(2)
    expect(w.findAll('li')[0]!.text()).toContain('big')
    expect(w.findAll('li')[1]!.text()).toContain('small')
  })

  it('sizes each arc by its share of the supplied total', () => {
    // 250 of 1000 = a quarter of the circumference.
    const w = mountDonut([entry('quarter', 250)], { total: 1000 })
    const arc = w.findAll('circle')[1]!
    const [dash] = arc.attributes('stroke-dasharray')!.split(' ').map(Number)
    const circumference = 2 * Math.PI * 60
    expect(dash).toBeCloseTo(circumference * 0.25, 5)
    expect(w.text()).toContain('25.0%')
  })

  it('normalises within the series when no total is given', () => {
    const w = mountDonut([entry('a', 300), entry('b', 100)])
    expect(w.text()).toContain('75.0%')
    expect(w.text()).toContain('25.0%')
  })

  it('collapses the long tail into a named Other slice', () => {
    // 12 entries with maxSlices=8 -> 8 arcs + 1 "Other (4)".
    const entries = Array.from({ length: 12 }, (_, i) => entry(`t${i}`, 100 - i))
    const w = mountDonut(entries)
    const arcs = w.findAll('circle').slice(1)
    expect(arcs).toHaveLength(9)
    expect(w.text()).toContain('Other (4)')
  })

  it('keeps every entry when the count fits under maxSlices', () => {
    const w = mountDonut([entry('a', 5), entry('b', 4)])
    expect(w.text()).not.toContain('Other')
  })

  it('skips zero-token entries so they do not render invisible arcs', () => {
    // Relevant Memories is legitimately 0 on a fresh breakdown.
    const w = mountDonut([entry('real', 100), entry('empty', 0)])
    expect(w.findAll('circle').slice(1)).toHaveLength(1)
    expect(w.text()).not.toContain('empty')
  })

  it('survives an all-empty series without dividing by zero', () => {
    const w = mountDonut([entry('empty', 0)])
    expect(w.findAll('circle').slice(1)).toHaveLength(0)
    expect(w.html()).not.toContain('NaN')
  })

  it('exposes a text alternative naming the slices and their shares', () => {
    const w = mountDonut([entry('Skills', 300), entry('Role', 100)])
    const label = w.find('svg').attributes('aria-label')!
    expect(w.find('svg').attributes('role')).toBe('img')
    expect(label).toContain('Prompt sections')
    expect(label).toContain('Skills 75.0%')
    expect(label).toContain('Role 25.0%')
  })
})
