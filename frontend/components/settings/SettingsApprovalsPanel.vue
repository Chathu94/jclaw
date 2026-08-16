<script setup lang="ts">
// Tool Approvals (JCLAW-1022). One instance-wide control: what a dangerous action does
// when it has no way to ask the operator. Deliberately its own section rather than part
// of Shell Execution — DangerousActionGate governs the exec tool, but the same policy
// also decides whether SubagentAcpRunner may launch the coding harness (JCLAW-669), and
// a reader of a "Shell Execution" panel would not expect to be changing that.
const { configData, saving, refresh } = useSettingsConfig()

const POLICY_KEY = 'tool.approval.offChannelPolicy'

const POLICIES = [
  {
    value: 'allow',
    label: 'Allow',
    detail: 'Run it. Applies to your own turns only — an origin that cannot ask you still fails closed.',
  },
  {
    value: 'ask',
    label: 'Ask',
    detail: 'Send a confirmation to the agent’s bound Telegram DM. Fails closed if there is none.',
  },
  {
    value: 'deny',
    label: 'Deny',
    detail: 'Refuse it, on every origin.',
  },
] as const

// 'allow' matches DangerousActionGate's DEFAULT_OFF_CHANNEL_POLICY, so an instance that
// never set the key reads the same here as it behaves.
const policy = computed(() =>
  configData.value?.entries?.find(e => e.key === POLICY_KEY)?.value?.toLowerCase() ?? 'allow')

const selected = ref(policy.value)
watch(policy, (v) => {
  selected.value = v
})

const error = ref<string | null>(null)

async function save(value: string) {
  saving.value = true
  error.value = null
  try {
    await $fetch('/api/config', { method: 'POST', body: { key: POLICY_KEY, value } })
    refresh()
  }
  catch (e) {
    // A ceiling in application.conf refuses a loosening value with a 403 naming it
    // (JCLAW-1022). Surface that instead of silently reverting the select.
    const detail = (e as { data?: { error?: string } })?.data?.error
    error.value = detail ?? 'Could not save the approval policy.'
    selected.value = policy.value
  }
  finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Tool Approvals
    </h2>
    <p class="text-xs text-fg-muted">
      What a dangerous action does when it cannot reach you for approval — the shell
      tool, and launching a coding-harness subagent. This decides your own turns: when
      someone else messages the agent on Telegram or Slack and that agent has a working
      binding, you are asked there regardless of this setting.
    </p>

    <div class="space-y-2">
      <label
        for="approval-off-channel-policy"
        class="block"
      >
        <span class="block text-xs font-medium text-fg-strong mb-1">No approval surface</span>
        <select
          id="approval-off-channel-policy"
          v-model="selected"
          class="w-full px-2 py-1.5 text-sm bg-surface border border-input text-fg-strong"
          data-testid="approval-off-channel-policy"
          :disabled="saving"
          @change="save(selected)"
        >
          <option
            v-for="p in POLICIES"
            :key="p.value"
            :value="p.value"
          >
            {{ p.label }} — {{ p.detail }}
          </option>
        </select>
      </label>

      <p
        v-if="error"
        class="text-xs text-danger"
        data-testid="approval-policy-error"
      >
        {{ error }}
      </p>

      <p class="text-xs text-fg-muted">
        An agent that you have previously granted &ldquo;always allow&rdquo; for a tool
        runs it without a prompt on any origin, independently of this setting.
      </p>
    </div>
  </div>
</template>
