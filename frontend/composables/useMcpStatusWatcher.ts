import { onBeforeUnmount, onMounted, type Ref } from 'vue'
import type { McpServer } from '~/types/api'

/** Cadence while a row is mid-handshake — fast enough to read as live. */
const FAST_POLL_MS = 600
/**
 * Idle heartbeat. Connections change without the operator touching anything:
 * the backend watchdog tears down and reconnects a server whose transport
 * dies, so a burst armed only at mount would miss every transition that
 * starts while the page sits open.
 */
const SLOW_POLL_MS = 10_000

export interface UseMcpStatusWatcher {
  /** Collapse the current wait so a just-mutated row updates at once. */
  kick: () => void
}

/**
 * Keep MCP connection status current for as long as the caller is mounted,
 * polling fast while any server is CONNECTING and idling otherwise.
 *
 * Deliberately unbounded rather than capped at an attempt count: a
 * docker-backed STDIO server takes ~90s to spawn, handshake and sync its
 * allowlist, so any ceiling short enough to look tidy gives up while the
 * slowest server is still legitimately connecting. The mount is the bound.
 */
export function useMcpStatusWatcher(
  servers: Ref<McpServer[] | null | undefined>,
  refresh: () => Promise<unknown>,
): UseMcpStatusWatcher {
  let watching = false
  /** Resolver for the in-flight sleep, so {@link kick} can end it early. */
  let wake: (() => void) | null = null

  function sleep(ms: number) {
    return new Promise<void>((resolve) => {
      const timer = setTimeout(done, ms)
      wake = done
      function done() {
        clearTimeout(timer)
        wake = null
        resolve()
      }
    })
  }

  function kick() {
    wake?.()
  }

  async function loop() {
    while (watching) {
      const connecting = servers.value?.some(row => row.status === 'CONNECTING') ?? false
      await sleep(connecting ? FAST_POLL_MS : SLOW_POLL_MS)
      if (!watching) return
      // A failed refresh must not end the watch. This loop is the page's only
      // source of updates, so letting one network blip escape would freeze
      // every badge until a manual reload — the exact failure this composable
      // exists to remove.
      try {
        await refresh()
      }
      catch { /* transient; the next tick retries */ }
    }
  }

  onMounted(() => {
    watching = true
    loop()
  })

  onBeforeUnmount(() => {
    watching = false
    kick()
  })

  return { kick }
}
