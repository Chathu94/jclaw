import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import GithubStarsButton from '~/components/GithubStarsButton.vue'

const REPO_API = 'https://api.github.com/repos/tsukhani/jclaw'
const CACHE_KEY = 'jclaw-github-stars'
const PAST_TTL_MS = 7 * 60 * 60 * 1000

// $fetch is auto-imported from #build/fetch.mjs, so it's a module binding —
// vi.stubGlobal can't reach it and registerEndpoint only intercepts relative
// Nitro routes, not api.github.com. mockNuxtImport replaces the binding itself.
const { fetchMock } = vi.hoisted(() => ({ fetchMock: vi.fn() }))
mockNuxtImport('$fetch', () => fetchMock)

async function mount() {
  const c = await mountSuspended(GithubStarsButton)
  await flushPromises()
  return c
}

function cache(count: number, ageMs: number) {
  localStorage.setItem(CACHE_KEY, JSON.stringify({ count, at: Date.now() - ageMs }))
}

beforeEach(() => {
  localStorage.clear()
  fetchMock.mockReset()
  fetchMock.mockResolvedValue({ stargazers_count: 3300 })
})

describe('GithubStarsButton', () => {
  it('links to the public GitHub mirror in a new tab', async () => {
    const a = (await mount()).find('a')
    expect(a.attributes('href')).toBe('https://github.com/tsukhani/jclaw')
    expect(a.attributes('target')).toBe('_blank')
    expect(a.attributes('rel')).toBe('noopener')
  })

  it('renders the live star count fetched from the GitHub API', async () => {
    const c = await mount()
    expect(fetchMock).toHaveBeenCalledWith(REPO_API)
    expect(c.text()).toContain('3.3k')
  })

  it('names the exact count for screen readers, not the abbreviation', async () => {
    const c = await mount()
    expect(c.find('a').attributes('aria-label')).toBe('JClaw on GitHub — 3300 stars')
  })

  it.each([
    [999, '999'],
    [1000, '1k'],
    [3300, '3.3k'],
    [12_400, '12k'],
  ])('formats %i stars as %s', async (stars, label) => {
    fetchMock.mockResolvedValue({ stargazers_count: stars })
    expect((await mount()).text()).toContain(label)
  })

  it('caches the count so a remount inside the TTL makes no second request', async () => {
    await mount()
    expect(fetchMock).toHaveBeenCalledTimes(1)

    const c = await mount()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(c.text()).toContain('3.3k')
  })

  it('refetches once the cached count is older than the TTL', async () => {
    cache(3300, PAST_TTL_MS)
    fetchMock.mockResolvedValue({ stargazers_count: 3400 })

    const c = await mount()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(c.text()).toContain('3.4k')
  })

  it('degrades to an icon-only link when the API is unreachable', async () => {
    fetchMock.mockRejectedValue(new Error('offline'))
    const c = await mount()
    expect(c.find('a').exists()).toBe(true)
    expect(c.find('span').exists()).toBe(false)
    expect(c.find('a').attributes('aria-label')).toBe('JClaw on GitHub')
  })

  it('keeps showing the cached count when a stale-cache refresh fails', async () => {
    cache(3300, PAST_TTL_MS)
    fetchMock.mockRejectedValue(new Error('rate limited'))

    expect((await mount()).text()).toContain('3.3k')
  })

  it('ignores a corrupted cache entry and refetches', async () => {
    localStorage.setItem(CACHE_KEY, 'not json')

    const c = await mount()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(c.text()).toContain('3.3k')
  })
})
