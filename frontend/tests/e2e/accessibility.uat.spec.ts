import { test, expect, gotoPage } from './helpers'
import { createRequire } from 'node:module'

/**
 * UAT-16 — WCAG 2.2 AA smoke.
 *
 * axe-core is injected from node_modules rather than pulled from a CDN — the
 * suite must run on an install with no outbound network.
 *
 * Scoped to serious+critical violations. axe's minor/moderate tiers are noisy
 * on a Tailwind codebase and would make this a permanent red, which teaches
 * everyone to ignore it. Contrast is excluded here on purpose: Tailwind v4
 * emits oklch(), which axe's rgb parser mis-reads, so contrast is audited by
 * /wcag-audit against computed styles instead.
 */
const require_ = createRequire(import.meta.url)
const AXE_PATH = require_.resolve('axe-core/axe.min.js')

const PAGES = ['/', '/chat', '/agents', '/tasks', '/prompts', '/memories', '/settings', '/conversations']

/**
 * Known-failing rules, per page. These are real defects, not false positives —
 * the entry exists so the suite still catches NEW violations instead of being
 * permanently red. Remove the entry when the underlying markup is fixed; the
 * test fails if a listed rule stops firing, so a stale baseline cannot rot.
 *
 * /agents nested-interactive: each agent row is a <div role="button"
 * tabindex="0"> that contains real <button>s (enable toggle, thinking pill,
 * delete). Screen readers announce the row as one control and the inner
 * buttons become unreachable in some AT. WCAG 4.1.2 — needs the row demoted
 * to a non-interactive container with an explicit open affordance.
 */
const KNOWN_VIOLATIONS: Record<string, string[]> = {
  '/agents': ['nested-interactive'],
}

type AxeResult = { violations: Array<{ id: string, impact: string, help: string, nodes: Array<{ target: string[] }> }> }

for (const path of PAGES) {
  test(`${path} has no serious or critical a11y violations`, async ({ page }) => {
    await gotoPage(page, path)
    await page.addScriptTag({ path: AXE_PATH })

    const results = await page.evaluate(async () => {
      // @ts-expect-error axe is attached to window by the injected script.
      return await window.axe.run(document, {
        resultTypes: ['violations'],
        rules: { 'color-contrast': { enabled: false } },
      }) as AxeResult
    }) as AxeResult

    const serious = results.violations.filter(v => v.impact === 'serious' || v.impact === 'critical')
    const baseline = KNOWN_VIOLATIONS[path] ?? []
    const unexpected = serious.filter(v => !baseline.includes(v.id))
    const detail = unexpected
      .map(v => `${v.id} (${v.impact}) — ${v.help} @ ${v.nodes.slice(0, 3).map(n => n.target.join(' ')).join(', ')}`)
      .join('\n')

    expect(unexpected, `new serious/critical a11y violations on ${path}:\n${detail}`).toHaveLength(0)

    // A baseline entry that no longer fires means the defect was fixed and the
    // entry must go — otherwise it silently masks a future regression.
    const stale = baseline.filter(id => !serious.some(v => v.id === id))
    expect(stale, `stale KNOWN_VIOLATIONS on ${path} — delete these entries`).toHaveLength(0)
  })
}

test('every page exposes a single main landmark', async ({ page }) => {
  for (const path of PAGES) {
    await gotoPage(page, path)
    expect(await page.locator('main').count(), `main landmark count on ${path}`).toBe(1)
  }
})

test('keyboard focus reaches the primary navigation', async ({ page }) => {
  await gotoPage(page, '/')
  await page.keyboard.press('Tab')
  const focused = await page.evaluate(() => document.activeElement?.tagName ?? '')
  expect(['A', 'BUTTON', 'INPUT'], 'first tab stop must be interactive').toContain(focused)
})
