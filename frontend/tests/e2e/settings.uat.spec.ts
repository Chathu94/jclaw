import { test, expect, gotoPage } from './helpers'

/**
 * UAT-9 — Settings.
 *
 * Twenty-six panels behind one page, each lazily mounted from the TOC. The
 * historical failure mode is a panel that throws on mount and leaves the
 * content column blank while the TOC still highlights it (see the cold-boot
 * top-level-await regression), so every section is visited rather than
 * spot-checked.
 *
 * No setting is saved. Restart and Upgrade are visited but their action
 * buttons are never clicked — both stop this JVM.
 */
const SECTIONS = [
  'timezone', 'logging', 'performance', 'uploads', 'printers', 'password',
  'upgrade', 'restart', 'providers', 'search', 'transcription', 'speech',
  'ocr', 'image-caption', 'image-generation', 'video-interpretation',
  'video-generation', 'chat', 'subagents', 'tasks', 'skills',
  'memory-limits', 'memory', 'memory-reranker', 'shell', 'malware',
]

test.describe('UAT-9 settings', () => {
  test('every section is listed in the table of contents', async ({ page }) => {
    await gotoPage(page, '/settings')
    for (const id of SECTIONS) {
      await expect(page.getByTestId(`settings-toc-item-${id}`), `TOC entry ${id}`).toBeVisible()
    }
  })

  for (const id of SECTIONS) {
    test(`${id} panel mounts without error`, async ({ page }) => {
      const errors: string[] = []
      page.on('console', (msg) => {
        if (msg.type() === 'error') errors.push(msg.text())
      })
      page.on('pageerror', err => errors.push(String(err)))

      await gotoPage(page, '/settings')
      await page.getByTestId(`settings-toc-item-${id}`).click()

      // A panel that throws on mount leaves the column empty while the TOC
      // still marks it active — assert on rendered content, not on the click.
      const panel = page.locator('main')
      await expect(panel).toBeVisible()
      await expect(async () => {
        const text = (await panel.innerText()).trim()
        expect(text.length, `${id} panel rendered no content`).toBeGreaterThan(40)
      }).toPass({ timeout: 10_000 })

      expect(errors, `console errors mounting ${id}`).toHaveLength(0)
    })
  }

  test('section deep link opens that panel directly', async ({ page }) => {
    await gotoPage(page, '/settings?section=providers')
    await expect(async () => {
      expect((await page.locator('main').innerText())).toContain('Provider')
    }).toPass({ timeout: 10_000 })
  })

  test('config read endpoints back the panels', async ({ request }) => {
    for (const path of ['/api/config', '/api/logging/levels', '/api/providers', '/api/ocr/status']) {
      expect((await request.get(path)).status(), path).toBe(200)
    }
  })

  test('restart preflight is a read, and is not triggered here', async ({ page }) => {
    // Visiting the panel must not arm anything. The POST that reboots the JVM
    // is deliberately never exercised by this suite.
    await gotoPage(page, '/settings')
    await page.getByTestId('settings-toc-item-restart').click()
    await expect(page.locator('main')).toBeVisible()
  })
})
