<script setup lang="ts">
/**
 * JCLAW-1062/1068: what a task fire is permitted to do — its tool allow-list and the
 * origin that decides fire-time trust.
 *
 * Both are read-only here. The allow-list is set through the task API; origin is
 * recorded at creation and deliberately cannot be raised (JCLAW-1021).
 */
const props = defineProps<{
  enabledToolNames?: string | null
  originChannel?: string | null
}>()

/**
 * Mirror of services.TaskToolPolicy.parse. It has to agree exactly: showing pills for
 * a list the backend reads as unrestricted would assert a fence that is not there.
 * Accepts a JSON array, a comma-separated list, or a bare name; anything carrying JSON
 * punctuation that fails to parse is malformed and reads as unrestricted, as it does
 * server-side.
 */
const TOOL_NAME = /^[A-Za-z0-9_.-]+$/

const tools = computed<string[] | null>(() => {
  const raw = props.enabledToolNames
  if (!raw || !raw.trim()) return null
  try {
    const parsed = JSON.parse(raw)
    if (Array.isArray(parsed) && parsed.every(n => typeof n === 'string')) {
      const names = parsed.map(n => n.trim()).filter(Boolean)
      return names.length ? names : null
    }
    return null
  }
  catch {
    if (/[[\]{}"]/.test(raw)) return null
    const names = raw.split(',').map(n => n.trim()).filter(Boolean)
    return names.length && names.every(n => TOOL_NAME.test(n)) ? names : null
  }
})

/** web is the only operator origin; absent reads as UNKNOWN and fails closed. */
const origin = computed(() => {
  const o = props.originChannel
  if (!o) {
    return {
      label: 'unrecorded',
      tone: 'border-amber-500/40 text-amber-700 dark:text-amber-400',
      title: 'No recorded origin — classifies as UNKNOWN, so dangerous tools fail closed at fire time.',
    }
  }
  if (o === 'web') {
    return {
      label: 'web',
      tone: 'border-emerald-500/40 text-emerald-700 dark:text-emerald-400',
      title: 'Operator origin — dangerous tools resolve by the Tool Approvals policy instead of failing closed.',
    }
  }
  return {
    label: o,
    tone: 'border-border text-fg-muted',
    title: `Inbound channel origin — untrusted, so dangerous tools fail closed unless the policy is "ask".`,
  }
})
</script>

<template>
  <section data-testid="task-permissions">
    <div class="text-[10px] uppercase tracking-wider font-medium text-fg-muted mb-1.5">
      Permissions
    </div>

    <div class="flex flex-wrap items-center gap-1.5 mb-2">
      <span class="text-[11px] text-fg-muted">Origin</span>
      <span
        class="text-[11px] px-1.5 py-0.5 border font-mono"
        :class="origin.tone"
        :title="origin.title"
        data-testid="task-origin-pill"
      >{{ origin.label }}</span>
    </div>

    <div class="flex flex-wrap items-center gap-1.5">
      <span class="text-[11px] text-fg-muted">Tools</span>
      <span
        v-if="!tools"
        class="text-[11px] px-1.5 py-0.5 border border-border text-fg-muted"
        title="No allow-list — this task may use every tool its agent has."
        data-testid="task-tools-unrestricted"
      >all the agent&rsquo;s tools</span>
      <span
        v-for="tool in tools"
        v-else
        :key="tool"
        class="text-[11px] px-1.5 py-0.5 border border-border text-fg-strong font-mono"
        data-testid="task-tool-pill"
      >{{ tool }}</span>
    </div>
  </section>
</template>
