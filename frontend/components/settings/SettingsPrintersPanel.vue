<script setup lang="ts">
// Printers settings panel (JCLAW-911). Browse the network, pick a default
// printer, and set the job options that default carries.
//
// Discovery is not run on mount. An mDNS browse takes seconds and blocks nothing
// useful — opening Settings to change the timezone should not pay for it — so the
// scan is an explicit action. The saved default loads immediately, because that
// is the state the operator came here to see.

interface PrinterEntry {
  name: string
  host: string
  port: number
  protocol: string
  formats: string | null
  isDefault: boolean
}

interface PrinterDefaults {
  name: string | null
  host: string | null
  port: number
  protocol: string | null
  sides: string | null
  color: string | null
  media: string | null
}

interface PrinterOptions {
  sides: string[]
  color: string[]
  protocols: string[]
}

const { mutate } = useApiMutation()

const { data: saved, refresh: refreshSaved } = useLazyFetch<PrinterDefaults>('/api/printers/default')
const { data: options } = useLazyFetch<PrinterOptions>('/api/printers/options')

const found = ref<PrinterEntry[] | null>(null)
const scanning = ref(false)
const savingState = ref(false)
const notice = ref<string | null>(null)
const problem = ref<string | null>(null)

// Draft job options, seeded from whatever is saved. '' means "printer's default".
const sides = ref('')
const color = ref('')
const media = ref('')

// Manual entry. Not a convenience: mDNS is link-local, so it is blocked on most
// VPNs and in any container without a multicast route — the exact networks where
// an operator is most likely to know the address and least likely to discover it.
// Without this the panel is unusable there, which UAT caught the hard way.
const manualHost = ref('')
const manualPort = ref('')
const manualProtocol = ref('')

watch(saved, (s) => {
  sides.value = s?.sides ?? ''
  color.value = s?.color ?? ''
  media.value = s?.media ?? ''
}, { immediate: true })

const hasDefault = computed(() => !!saved.value?.host)

/** "Office LaserJet — 10.0.0.5:631 (IPP)", collapsing the name when it IS the host. */
const defaultLabel = computed(() => {
  const s = saved.value
  if (!s?.host) return ''
  const address = s.port ? `${s.host}:${s.port}` : s.host
  const suffix = s.protocol ? ` (${s.protocol})` : ''
  return s.name && s.name !== s.host
    ? `${s.name} — ${address}${suffix}`
    : `${address}${suffix}`
})

async function scan() {
  scanning.value = true
  problem.value = null
  notice.value = null
  const list = await mutate<PrinterEntry[]>('/api/printers', { method: 'GET' })
  scanning.value = false
  if (list === null) {
    problem.value = 'Discovery failed. See the application log for details.'
    return
  }
  found.value = list
  if (list.length === 0) {
    notice.value = 'No printers answered. mDNS is link-local, so this is also what '
      + 'a blocked multicast route looks like — on a VPN or in a container it will '
      + 'always be empty. Enter the address by hand below if you know it.'
  }
}

/** Save a discovered printer as the default, carrying the current job options. */
async function chooseDefault(p: PrinterEntry) {
  await persist({
    name: p.name, host: p.host, port: p.port, protocol: p.protocol,
    sides: sides.value || null, color: color.value || null, media: media.value || null,
  })
}

/** Save a hand-entered address as the default. */
async function useManual() {
  const host = manualHost.value.trim()
  if (!host) return
  const port = Number.parseInt(manualPort.value, 10)
  await persist({
    name: host, host,
    port: Number.isFinite(port) && port > 0 ? port : 0,
    protocol: manualProtocol.value || null,
    sides: sides.value || null, color: color.value || null, media: media.value || null,
  })
  manualHost.value = ''
  manualPort.value = ''
}

/** Save job-option changes against the printer already chosen. */
async function saveOptions() {
  if (!saved.value?.host) return
  await persist({
    name: saved.value.name, host: saved.value.host, port: saved.value.port,
    protocol: saved.value.protocol,
    sides: sides.value || null, color: color.value || null, media: media.value || null,
  })
}

