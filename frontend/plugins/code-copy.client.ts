// Copy-to-clipboard for the code blocks rendered by ~/utils/chat-markdown.
// Registered app-wide because renderMarkdown feeds several surfaces (chat
// transcript, thinking card, subagent row, the agent editor's prompt and
// workspace previews) and the markup is identical on all of them.
//
// Delegated from the document rather than bound per button: the markup arrives
// via v-html, where DOMPurify has already stripped any inline handler, and the
// streaming bubble replaces those nodes ~12.5 times a second.
//
// The handler is exported so it can be unit-tested against a detached DOM
// without booting the plugin, mirroring why chat-markdown.ts lives outside the
// page SFC.

/** Matches the flash used by the per-message copy in useChatMessageActions. */
const COPIED_FLASH_MS = 1200

export async function handleCodeCopyClick(ev: MouseEvent) {
  // EventTarget is not necessarily an Element — a click dispatched straight at
  // `document` (the guided-tour dismissal does this) has no closest().
  const target = ev.target
  if (!(target instanceof Element)) return

  const btn = target.closest('.code-block > .code-copy')
  if (!(btn instanceof HTMLButtonElement)) return

  // Read the <code> child, not the wrapper: pre.textContent would swallow the
  // button's own "Copy" label into the copied text.
  const code = btn.parentElement?.querySelector('pre code')
  if (!code) return

  try {
    await navigator.clipboard.writeText(code.textContent ?? '')
    btn.dataset.copied = 'true'
    btn.textContent = 'Copied'
    setTimeout(() => {
      delete btn.dataset.copied
      btn.textContent = 'Copy'
    }, COPIED_FLASH_MS)
  }
  catch (e) {
    console.error('Failed to copy code block:', e)
  }
}

export default defineNuxtPlugin(() => {
  document.addEventListener('click', handleCodeCopyClick)
})
