/**
 * Backpressure queue between decoded TTS chunks and the playback worklet's ring
 * buffer (JCLAW-845).
 *
 * The sidecar synthesises far faster than real-time, so a long reply produces
 * audio much quicker than it plays out. The ring holds seconds; the reply can be
 * minutes. This holds the overflow on the heap and feeds the ring only as space
 * frees up, so no sample is ever dropped.
 *
 * Free space is seeded from the capacity the caller configured rather than
 * waiting to be told: making the first `level` message load-bearing gives the
 * pump a permanent-deadlock state (nothing pushed → no level reports → nothing
 * ever pushed). Reports from the worklet only ever *correct* the estimate.
 */
export class AudioPump {
  private pending: Float32Array[] = []
  private free: number
  /** Chunks handed to the decoder but not yet enqueued. Counted here because
   *  "is there audio still to come?" has to include work that has not reached
   *  the queue yet — a chunk mid-decode when the server signals turn_complete
   *  would otherwise let the turn end mid-sentence. */
  private inFlight = 0

  /**
   * @param capacity ring capacity in samples — the pump's own starting estimate
   * @param send     hands a chunk to the worklet; must transfer/copy as needed
   */
  constructor(private readonly capacity: number, private readonly send: (chunk: Float32Array) => void) {
    this.free = capacity
  }

  /** Queue decoded audio and push as much as currently fits. */
  enqueue(chunk: Float32Array): void {
    if (chunk.length > 0) this.pending.push(chunk)
    this.pump()
  }

  /** Adopt the worklet's authoritative free-space report and top the ring up. */
  setFree(free: number): void {
    this.free = Math.max(0, Math.min(free, this.capacity))
    this.pump()
  }

  /** A chunk has been handed to the decoder. */
  beginDecode(): void {
    this.inFlight++
  }

  /** A decode finished — whether it enqueued, failed, or was discarded. */
  endDecode(): void {
    this.inFlight = Math.max(0, this.inFlight - 1)
  }

  /**
   * Drop everything still queued — barge-in, or teardown.
   *
   * Deliberately leaves {@link inFlight} alone: those decodes really are still
   * running and will decrement themselves. Zeroing it here would report idle
   * while a chunk was still resolving, which is the state that ends a turn early.
   */
  reset(): void {
    this.pending = []
    this.free = this.capacity
  }

  /** True when nothing is queued and nothing is still decoding. */
  get isIdle(): boolean {
    return this.pending.length === 0 && this.inFlight === 0
  }

  /** True when nothing is waiting for ring space, ignoring in-flight decodes. */
  get isEmpty(): boolean {
    return this.pending.length === 0
  }

  /** Samples still queued on the heap; for diagnostics and tests. */
  get pendingSamples(): number {
    return this.pending.reduce((n, c) => n + c.length, 0)
  }

  private pump(): void {
    while (this.pending.length > 0 && this.free > 0) {
      const head = this.pending[0]!
      if (head.length <= this.free) {
        this.pending.shift()
        this.free -= head.length
        this.send(head)
      }
      else {
        // Split across the boundary rather than holding the whole chunk back,
        // so a chunk larger than the remaining space cannot stall playback.
        const fit = head.slice(0, this.free)
        this.pending[0] = head.slice(this.free)
        this.free = 0
        this.send(fit)
      }
    }
  }
}
