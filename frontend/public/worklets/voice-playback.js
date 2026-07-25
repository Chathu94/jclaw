/* global AudioWorkletProcessor, registerProcessor, sampleRate */
// Voice-mode TTS playback (JCLAW-796). A single-reader/single-writer ring
// buffer fed by the main thread. Replaces timeline-scheduled
// AudioBufferSourceNodes: on barge-in the main thread posts one `flush`
// message and playback stops within a single render quantum (~2.7 ms), instead
// of stopping and discarding N already-scheduled source nodes.
//
// Messages in:  { type: 'push', data: Float32Array }  — append decoded PCM
//               { type: 'flush' }                      — hard-stop, drop all audio
// Messages out: { type: 'drained' }                    — buffer emptied after playing
//               { type: 'level', free }                — free sample slots, for backpressure
//
// JCLAW-845: the ring holds seconds, but a reply can be minutes. The sidecar
// synthesises far faster than real-time, so a long answer used to overrun the
// ring and the writer dropped the OLDEST samples — audio the listener had not
// heard yet. That is why long replies came out with gaps throughout rather than
// truncated at the end. The main thread now applies backpressure from the
// `level` reports and only pushes what fits; `enqueue` additionally refuses to
// overwrite unplayed audio, so a backpressure bug degrades to a reported drop
// instead of silent corruption.
// Render quanta between free-space reports while draining. 16 quanta is ~43 ms
// at 128 frames / 48 kHz — frequent enough that the writer keeps the ring fed,
// rare enough not to flood the message port during a multi-minute reply.
const LEVEL_REPORT_QUANTA = 16

class VoicePlaybackProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super()
    const seconds = options?.processorOptions?.bufferSeconds || 10
    this.capacity = Math.max(1, Math.floor(sampleRate * seconds))
    this.ring = new Float32Array(this.capacity)
    this.read = 0
    this.write = 0
    this.filled = 0
    this.hadData = false
    this.sinceReport = 0
    this.port.onmessage = (e) => {
      const msg = e.data
      if (!msg) return
      if (msg.type === 'push' && msg.data) {
        this.enqueue(msg.data)
      }
      else if (msg.type === 'flush') {
        this.read = 0
        this.write = 0
        this.filled = 0
        this.hadData = false
        this.reportLevel()
      }
    }
    // Seed the writer's view of free space before the first chunk arrives.
    this.reportLevel()
  }

  reportLevel() {
    this.port.postMessage({ type: 'level', free: this.capacity - this.filled })
  }

  enqueue(data) {
    // Accept only what fits. Dropping the tail of an over-long push loses audio
    // not yet written; dropping the head (the old behaviour) loses audio already
    // queued for playback, which is strictly worse and silent.
    const n = Math.min(data.length, this.capacity - this.filled)
    for (let i = 0; i < n; i++) {
      this.ring[this.write] = data[i]
      this.write = (this.write + 1) % this.capacity
      this.filled++
    }
    if (n > 0) this.hadData = true
    if (n < data.length) {
      this.port.postMessage({ type: 'overflow', dropped: data.length - n })
    }
    this.reportLevel()
  }

  process(_inputs, outputs) {
    const out = outputs[0]
    const frames = out[0].length
    for (let i = 0; i < frames; i++) {
      let sample = 0
      if (this.filled > 0) {
        sample = this.ring[this.read]
        this.read = (this.read + 1) % this.capacity
        this.filled--
      }
      for (let c = 0; c < out.length; c++) {
        out[c][i] = sample
      }
    }
    // Announce the non-empty -> empty transition once, so the main thread can
    // end the turn only after the server has also signalled turn_complete.
    if (this.hadData && this.filled === 0) {
      this.hadData = false
      this.port.postMessage({ type: 'drained' })
    }
    // Report free space periodically while draining so the writer can top up.
    // Only while there is audio in flight — an idle buffer needs no reports.
    if (this.filled > 0 && ++this.sinceReport >= LEVEL_REPORT_QUANTA) {
      this.sinceReport = 0
      this.reportLevel()
    }
    return true
  }
}

registerProcessor('voice-playback-processor', VoicePlaybackProcessor)
