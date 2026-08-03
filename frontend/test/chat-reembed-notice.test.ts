import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import ChatReembedNotice from '~/components/chat/ChatReembedNotice.vue'

/**
 * JCLAW-934. Two things matter here and neither is cosmetic: the notice must not appear
 * when nothing is running, and when it does appear it must not claim memories have
 * stopped being saved — they haven't, and telling an operator otherwise would push them
 * to stop using the app during a reindex for no reason.
 */
function status(o: Record<string, unknown> = {}) {
  return {
    running: false, processed: 0, total: 0,
    model: 'qwen3-embedding:0.6b', error: null, upToDate: true, ...o,
  }
}

describe('ChatReembedNotice', () => {
  beforeEach(() => {
    clearNuxtData()
  })

  it('renders nothing while no re-embed is running', async () => {
    registerEndpoint('/api/memories/reembed', () => status())
    const c = await mountSuspended(ChatReembedNotice)
    await flushPromises()

    expect(c.find('[data-testid="chat-reembed-notice"]').exists()).toBe(false)
  })

  it('shows progress while one is running', async () => {
    registerEndpoint('/api/memories/reembed', () => status({ running: true, processed: 312, total: 616, upToDate: false }))
    const c = await mountSuspended(ChatReembedNotice)
    await flushPromises()

    const notice = c.find('[data-testid="chat-reembed-notice"]')
    expect(notice.exists()).toBe(true)
    expect(notice.text()).toContain('312')
    expect(notice.text()).toContain('616')
  })

  it('says memories are still being saved, and names what actually degrades', async () => {
    registerEndpoint('/api/memories/reembed', () => status({ running: true, processed: 1, total: 9, upToDate: false }))
    const c = await mountSuspended(ChatReembedNotice)
    await flushPromises()

    const text = c.find('[data-testid="chat-reembed-notice"]').text()
    expect(text).toContain('still being saved')
    expect(text).toContain('duplicate detection')
    expect(text).toContain('semantic recall')
    expect(text).not.toContain('not being saved')
  })

  it('stays silent when the status endpoint fails', async () => {
    // A polling failure is not the operator's problem — it must not render a
    // half-populated notice or an error into the chat transcript.
    registerEndpoint('/api/memories/reembed', () => {
      throw createError({ statusCode: 500 })
    })
    const c = await mountSuspended(ChatReembedNotice)
    await flushPromises()

    expect(c.find('[data-testid="chat-reembed-notice"]').exists()).toBe(false)
  })
})
