import { test, expect, gotoPage } from './helpers'

/**
 * UAT-12 — Dashboard and observability.
 *
 * The dashboard is four independent panels over four endpoints; each fails
 * blank rather than loudly, so a panel that silently stopped populating looks
 * identical to a quiet install. These tests assert on rendered structure, not
 * on values, since the numbers move between runs.
 */
test.describe('UAT-12 dashboard', () => {
  test('all four panels render', async ({ page }) => {
    await gotoPage(page, '/')
    for (const name of ['Chat Cost', 'Chat Performance', 'Chat Compression', 'Recent Activity']) {
      await expect(page.getByRole('heading', { name }), `${name} panel`).toBeVisible()
    }
  })

  test('workspace size gauge renders a value', async ({ page }) => {
    await gotoPage(page, '/')
    await expect(page.getByTestId('workspace-size')).toBeVisible()
    const value = await page.getByTestId('workspace-size-value').innerText()
    expect(value.trim().length, 'workspace size must render a figure').toBeGreaterThan(0)
  })

  test('cost table renders its column headers', async ({ page }) => {
    await gotoPage(page, '/')
    for (const col of ['Model', 'Turns', 'Prompt', 'Completion', 'Cached', 'Cost']) {
      await expect(page.getByRole('button', { name: col, exact: true }).first(), `column ${col}`).toBeVisible()
    }
  })

  test('period toggles switch the cost window', async ({ page }) => {
    await gotoPage(page, '/')
    const thirty = page.getByRole('button', { name: '30d', exact: true }).first()
    await thirty.click()
    // The panel must survive the refetch rather than collapsing to empty.
    await expect(page.getByRole('heading', { name: 'Chat Cost' })).toBeVisible()
    await expect(async () => {
      expect((await page.locator('main').innerText()).length).toBeGreaterThan(200)
    }).toPass({ timeout: 10_000 })
  })

  test('metrics endpoints back the panels', async ({ request }) => {
    for (const path of [
      '/api/metrics/cost',
      '/api/metrics/latency',
      '/api/metrics/latency/rows',
      '/api/metrics/compression',
      '/api/metrics/db-pool',
      '/api/workspace/stats',
    ]) {
      expect((await request.get(path)).status(), path).toBe(200)
    }
  })

  test('logs page renders and searches', async ({ page }) => {
    await gotoPage(page, '/logs')
    const search = page.getByPlaceholder('Search messages...')
    await expect(search).toBeVisible()
    await search.fill('zzz-no-such-log-line-zzz')
    // Must not throw the page away — an empty result set is the correct answer.
    await expect(page.locator('main')).toBeVisible()
  })

  test('logs endpoint backs the page', async ({ request }) => {
    expect((await request.get('/api/logs')).status()).toBe(200)
  })
})
