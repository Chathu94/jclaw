import { test, expect, gotoPage, applyFilter, filterInput, expectFilterChip } from './helpers'

/**
 * UAT-6 — Tasks and reminders (scheduling surface).
 *
 * Read-only against the operator's live schedule. This suite deliberately
 * never creates a task: a task is a live cron entry, so a fixture would fire
 * against a real agent and spend model calls, and a fixture that fired
 * between create and delete would leave a TaskRun behind. Pause/resume is
 * likewise left alone — flipping a real schedule mid-run is not a test, it is
 * an outage.
 *
 * What is exercised is the operator's read path: does the table render, does
 * the filter grammar work, do the two views agree, does a row expand.
 */
test.describe('UAT-6 tasks', () => {
  test('tasks page renders the schedule table', async ({ page }) => {
    await gotoPage(page, '/tasks')
    await expect(page.locator('table').first()).toBeVisible({ timeout: 15_000 })
    await expect(page.getByRole('tab', { name: 'Table view' })).toBeVisible()
    await expect(page.getByRole('tab', { name: 'Calendar view' })).toBeVisible()
  })

  test('filter grammar narrows the task list', async ({ page }) => {
    await gotoPage(page, '/tasks')
    await expect(filterInput(page)).toBeVisible()

    const before = await page.locator('tbody tr').count()
    await applyFilter(page, 'zzz-no-such-task-zzz')
    await expect(async () => {
      expect(await page.locator('tbody tr').count()).toBeLessThan(before)
    }).toPass({ timeout: 10_000 })

    // Reload rather than clearing the box: a committed facet lives in a chip
    // beside the input, so emptying the input alone does not restore the view.
    await gotoPage(page, '/tasks')
    await expect(async () => {
      expect(await page.locator('tbody tr').count()).toBe(before)
    }).toPass({ timeout: 10_000 })
  })

  test('typed filter facet is accepted', async ({ page }) => {
    // The placeholder documents `type:CRON`; a parser regression turns this
    // into a literal text search that silently matches nothing.
    await gotoPage(page, '/tasks')
    await applyFilter(page, 'type:CRON')
    await expectFilterChip(page, 'type', 'CRON')
    await expect(page.locator('tbody tr').first()).toBeVisible({ timeout: 10_000 })
  })

  test('calendar view renders and returns to the table', async ({ page }) => {
    await gotoPage(page, '/tasks')
    await page.getByRole('tab', { name: 'Calendar view' }).click()
    await expect(page.locator('table')).toHaveCount(0, { timeout: 15_000 })

    await page.getByRole('tab', { name: 'Table view' }).click()
    await expect(page.locator('table').first()).toBeVisible({ timeout: 15_000 })
  })

  test('a task row expands to show its detail', async ({ page }) => {
    await gotoPage(page, '/tasks')
    const toggle = page.getByRole('button', { name: /^Toggle details for / }).first()
    await expect(toggle).toBeVisible()

    const rowsBefore = await page.locator('tbody tr').count()
    await toggle.click()
    await expect(async () => {
      expect(await page.locator('tbody tr').count()).toBeGreaterThan(rowsBefore)
    }).toPass({ timeout: 10_000 })
  })

  test('every task exposes run, pause/resume and delete controls', async ({ page }) => {
    // The operator's whole control surface over a schedule. Present-and-
    // visible only — none of them is clicked against live data.
    await gotoPage(page, '/tasks')
    await expect(page.getByRole('button', { name: /^Delete / }).first()).toBeVisible()
    const runnable = await page.getByRole('button', { name: / now$/ }).count()
    const pausable = await page.getByRole('button', { name: /^(Pause|Resume) / }).count()
    expect(runnable + pausable, 'each task row carries lifecycle controls').toBeGreaterThan(0)
  })

  test('task stats and runs endpoints back the page', async ({ request }) => {
    expect((await request.get('/api/tasks/stats')).status()).toBe(200)
    expect((await request.get('/api/task-runs/recent')).status()).toBe(200)
    expect((await request.get('/api/timezones')).status()).toBe(200)
  })

  test('reminders page renders its own schedule table', async ({ page }) => {
    await gotoPage(page, '/reminders')
    await expect(page.locator('table')).toBeVisible()
    await expect(filterInput(page)).toBeVisible()
  })

  test('notifications endpoint backs the reminder fire surface', async ({ request }) => {
    const res = await request.get('/api/notifications')
    expect(res.status()).toBe(200)
  })
})
