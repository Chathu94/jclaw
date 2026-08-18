import type { Ref } from 'vue'
import type { CompletionOption, CompletionSource } from '~/composables/useComposerCompleter'
import type { SlashCommand } from '~/types/api'

/**
 * Completion source for the `/` command menu (JCLAW-1071) — the web
 * composer's equivalent of the native dropdown Telegram builds from
 * `setMyCommands`. Options come from GET /api/slash-commands, which is
 * derived from the backend `Commands.Command` enum, so the menu can't
 * advertise a command `Commands.parse` doesn't recognize.
 */

/**
 * True while the composer holds a bare command token: a leading slash and no
 * whitespace yet. Typing a space ends command selection and hands off to the
 * argument sources (`/model NAME`), so the two never compete for one keystroke.
 */
export function isCommandContext(text: string): boolean {
  return /^\/\S*$/.test(text)
}

/** Commands whose literal starts with `text`, case-insensitively. A bare "/" matches all. */
export function filterSlashCommands(commands: SlashCommand[], text: string): SlashCommand[] {
  if (!isCommandContext(text)) return []
  const typed = text.toLowerCase()
  return commands.filter(c => c.literal.toLowerCase().startsWith(typed))
}

export function slashCommandSource(commands: Ref<SlashCommand[]>): CompletionSource {
  return {
    id: 'slash-command',
    ariaLabel: 'Slash command options',
    options: (text: string): CompletionOption[] =>
      filterSlashCommands(commands.value, text).map(c => ({
        value: c.literal,
        label: c.literal,
        detail: c.description,
      })),
    // Trailing space so an argument-taking command (/model, /compact) is
    // immediately in argument context; Enter still has to be pressed to send.
    apply: (_text: string, choice: CompletionOption) => `${choice.value} `,
  }
}
