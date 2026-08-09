import { test, expect, gotoPage } from './helpers'

/**
 * UAT-11 — Channels and bindings.
 *
 * Read-only. Binding "Test" buttons send a real message to a real chat and
 * the Funnel toggle changes whether this install is reachable from the public
 * internet, so neither is clicked — both are asserted present instead.
 */
test.describe('UAT-11 channels', () => {
  test('channels index lists all three transports', async ({ page }) => {
    await gotoPage(page, '/channels')
    for (const name of ['Telegram', 'Slack', 'WhatsApp']) {
      await expect(page.getByRole('heading', { name })).toBeVisible()
    }
    await expect(page.locator('a[href="/channels/telegram"]')).toBeVisible()
    await expect(page.locator('a[href="/channels/slack"]')).toBeVisible()
    await expect(page.locator('a[href="/channels/whatsapp"]')).toBeVisible()
  })

  test('tailscale funnel control is present and not toggled', async ({ page }) => {
    await gotoPage(page, '/channels')
    await expect(page.getByRole('button', { name: /Funnel/ })).toBeVisible()
  })

  test('per-channel binding pages render', async ({ page }) => {
    for (const path of ['/channels/telegram', '/channels/slack', '/channels/whatsapp']) {
      const errors: string[] = []
      page.on('pageerror', err => errors.push(String(err)))
      await gotoPage(page, path)
      expect(errors, `page errors on ${path}`).toHaveLength(0)
    }
  })

  test('binding list endpoints answer for every transport', async ({ request }) => {
    for (const path of [
      '/api/channels/telegram/bindings',
      '/api/channels/slack/bindings',
      '/api/channels/whatsapp/bindings',
      '/api/bindings',
    ]) {
      const res = await request.get(path)
      expect(res.status(), path).toBe(200)
    }
  })

  test('per-channel config is readable', async ({ request }) => {
    for (const type of ['telegram', 'slack', 'whatsapp', 'web']) {
      const res = await request.get(`/api/channels/${type}`)
      expect([200, 404], `/api/channels/${type}`).toContain(res.status())
    }
  })

  test('dashboard channel aggregate is available', async ({ request }) => {
    const res = await request.get('/api/channels/active')
    expect(res.status()).toBe(200)
  })

  test('webhook endpoints are not session-gated', async ({ playwright }) => {
    // Webhooks authenticate by their own signature mechanism; a 401 here would
    // mean AuthCheck had started intercepting them and every inbound message
    // from Telegram/Slack/WhatsApp would silently stop arriving.
    const anon = await playwright.request.newContext({
      baseURL: process.env.JCLAW_E2E_BASE_URL || 'http://localhost:3000',
      storageState: { cookies: [], origins: [] },
    })
    const res = await anon.get('/api/webhooks/whatsapp')
    expect(res.status(), 'webhook verify must not answer 401').not.toBe(401)
    await anon.dispose()
  })
})
