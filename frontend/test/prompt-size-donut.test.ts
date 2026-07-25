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

  it('draws every entry rather than collapsing a tail into "Other"', () => {
    // A real prompt runs to ~50 contributors; the tail is where the surprises
    // are, so all of it is named.
    const entries = Array.from({ length: 50 }, (_, i) => entry(`t${i}`, 100 - i))
    const w = mountDonut(entries)
    expect(w.findAll('circle').slice(1)).toHaveLength(50)
    expect(w.text()).not.toContain('Other')
    expect(w.text()).toContain('t49')
  })

  it('gives every slice its own colour past the end of the curated palette', () => {
    // The 12-colour palette used to wrap with `% length`, painting two slices of
    // one chart identically. Generated hues have to keep 50 apart.
    const entries = Array.from({ length: 50 }, (_, i) => entry(`t${i}`, 100 - i))
    const w = mountDonut(entries)
    const colors = w.findAll('circle').slice(1).map(c => c.attributes('stroke'))
    expect(colors).toHaveLength(50)
    expect(new Set(colors).size).toBe(50)
  })

  it('splits the legend into two columns flanking the donut', () => {
    const entries = Array.from({ length: 7 }, (_, i) => entry(`t${i}`, 100 - i))
    const w = mountDonut(entries)
    const columns = w.findAll('ul')
    expect(columns).toHaveLength(2)
    // Biggest first, down the left column then down the right; the odd row goes
    // left so the taller column is the one that starts the reading order.
    expect(columns[0]!.findAll('li')).toHaveLength(4)
    expect(columns[1]!.findAll('li')).toHaveLength(3)
    expect(columns[0]!.text()).toContain('t0')
    expect(columns[1]!.text()).toContain('t6')
    expect(columns[1]!.text()).not.toContain('t0')
  })

  it('labels the numeric columns on both halves of the legend', () => {
    // The halves are independent lists side by side, so a header over one of
    // them would leave the other's three numbers unexplained.
    const entries = Array.from({ length: 6 }, (_, i) => entry(`t${i}`, 100 - i))
    const w = mountDonut(entries)
    const headers = w.findAll('[data-testid="legend-header"]')
    expect(headers).toHaveLength(2)
    for (const h of headers) {
      expect(h.text()).toContain('Section')
      expect(h.text()).toContain('Chars')
      expect(h.text()).toContain('Tokens')
      expect(h.text()).toContain('% of total')
    }
  })

  it('carries no total row — the dialog header already states the totals', () => {
    const w = mountDonut([entry('a', 300), entry('b', 100)])
    expect(w.find('[data-testid="donut-total"]').exists()).toBe(false)
    expect(w.text()).not.toContain('Total')
  })

  it('sizes the donut off the legend height rather than a fixed box', () => {
    // The wrapper stretches to the row height — set by the taller legend column
    // — and the SVG fills it, so the graphic can't overhang the rows. A fixed
    // height on either would put the overhang back.
    const w = mountDonut([entry('a', 300), entry('b', 100)])
    expect(w.find('svg').classes()).toContain('h-full')
    expect(w.find('svg').element.parentElement?.className).toContain('lg:h-auto')
    expect(w.find('svg').classes()).not.toContain('lg:h-72')
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
