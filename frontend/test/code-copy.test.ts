import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { handleCodeCopyClick } from '~/plugins/code-copy.client'
import { renderMarkdown } from '~/utils/chat-markdown'

/**
 * The handler runs against real rendered markdown rather than a hand-written
 * fixture, so a change to chat-markdown's code renderer that breaks the
 * button/`pre code` relationship fails here instead of silently copying the
 * wrong string.
 */
function mountRendered(markdown: string): HTMLButtonElement {
  document.body.innerHTML = renderMarkdown(markdown)
  return document.querySelector('.code-copy') as HTMLButtonElement
}

const writeText = vi.fn(() => Promise.resolve())

beforeEach(() => {
  vi.useFakeTimers()
  writeText.mockClear()
  Object.defineProperty(navigator, 'clipboard', {
    value: { writeText },
    configurable: true,
  })
})

afterEach(() => {
  vi.useRealTimers()
  document.body.innerHTML = ''
})

describe('handleCodeCopyClick', () => {
  it('copies the code block source without the button label', async () => {
    const btn = mountRendered('```js\nconsole.log("hi")\n```')
    await handleCodeCopyClick({ target: btn } as unknown as MouseEvent)
    expect(writeText).toHaveBeenCalledWith('console.log("hi")\n')
  })

  it('copies entity-escaped source back as the original characters', async () => {
    const btn = mountRendered('```html\n<div class="x">a && b</div>\n```')
    await handleCodeCopyClick({ target: btn } as unknown as MouseEvent)
    expect(writeText).toHaveBeenCalledWith('<div class="x">a && b</div>\n')
  })

  it('flashes Copied and reverts after the timeout', async () => {
    const btn = mountRendered('```\nx\n```')
    await handleCodeCopyClick({ target: btn } as unknown as MouseEvent)
    expect(btn.textContent).toBe('Copied')
    expect(btn.dataset.copied).toBe('true')

    vi.advanceTimersByTime(1200)
    expect(btn.textContent).toBe('Copy')
    expect(btn.dataset.copied).toBeUndefined()
  })

  it('ignores clicks outside a copy button', async () => {
    document.body.innerHTML = '<div class="code-block"><pre><code>x</code></pre></div>'
    const pre = document.querySelector('pre') as HTMLElement
    await handleCodeCopyClick({ target: pre } as unknown as MouseEvent)
    expect(writeText).not.toHaveBeenCalled()
  })

  it('ignores a click whose target is not an Element', async () => {
    // A click dispatched directly at `document` has no closest(); the delegated
    // listener sees every click in the app, so this is reachable in practice.
    await expect(
      handleCodeCopyClick({ target: document } as unknown as MouseEvent),
    ).resolves.toBeUndefined()
    expect(writeText).not.toHaveBeenCalled()
  })

  it('leaves the button untouched when the clipboard write rejects', async () => {
    writeText.mockRejectedValueOnce(new Error('denied'))
    const err = vi.spyOn(console, 'error').mockImplementation(() => {})
    const btn = mountRendered('```\nx\n```')

    await handleCodeCopyClick({ target: btn } as unknown as MouseEvent)

    expect(btn.textContent).toBe('Copy')
    expect(err).toHaveBeenCalled()
    err.mockRestore()
  })
})
