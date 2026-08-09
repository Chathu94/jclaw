import { test, expect, gotoPage, PLAY_SERVES_SITE } from './helpers'

/**
 * UAT-13 — Hosted apps and the user guide.
 *
 * The apps half is the only place the suite asserts on HTTP cache headers.
 * That is deliberate: hosted mini-apps keep stable filenames (index.html,
 * script.js), so if Play's default http.cacheControl ever reaches them an
 * operator edit stays invisible for an hour and looks like a broken save.
 * conf/routes:339 routes them through Application.appAsset for exactly this.
 */
test.describe('UAT-13 hosted apps', () => {
  test('apps registry renders with per-app controls', async ({ page }) => {
    await gotoPage(page, '/apps')
    await expect(page.getByTestId('create-app-button')).toBeVisible()
    await expect(page.getByPlaceholder('Search apps')).toBeVisible()
    await expect(page.locator('[data-testid^="app-card-"]').first()).toBeVisible()
  })

  test('registry endpoint lists apps', async ({ request }) => {
    const res = await request.get('/api/apps')
    expect(res.status()).toBe(200)
    expect(Array.isArray((await res.json()).apps), '/api/apps wraps its rows in { apps: [...] }').toBeTruthy()
  })

  test('a hosted app is served from its slug', async ({ page, request }) => {
    test.skip(!PLAY_SERVES_SITE, 'hosted apps are served by Play; not reachable in dev mode')
    const { apps } = await (await request.get('/api/apps')).json() as { apps: Array<{ id: string }> }
    test.skip(apps.length === 0, 'no hosted apps on this install')
    const slug = apps[0]!.id

    const res = await request.get(`/apps/${slug}/`)
    expect(res.status(), `/apps/${slug}/`).toBe(200)
    expect(res.headers()['content-type']).toContain('html')

    await page.goto(`/apps/${slug}/`)
    await page.waitForLoadState('domcontentloaded')
    expect(await page.content()).toContain('<body')
  })

  test('hosted app assets are not long-cached', async ({ request }) => {
    test.skip(!PLAY_SERVES_SITE, 'the no-cache header comes from Play; not reachable in dev mode')
    const { apps } = await (await request.get('/api/apps')).json() as { apps: Array<{ id: string }> }
    test.skip(apps.length === 0, 'no hosted apps on this install')
    const slug = apps[0]!.id

    const res = await request.get(`/apps/${slug}/`)
    const cc = res.headers()['cache-control'] ?? ''
    expect(cc, 'hosted apps must revalidate so operator edits surface').toContain('no-cache')
  })

  test('/apps without a slash stays the SPA listing page', async ({ page }) => {
    // The appAsset route is greedy; if it swallowed /apps the listing would
    // 404 instead of rendering.
    await gotoPage(page, '/apps')
    await expect(page.getByRole('heading', { name: 'Apps' })).toBeVisible()
  })
})

test.describe('UAT-14 user guide', () => {
  const CHAPTERS = [
    'getting-started', 'chat', 'prompts', 'agents', 'conversations-and-channels',
    'subagents', 'tasks', 'reminders', 'skills-tools-mcp', 'apps', 'settings',
    'memory', 'logs-and-dashboard',
  ]

  test('every chapter is listed in the contents', async ({ page }) => {
    await gotoPage(page, '/guide')
    for (const id of CHAPTERS) {
      await expect(page.getByTestId(`guide-toc-item-${id}`), `TOC entry ${id}`).toBeVisible()
    }
  })

  test('contents entry scrolls to its chapter', async ({ page }) => {
    await gotoPage(page, '/guide')
    await page.getByTestId('guide-toc-item-tasks').click()
    await expect(page).toHaveURL(/#tasks/)
    await expect(page.getByRole('heading', { name: 'Tasks', exact: true }).first()).toBeVisible()
  })

  test('guide anchor deep link resolves on a cold load', async ({ page }) => {
    await page.goto('/guide#memory')
    await page.waitForLoadState('domcontentloaded')
    await expect(page.locator('main')).toBeVisible()
    await expect(page).toHaveURL(/#memory/)
  })

  test('guide renders its callouts', async ({ page }) => {
    await gotoPage(page, '/guide')
    for (const kind of ['tip', 'note', 'gotcha']) {
      await expect(page.getByTestId(`guide-callout-${kind}`).first(), `${kind} callout`).toBeVisible()
    }
  })
})
