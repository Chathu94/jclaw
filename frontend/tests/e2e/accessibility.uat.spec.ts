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
 * Known-failing rules, per page. An entry records a real defect, not a false
 * positive, so the suite still catches NEW violations instead of being
 * permanently red. The test also fails if a listed rule stops firing, so an
 * entry cannot outlive the fix it was waiting on.
 *
 * Empty is the correct state — add to it only alongside a ticket.
 */
const KNOWN_VIOLATIONS: Record<string, string[]> = {}

type AxeResult = { violations: Array<{ id: string, impact: string, help: string, nodes: Array<{ target: string[] }> }> }

for (const path of PAGES) {
  test(`${path} has no serious or critical a11y violations`, async ({ page }) => {
    await gotoPage(page, path)
    await page.addScriptTag({ path: AXE_PATH })

    const results = await page.evaluate(async () => {
      // @ts-expect-error axe attaches itself to the page global on inject.
      return await globalThis.axe.run(document, {
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

/**
 * Regression guard for JCLAW-1013. The axe scan above catches the violation in
 * the abstract; these assert the two properties the fix exists to provide, so
 * a future refactor that reintroduces a clickable row fails here with a
 * readable reason rather than only as an axe rule id.
 */
test('agent row controls are siblings, not nested inside one control', async ({ page, request }) => {
  const agents = await (await request.get('/api/agents')).json() as Array<{ name: string, isMain: boolean }>
  const custom = agents.find(a => !a.isMain)
  test.skip(!custom, 'no custom agent on this install')

  await gotoPage(page, '/agents')
  const controls = [
    page.getByRole('button', { name: custom!.name, exact: true }),
    page.getByRole('button', { name: `Delete ${custom!.name}` }),
  ]

  for (const control of controls) {
    await expect(control).toBeVisible()
    // Search from parentElement, not from the element: closest() starts at the
    // node itself, so a <button> would always match itself and the assertion
    // could never fail.
    const nestedIn = await control.evaluate((el) => {
      const owner = el.parentElement?.closest('button, a, [role="button"]')
      if (!owner) return null
      const viaRole = owner.getAttribute('role') ? '[role=button]' : ''
      return `${owner.tagName}${viaRole}`
    })
    expect(nestedIn, 'control must not sit inside another interactive element').toBeNull()
  }
})

test('an agent can be opened from the keyboard', async ({ page, request }) => {
  const agents = await (await request.get('/api/agents')).json() as Array<{ name: string }>
  const target = agents[0]!

  await gotoPage(page, '/agents')
  await page.getByRole('button', { name: target.name, exact: true }).focus()
  await page.keyboard.press('Enter')

  await expect(page).toHaveURL(new RegExp(`/agents/${target.name}$`), { timeout: 10_000 })
})
