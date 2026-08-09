import { test, expect, SIDEBAR_ROUTES, gotoPage, collectConsoleErrors } from './helpers'

/**
 * UAT-3 — Navigation, routing and the command palette.
 *
 * pages.smoke.spec.ts already asserts that ten top-level pages mount. This
 * spec covers what smoke does not: that the sidebar link actually routes
 * there (client-side, no full reload), that a deep link survives a browser
 * refresh, and that the palette can reach a page by keyboard alone.
 */
test.describe('UAT-3 navigation', () => {
  test('sidebar exposes every documented route', async ({ page }) => {
    await gotoPage(page, '/')
    for (const { path } of SIDEBAR_ROUTES) {
      await expect(page.locator(`a[href="${path}"]`).first(), `sidebar link ${path}`).toBeVisible()
    }
  })

  test('sidebar navigates client-side without a full page load', async ({ page }) => {
    await gotoPage(page, '/')
    // Stamp the window; a full document reload would wipe it, proving the
    // click went through the server instead of the Vue router.
    await page.evaluate(() => {
      (window as unknown as { __uat: boolean }).__uat = true
    })

    for (const path of ['/tasks', '/agents', '/prompts', '/memories']) {
      await page.locator(`a[href="${path}"]`).first().click()
      await expect(page).toHaveURL(new RegExp(`${path}$`))
      await expect(page.locator('main')).toBeVisible()
    }

    const survived = await page.evaluate(() => (window as unknown as { __uat?: boolean }).__uat === true)
    expect(survived, 'sidebar navigation must stay client-side').toBeTruthy()
  })

  test('secondary pages outside the smoke list render', async ({ page }) => {
    // Smoke covers ten pages; these seven are the remainder of the sidebar.
    const secondary = ['/prompts', '/subagents', '/apps', '/reminders', '/mcp-servers', '/memories', '/guide']
    for (const path of secondary) {
      const errors = collectConsoleErrors(page)
      await gotoPage(page, path)
      expect(errors(), `console errors on ${path}`).toHaveLength(0)
    }
  })

  test('channel sub-pages render', async ({ page }) => {
    for (const path of ['/channels/telegram', '/channels/slack', '/channels/whatsapp']) {
      await gotoPage(page, path)
      await expect(page.getByRole('navigation').first()).toContainText('Channels')
    }
  })

  test('deep link survives a browser refresh', async ({ page }) => {
    await gotoPage(page, '/settings')
    await page.reload()
    await page.waitForLoadState('domcontentloaded')
    await expect(page.locator('main')).toBeVisible()
    await expect(page).toHaveURL(/\/settings$/)
  })

  test('query-string deep link is preserved through a reload', async ({ page }) => {
    await gotoPage(page, '/settings?section=tasks')
    await page.reload()
    await page.waitForLoadState('domcontentloaded')
    await expect(page).toHaveURL(/section=tasks/)
    await expect(page.locator('main')).toBeVisible()
  })

  test('command palette opens with Ctrl+K and navigates', async ({ page }) => {
    await gotoPage(page, '/')
    await page.keyboard.press('ControlOrMeta+k')

    const search = page.getByPlaceholder(/search/i).first()
    await expect(search).toBeVisible()
    await search.fill('tasks')
    await page.keyboard.press('Enter')

    await expect(page).toHaveURL(/\/tasks$/, { timeout: 10_000 })
  })

  test('command palette closes on Escape without navigating', async ({ page }) => {
    await gotoPage(page, '/agents')
    await page.keyboard.press('ControlOrMeta+k')
    const search = page.getByPlaceholder(/search/i).first()
    await expect(search).toBeVisible()

    await page.keyboard.press('Escape')
    await expect(search).toBeHidden()
    await expect(page).toHaveURL(/\/agents$/)
  })
})
