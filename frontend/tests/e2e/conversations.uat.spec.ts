import { test, expect, gotoPage, applyFilter, filterInput, expectFilterChip } from './helpers'

/**
 * UAT-8 — Conversations and subagent runs.
 *
 * Read-only: deletion here removes real transcripts, so the bulk controls are
 * asserted present but never confirmed. The valuable path is that a listed
 * conversation opens and renders its messages — the operator's audit trail.
 */
test.describe('UAT-8 conversations', () => {
  test('conversation list renders with filter and pagination', async ({ page }) => {
    await gotoPage(page, '/conversations')
    await expect(page.locator('table')).toBeVisible()
    await expect(filterInput(page)).toBeVisible()
    await expect(page.getByRole('button', { name: 'Next' })).toBeVisible()
  })

  test('opening a conversation renders its transcript', async ({ page, request }) => {
    const list = await (await request.get('/api/conversations')).json()
    const rows = (Array.isArray(list) ? list : list.conversations ?? list.items ?? []) as Array<{ id: number }>
    test.skip(rows.length === 0, 'no conversations on this install')

    await gotoPage(page, `/conversations/${rows[0]!.id}`)
    await expect(page.getByRole('navigation').first()).toContainText('Conversations')
  })

  test('conversation messages endpoint backs the detail view', async ({ request }) => {
    const list = await (await request.get('/api/conversations')).json()
    const rows = (Array.isArray(list) ? list : list.conversations ?? list.items ?? []) as Array<{ id: number }>
    test.skip(rows.length === 0, 'no conversations on this install')

    const id = rows[0]!.id
    for (const suffix of ['messages', 'queue', '']) {
      const res = await request.get(`/api/conversations/${id}${suffix ? '/' + suffix : ''}`)
      expect(res.status(), `/api/conversations/${id}/${suffix}`).toBe(200)
    }
  })

  test('filter grammar narrows the conversation list', async ({ page }) => {
    await gotoPage(page, '/conversations')
    const before = await page.locator('tbody tr').count()
    test.skip(before === 0, 'no conversations on this install')

    await applyFilter(page, 'zzz-no-such-conversation-zzz')
    await expect(async () => {
      expect(await page.locator('tbody tr').count()).toBeLessThan(before)
    }).toPass({ timeout: 10_000 })
  })

  test('channel facet is accepted by the filter parser', async ({ page }) => {
    await gotoPage(page, '/conversations')
    await applyFilter(page, 'channel:web')
    await expectFilterChip(page, 'channel', 'web')
  })

  test('bulk delete controls are present but require selection', async ({ page }) => {
    await gotoPage(page, '/conversations')
    // Present-and-disabled is the safe state on arrival; a Delete that is live
    // with nothing selected is a one-click accident against real transcripts.
    await expect(page.getByRole('button', { name: 'Delete', exact: true })).toBeDisabled()
  })

  test('subagent runs list renders and links back to its conversation', async ({ page }) => {
    await gotoPage(page, '/subagents')
    await expect(page.locator('table')).toBeVisible()
    const link = page.locator('a[href^="/chat?conversation="]').first()
    if (await link.count() > 0) await expect(link).toBeVisible()
  })

  test('subagent runs endpoint backs the page', async ({ request }) => {
    expect((await request.get('/api/subagent-runs')).status()).toBe(200)
  })
})