async function clearDefault() {
  await persist({ host: null })
}

async function persist(body: Record<string, unknown>) {
  savingState.value = true
  problem.value = null
  notice.value = null
  const res = await mutate('/api/printers/default', { method: 'PUT', body })
  savingState.value = false
  if (res === null) {
    problem.value = 'Could not save. The value may be invalid — check the job options.'
    return
  }
  await refreshSaved()
  // Re-scan silently so the "Default" badge moves without another click.
  if (found.value) await scan()
  notice.value = body.host ? 'Default printer saved.' : 'Default printer cleared.'
}
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Printers
    </h2>
    <p class="text-xs text-fg-muted">
      Choose the printer the <code>printer</code> tool uses when an agent doesn't name one, and the
      job options it should carry. Everything here is optional — without a default, agents must pass
      a printer or host on every call.
    </p>

    <!-- Current default -->
    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-2.5 flex items-center justify-between gap-4">
        <div class="min-w-0">
          <span class="text-sm font-medium text-fg-strong">Default printer</span>
          <div class="text-xs text-fg-muted mt-0.5">
            <!-- One string, built in script: interpolating the parts inline put a
                 stray space before ":9100" and printed the name twice when it is
                 just the host (which manual entry makes it). -->
            <template v-if="hasDefault">
              {{ defaultLabel }}
            </template>
            <template v-else>
              None set. Agents must name a printer on every call.
            </template>
          </div>
        </div>
        <button
          v-if="hasDefault"
          :disabled="savingState"
          class="shrink-0 px-3 py-1.5 text-xs font-medium text-fg-muted hover:text-fg-strong
                 border border-border rounded-full transition-colors disabled:opacity-40"
          @click="clearDefault"
        >
          Clear
        </button>
      </div>
    </div>

    <!-- Discovery -->
    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-2.5 flex items-center justify-between gap-4">
        <div class="min-w-0">
          <span class="text-sm font-medium text-fg-strong">Find printers</span>
          <div class="text-xs text-fg-muted mt-0.5">
            Browses the local network over mDNS/Bonjour. Takes a couple of seconds.
          </div>
        </div>
        <button
          :disabled="scanning"
          class="shrink-0 px-3 py-1.5 text-xs font-medium text-white bg-accent
                 hover:opacity-90 disabled:opacity-40 rounded-full transition-opacity"
          @click="scan"
        >
          {{ scanning ? 'Scanning…' : 'Scan' }}
        </button>
      </div>

      <ul
        v-if="found && found.length"
        class="border-t border-border divide-y divide-border"
      >
        <li
          v-for="p in found"
          :key="`${p.host}:${p.port}`"
          class="px-4 py-2.5 flex items-center justify-between gap-4"
        >
          <div class="min-w-0">
            <span class="text-sm text-fg-strong">{{ p.name }}</span>
            <span
              v-if="p.isDefault"
              class="ml-2 px-1.5 py-0.5 text-[10px] uppercase tracking-wide
                     bg-accent/15 text-accent rounded"
            >Default</span>
            <div class="text-xs text-fg-muted mt-0.5">
              {{ p.host }}:{{ p.port }} ({{ p.protocol }})<template v-if="p.formats">
                — {{ p.formats }}
              </template>
            </div>
          </div>
          <button
            :disabled="savingState || p.isDefault"
            class="shrink-0 px-3 py-1.5 text-xs font-medium border border-border
                   rounded-full transition-colors disabled:opacity-40
                   hover:text-fg-strong text-fg-muted"
            @click="chooseDefault(p)"
          >
            {{ p.isDefault ? 'Current' : 'Set default' }}
          </button>
        </li>
      </ul>

      <!-- Manual entry. The only route to a default on a network that blocks
           mDNS, which is most VPNs and any container without a multicast route. -->
      <div class="border-t border-border px-4 py-3">
        <div class="text-xs text-fg-muted mb-2">
          Or enter an address directly — needed when the network blocks mDNS.
        </div>
        <div class="grid gap-2 sm:grid-cols-[2fr_1fr_1fr_auto] sm:items-end">
          <label
            for="printer-manual-host"
            class="block"
          >
            <span class="block text-[11px] text-fg-muted mb-1">Host or IP</span>
            <input
              id="printer-manual-host"
              v-model="manualHost"
              type="text"
              placeholder="10.0.0.5"
              class="w-full px-2 py-1.5 text-xs bg-surface border border-border"
            >
          </label>
          <label
            for="printer-manual-port"
            class="block"
          >
            <span class="block text-[11px] text-fg-muted mb-1">Port</span>
            <input
              id="printer-manual-port"
              v-model="manualPort"
              type="number"
              placeholder="auto"
              class="w-full px-2 py-1.5 text-xs bg-surface border border-border"
            >
          </label>
          <label
            for="printer-manual-protocol"
            class="block"
          >
            <span class="block text-[11px] text-fg-muted mb-1">Protocol</span>
            <select
              id="printer-manual-protocol"
              v-model="manualProtocol"
              class="w-full px-2 py-1.5 text-xs bg-surface border border-border"
            >
              <option value="">Auto</option>
              <option
                v-for="v in options?.protocols ?? []"
                :key="v"
                :value="v"
              >{{ v }}</option>
            </select>
          </label>
          <button
            :disabled="savingState || !manualHost.trim()"
            class="px-3 py-1.5 text-xs font-medium border border-border rounded-full
                   transition-colors disabled:opacity-40 hover:text-fg-strong text-fg-muted"
            @click="useManual"
          >
            Set default
          </button>
        </div>
      </div>
    </div>

    <!-- Job options -->
    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-2.5">
        <span class="text-sm font-medium text-fg-strong">Job options</span>
        <div class="text-xs text-fg-muted mt-0.5">
          Applied to jobs that don't specify their own. Leave any of them on
          <em>Printer default</em> to let the printer decide. These travel over IPP only — a job
          that falls back to a raw socket or LPD prints with the printer's own settings, and the
          tool says so when that happens.
        </div>
      </div>
      <div class="px-4 pb-3 grid gap-3 sm:grid-cols-3">
        <label
          for="printer-default-sides"
          class="block"
        >
          <span class="block text-[11px] text-fg-muted mb-1">Sides</span>
          <select
            id="printer-default-sides"
            v-model="sides"
            class="w-full px-2 py-1.5 text-xs bg-surface border border-border"
          >
            <option value="">Printer default</option>
            <option
              v-for="v in options?.sides ?? []"
              :key="v"
              :value="v"
            >{{ v }}</option>
          </select>
        </label>
        <label
          for="printer-default-color"
          class="block"
        >
          <span class="block text-[11px] text-fg-muted mb-1">Colour</span>
          <select
            id="printer-default-color"
            v-model="color"
            class="w-full px-2 py-1.5 text-xs bg-surface border border-border"
          >
            <option value="">Printer default</option>
            <option
              v-for="v in options?.color ?? []"
              :key="v"
              :value="v"
            >{{ v }}</option>
          </select>
        </label>
        <label
          for="printer-default-media"
          class="block"
        >
          <span class="block text-[11px] text-fg-muted mb-1">Paper / tray</span>
          <input
            id="printer-default-media"
            v-model="media"
            type="text"
            placeholder="iso_a4_210x297mm"
            class="w-full px-2 py-1.5 text-xs bg-surface border border-border"
          >
        </label>
      </div>
      <div class="px-4 pb-3 flex justify-end">
        <button
          :disabled="savingState || !hasDefault"
          class="px-3 py-1.5 text-xs font-medium text-white bg-accent hover:opacity-90
                 disabled:opacity-40 rounded-full transition-opacity"
          @click="saveOptions"
        >
          {{ savingState ? 'Saving…' : 'Save options' }}
        </button>
      </div>
    </div>

    <p
      v-if="notice || problem"
      class="text-xs"
      role="status"
      aria-live="polite"
    >
      <span
        v-if="problem"
        class="text-red-600 dark:text-red-400"
      >{{ problem }}</span>
      <span
        v-else
        class="text-fg-muted"
      >{{ notice }}</span>
    </p>
  </div>
</template>
