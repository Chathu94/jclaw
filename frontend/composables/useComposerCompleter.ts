import { computed, ref, type Ref, type ComputedRef } from 'vue'

/**
 * Source-agnostic typeahead for the chat composer (JCLAW-1071). One popup, N
 * sources: the `/` command menu, the `/model NAME` argument completer, and
 * whatever comes later. Pure Vue — no DOM — so it's testable without mounting.
 *
 * Generalized from useModelAutocomplete (JCLAW-114), which owned both the state
 * machine and the /model matching rules. Keeping one engine matters because
 * `useChatComposer` decides what Enter means by asking whether a popup is open:
 * with a popup per source that question has no single answer.
 */

/** One row in the completion popup. */
export interface CompletionOption {
  /** Identity within a source, and what {@link CompletionSource.apply} receives. */
  value: string
  /** Primary row text. Falls back to {@link CompletionOption.value}. */
  label?: string
  /** Secondary row text — a command's description, a prompt's category. */
  detail?: string
}

export interface CompletionSource {
  /** Identifies the source in tests and lets the popup style its rows. */
  id: string
  /** Listbox aria-label while this source is the active one. */
  ariaLabel: string
  /** Completions for `text`; an empty array means this source doesn't apply. */
  options: (text: string) => CompletionOption[]
  /** The composer text after accepting `choice`. */
  apply: (text: string, choice: CompletionOption) => string
}

/**
 * Next highlighted index after ArrowDown / ArrowUp, with wrap. Returns 0 for an
 * empty list — callers check length before rendering, but the wrap stays defined.
 */
export function nextAutocompleteIndex(
  current: number,
  total: number,
  direction: 'up' | 'down',
): number {
  if (total <= 0) return 0
  if (direction === 'down') return (current + 1) % total
  return (current - 1 + total) % total
}

export interface UseComposerCompleter {
  readonly open: Ref<boolean>
  readonly options: Ref<CompletionOption[]>
  readonly highlightedIndex: Ref<number>
  readonly highlighted: ComputedRef<CompletionOption | null>
  /** aria-label for the listbox, from whichever source is active. */
  readonly ariaLabel: ComputedRef<string>
  /** Id of the active source, or null when closed. */
  readonly activeSourceId: ComputedRef<string | null>
  /** Recompute from the textarea's current value. */
  update: (text: string) => void
  close: () => void
  moveHighlight: (direction: 'up' | 'down') => void
  /** Accept the highlighted option; returns the new textarea value, or null. */
  accept: (currentText: string) => string | null
}

/**
 * @param sources tried in order; the first yielding options wins. Sources close
 *                over their own reactive data, so the array itself is static.
 */
export function useComposerCompleter(sources: CompletionSource[]): UseComposerCompleter {
  const open = ref(false)
  const options = ref<CompletionOption[]>([])
  const highlightedIndex = ref(0)
  const activeSource = ref<CompletionSource | null>(null)

  const highlighted = computed<CompletionOption | null>(() => {
    if (!open.value) return null
    return options.value[highlightedIndex.value] ?? null
  })

  const ariaLabel = computed(() => activeSource.value?.ariaLabel ?? 'Completion options')
  const activeSourceId = computed(() => (open.value ? activeSource.value?.id ?? null : null))

  function update(text: string) {
    for (const source of sources) {
      const found = source.options(text)
      if (found.length === 0) continue
      options.value = found
      activeSource.value = source
      open.value = true
      // Clamp so a filter that shrank the list doesn't leave the index past the end.
      if (highlightedIndex.value >= found.length) highlightedIndex.value = 0
      return
    }
    close()
  }

  function close() {
    open.value = false
    options.value = []
    highlightedIndex.value = 0
    activeSource.value = null
  }

  function moveHighlight(direction: 'up' | 'down') {
    if (!open.value || options.value.length === 0) return
    highlightedIndex.value = nextAutocompleteIndex(
      highlightedIndex.value,
      options.value.length,
      direction,
    )
  }

  function accept(currentText: string): string | null {
    if (!open.value) return null
    const source = activeSource.value
    const choice = options.value[highlightedIndex.value]
    if (!source || !choice) return null
    close()
    return source.apply(currentText, choice)
  }

  return {
    open,
    options,
    highlightedIndex,
    highlighted,
    ariaLabel,
    activeSourceId,
    update,
    close,
    moveHighlight,
    accept,
  }
}
