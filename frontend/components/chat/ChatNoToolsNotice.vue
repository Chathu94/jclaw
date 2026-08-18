<script setup lang="ts">
/**
 * Non-blocking notice that the active model cannot call tools (JCLAW-1075).
 *
 * Without it the failure mode is a silent one: the agent still answers, just
 * never uses a tool or skill, which reads as the model being bad rather than
 * the configuration being wrong. The wording therefore names the cause and
 * what is unavailable, and avoids implying anything is broken — a chat-only
 * model is a legitimate choice, so this is information, not a warning.
 *
 * Renders only when there is something to lose: a model declared tool-incapable
 * AND an agent that actually has tools or skills configured.
 */
const props = defineProps<{
  /** Display name of the active model. */
  modelLabel: string
  toolCount: number
  skillCount: number
}>()

const what = computed(() => {
  const parts: string[] = []
  if (props.toolCount > 0) parts.push(`${props.toolCount} tool${props.toolCount === 1 ? '' : 's'}`)
  if (props.skillCount > 0) parts.push(`${props.skillCount} skill${props.skillCount === 1 ? '' : 's'}`)
  return parts.join(' and ')
})
</script>

<template>
  <div
    data-testid="no-tools-notice"
    class="mx-auto w-full max-w-3xl px-4 pt-3"
  >
    <div
      class="flex items-start gap-2 rounded border border-orange-200 bg-orange-50 px-3 py-2 text-xs
             text-orange-800 dark:border-orange-400/20 dark:bg-orange-400/10 dark:text-orange-300"
      role="status"
    >
      <span class="font-mono uppercase tracking-wide shrink-0">No tools</span>
      <span>
        <strong>{{ modelLabel }}</strong> can't call tools, so this agent's
        {{ what }} are unavailable in this conversation. Pick a tool-capable
        model to use them.
      </span>
    </div>
  </div>
</template>
