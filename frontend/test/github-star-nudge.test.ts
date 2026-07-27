import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import GithubStarNudge from '~/components/GithubStarNudge.vue'

// Literal on purpose: asserting against the shared constant would pass even
// if that constant were renamed, which would silently orphan every flag
// already written into real users' browsers.
const SEEN_KEY = 'jclaw-star-nudge-seen'
const APPEAR_DELAY_MS = 1200
const VISIBLE_MS = 8000

/**
 * jsdom reports an all-zero rect for every element, which the component reads
 * as "no anchor" and stays hidden. Plant a stub anchor reporting a realistic
 * header-pill rect so placement runs the way it does in a browser.
 */
function plantAnchor() {
  const el = document.createElement('a')
  el.setAttribute('data-star-anchor', '')
  el.getBoundingClientRect = () => ({
    left: 1800, right: 1880, top: 12, bottom: 44, width: 80, height: 32, x: 1800, y: 12,
    toJSON: () => ({}),
  })
  document.body.appendChild(el)
  return el
}

/** Mount, then run past the appear delay so the nudge has decided. */
async function mountAndSettle(props: { suppressed?: boolean } = {}) {
  const c = await mountSuspended(GithubStarNudge, { props })
  await vi.advanceTimersByTimeAsync(APPEAR_DELAY_MS)
  await c.vm.$nextTick()
  return c
}

beforeEach(() => {
  vi.useFakeTimers()
  localStorage.clear()
  document.body.innerHTML = ''
  vi.stubGlobal('innerWidth', 1920)
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('GithubStarNudge', () => {
  it('appears after the delay and reads "Leave a star!"', async () => {
    plantAnchor()
    const c = await mountAndSettle()
    expect(c.text()).toContain('Leave a star!')
  })

  it('stays hidden until the appear delay has elapsed', async () => {
    plantAnchor()
    const c = await mountSuspended(GithubStarNudge)
    await vi.advanceTimersByTimeAsync(APPEAR_DELAY_MS - 100)
    await c.vm.$nextTick()
    expect(c.text()).not.toContain('Leave a star!')
  })

  it('never shows a second time once it has been seen', async () => {
    plantAnchor()
    localStorage.setItem(SEEN_KEY, '1')
    const c = await mountAndSettle()
    expect(c.text()).not.toContain('Leave a star!')
  })

  it('records that it was seen, so the next load stays quiet', async () => {
    plantAnchor()
    await mountAndSettle()
    expect(localStorage.getItem(SEEN_KEY)).toBe('1')
  })

  it('fades out on its own after the visible window', async () => {
    plantAnchor()
    const c = await mountAndSettle()
    expect(c.text()).toContain('Leave a star!')

    await vi.advanceTimersByTimeAsync(VISIBLE_MS)
    await c.vm.$nextTick()
    expect(c.text()).not.toContain('Leave a star!')
  })

  it('dismisses early when the user clicks anywhere', async () => {
    plantAnchor()
    const c = await mountAndSettle()

    document.dispatchEvent(new Event('click'))
    await c.vm.$nextTick()
    expect(c.text()).not.toContain('Leave a star!')
  })

  it('holds back while the guided-tour intro is up', async () => {
    plantAnchor()
    const c = await mountAndSettle({ suppressed: true })
    expect(c.text()).not.toContain('Leave a star!')
  })

  it('does not burn its seen flag while suppressed', async () => {
    plantAnchor()
    await mountAndSettle({ suppressed: true })
    expect(localStorage.getItem(SEEN_KEY)).toBeNull()
  })

  // The fresh-install path: intro dialog auto-opens, the user goes straight
  // into the walkthrough, and the nudge must sit out the whole thing — then
  // still get its showing, without waiting for a page reload.
  it('appears once the tour ends, without needing a reload', async () => {
    plantAnchor()
    const c = await mountAndSettle({ suppressed: true })
    expect(c.text()).not.toContain('Leave a star!')

    await c.setProps({ suppressed: false })
    await vi.advanceTimersByTimeAsync(APPEAR_DELAY_MS)
    await c.vm.$nextTick()

    expect(c.text()).toContain('Leave a star!')
    expect(localStorage.getItem(SEEN_KEY)).toBe('1')
  })

  it('stands down if the tour starts after it is already up', async () => {
    plantAnchor()
    const c = await mountAndSettle()
    expect(c.text()).toContain('Leave a star!')

    await c.setProps({ suppressed: true })
    await c.vm.$nextTick()
    expect(c.text()).not.toContain('Leave a star!')
  })

  it('does not reappear after the tour if it already had its showing', async () => {
    plantAnchor()
    const c = await mountAndSettle()
    expect(c.text()).toContain('Leave a star!')

    await c.setProps({ suppressed: true })
    await c.setProps({ suppressed: false })
    await vi.advanceTimersByTimeAsync(APPEAR_DELAY_MS)
    await c.vm.$nextTick()

    expect(c.text()).not.toContain('Leave a star!')
  })

  it('stays hidden when the GitHub pill is not on the page', async () => {
    const c = await mountAndSettle()
    expect(c.text()).not.toContain('Leave a star!')
    expect(localStorage.getItem(SEEN_KEY)).toBeNull()
  })

  it('points its arrow tip at the pill rather than a fixed offset', async () => {
    plantAnchor()
    const c = await mountAndSettle()

    // Anchor spans x 1800–1880 (centre 1840), bottom 44, in a 1920px viewport.
    // top  = 44 + 8 - TIP_Y(18)                    = 34
    // right = 1920 - 1840 - (SVG_W(120) - TIP_X(106)) = 66
    const style = c.find('[aria-hidden="true"]').attributes('style')
    expect(style).toContain('top: 34px')
    expect(style).toContain('right: 66px')
  })

  it('is inert: hidden from screen readers and transparent to clicks', async () => {
    plantAnchor()
    const c = await mountAndSettle()
    const el = c.find('[aria-hidden="true"]')
    expect(el.exists()).toBe(true)
    expect(el.classes()).toContain('pointer-events-none')
  })
})
