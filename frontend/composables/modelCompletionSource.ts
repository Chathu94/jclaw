import type { Ref } from 'vue'
import type { CompletionOption, CompletionSource } from '~/composables/useComposerCompleter'
import type { Provider } from '~/composables/useProviders'

/**
 * Completion source for the `/model NAME` slash-command argument (JCLAW-114).
 * Pure-logic helpers sit at the top so they're unit-testable without a Vue
 * mount; {@link modelCompletionSource} adapts them to the shared composer
 * completer (JCLAW-1071), which owns the popup state machine.
 */

/** Literal prefix that activates the completer. Trailing space required. */
export const MODEL_COMMAND_PREFIX = '/model '

/** Fixed sub-keywords of /model that are NOT provider/model names — no completion. */
const FIXED_SUB_KEYWORDS = new Set(['status', 'reset'])

/** True when `text` starts with the /model command and has past the command literal. */
export function isModelArgumentContext(text: string): boolean {
  if (!text) return false
  const lower = text.toLowerCase()
  if (!lower.startsWith(MODEL_COMMAND_PREFIX)) return false
  const arg = text.slice(MODEL_COMMAND_PREFIX.length).trim()
  // When the user has typed a fixed sub-keyword like "status" or "reset",
  // they're not typing a provider/model — don't show the completer.
  if (FIXED_SUB_KEYWORDS.has(arg.toLowerCase())) return false
  return true
}

/**
 * Flatten the providers list into a sorted array of `provider/model-id`
 * strings — one per (provider, model) pair. Stable across renders so the
 * popup's highlighted index doesn't jump unless the underlying config
 * actually changed.
 */
export function buildModelOptions(providers: Provider[]): string[] {
  const out: string[] = []
  for (const p of providers) {
    for (const m of p.models) {
      out.push(`${p.name}/${m.id}`)
    }
  }
  return out
}

/**
 * Filter completions by the argument portion of `text`. Empty query returns
 * all options; non-empty matches substring (case-insensitive) against the
 * full `provider/model-id` string — so typing either the provider portion
 * or the model id narrows the list.
 */
export function filterModelOptions(allOptions: string[], text: string): string[] {
  if (!isModelArgumentContext(text)) return []
  const arg = text.slice(MODEL_COMMAND_PREFIX.length).trim().toLowerCase()
  if (!arg) return [...allOptions]
  return allOptions.filter(opt => opt.toLowerCase().includes(arg))
}

/**
 * Replace the argument portion of `text` with `choice`. Preserves the
 * command literal. Trims trailing whitespace from the replacement so
 * pressing Enter immediately after accepting a suggestion sends the
 * command cleanly — no "/model openrouter/gpt-4.1   " artifacts.
 */
export function applyModelOption(text: string, choice: string): string {
  if (!text.toLowerCase().startsWith(MODEL_COMMAND_PREFIX)) return text
  return MODEL_COMMAND_PREFIX + choice
}

/** Adapts the helpers above to the shared completer. Reads `providers` per call, so config changes land without re-registering the source. */
export function modelCompletionSource(providers: Ref<Provider[]>): CompletionSource {
  return {
    id: 'model',
    ariaLabel: 'Model completion options',
    options: (text: string): CompletionOption[] =>
      filterModelOptions(buildModelOptions(providers.value), text).map(value => ({ value })),
    apply: (text: string, choice: CompletionOption) => applyModelOption(text, choice.value),
  }
}
