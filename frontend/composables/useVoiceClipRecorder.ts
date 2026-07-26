import { ref, onScopeDispose } from 'vue'

/**
 * Mic capture for the Settings > Speech reference clip (JCLAW-868).
 *
 * Deliberately AudioWorklet + client-side WAV rather than MediaRecorder. The
 * MediaRecorder path in `useChatAttachments` negotiates `audio/webm` on
 * Chromium, and webm is not in `TtsReferenceVoice.ALLOWED_EXTENSIONS` — that
 * allow-list is narrow because the engines' audio loaders read those formats
 * without extra codecs, and libsndfile has no webm decoder. Encoding PCM to WAV
 * here removes the codec-negotiation matrix entirely and lands on the one format
 * every engine reads natively.
 *
 * Reuses the voice-mode capture worklet rather than adding a second capture
 * stack; it is already generic over frame size.
 */

const CAPTURE_WORKLET = '/worklets/voice-capture.js'

/** Samples per posted frame. Matches voice mode — ~85 ms at 48 kHz, which is
 *  also a fine granularity for the elapsed-time counter. */
const FRAME = 4096

/** Recording cap. Cloning wants a few seconds of clean speech, so this is a
 *  guard against a forgotten session rather than a useful length: 30 s is
 *  ~2.9 MB at 48 kHz, comfortably inside the server's 10 MB limit. */
const DEFAULT_MAX_SECONDS = 30

/**
 * Encode mono float samples as a 16-bit PCM WAV.
 *
 * `sampleRate` is written to the header as given rather than resampled — the
 * caller passes the AudioContext's actual rate, and the sidecar resamples on
 * load, so honouring whatever the platform produced is both correct and lossless.
 */
export function encodeWavMono16(samples: Float32Array, sampleRate: number): Blob {
  const buffer = new ArrayBuffer(44 + samples.length * 2)
  const view = new DataView(buffer)
  const ascii = (offset: number, text: string) => {
    for (let i = 0; i < text.length; i++) view.setUint8(offset + i, text.codePointAt(i)!)
  }

  ascii(0, 'RIFF')
  view.setUint32(4, 36 + samples.length * 2, true) // size of everything after this field
  ascii(8, 'WAVE')
  ascii(12, 'fmt ')
  view.setUint32(16, 16, true) // fmt chunk length
  view.setUint16(20, 1, true) // 1 = uncompressed PCM
  view.setUint16(22, 1, true) // mono
  view.setUint32(24, sampleRate, true)
  view.setUint32(28, sampleRate * 2, true) // byte rate = rate * blockAlign
  view.setUint16(32, 2, true) // block align = channels * bytes-per-sample
  view.setUint16(34, 16, true) // bits per sample
  ascii(36, 'data')
  view.setUint32(40, samples.length * 2, true)

  // Asymmetric scaling, matching useVoiceMode: int16 holds -32768..32767, so the
  // negative and positive full scales differ by one.
  let offset = 44
  for (let i = 0; i < samples.length; i++, offset += 2) {
    const s = Math.max(-1, Math.min(1, samples[i]!))
    view.setInt16(offset, s < 0 ? s * 0x8000 : s * 0x7FFF, true)
  }
  return new Blob([buffer], { type: 'audio/wav' })
}

export interface VoiceClipRecorderOptions {
  maxSeconds?: number
  /** Fired once when the cap is reached, so the caller can finish the recording
   *  the same way a manual Stop would. */
  onLimit?: () => void
}

export function useVoiceClipRecorder(options: VoiceClipRecorderOptions = {}) {
  const maxSeconds = options.maxSeconds ?? DEFAULT_MAX_SECONDS
  const recording = ref(false)
  const seconds = ref(0)

  // Plain bindings, not refs: reactivity on audio nodes buys nothing and these
  // change on a hot path.
  let stream: MediaStream | null = null
  let ctx: AudioContext | null = null
  let source: MediaStreamAudioSourceNode | null = null
  let node: AudioWorkletNode | null = null
  let chunks: Float32Array[] = []
  let total = 0
  let rate = 0
  let limitFired = false

  /** Tear down the graph and, critically, stop the mic tracks — otherwise the
   *  browser keeps showing a recording indicator and the mic stays hot. */
  function release() {
    if (node) {
      node.port.onmessage = null
      node.disconnect()
    }
    source?.disconnect()
    stream?.getTracks().forEach(t => t.stop())
    void ctx?.close()
    node = null
    source = null
    stream = null
    ctx = null
    recording.value = false
  }

  function onFrame(frame: Float32Array) {
    chunks.push(frame)
    total += frame.length
    seconds.value = Math.floor(total / rate)
    if (!limitFired && total >= maxSeconds * rate) {
      limitFired = true
      options.onLimit?.()
    }
  }

  async function start() {
    if (recording.value) return
    if (typeof navigator === 'undefined' || !navigator.mediaDevices?.getUserMedia) {
      throw new Error('microphone capture is not available in this browser')
    }
    chunks = []
    total = 0
    seconds.value = 0
    limitFired = false

    // Same constraints as voice mode: the browser's cleanup makes for a better
    // reference clip than a raw room recording would.
    stream = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    })
    try {
      // No forced sampleRate, unlike voice mode — the server VAD/STT there
      // assume 16 kHz, whereas cloning just wants the best the device offers.
      ctx = new AudioContext()
      await ctx.audioWorklet.addModule(CAPTURE_WORKLET)
      rate = ctx.sampleRate
      source = ctx.createMediaStreamSource(stream)
      node = new AudioWorkletNode(ctx, 'voice-capture-processor', {
        numberOfInputs: 1,
        numberOfOutputs: 1,
        processorOptions: { frame: FRAME },
      })
      node.port.onmessage = e => onFrame(e.data as Float32Array)
      source.connect(node)
      // The worklet writes no output, so this is silent; it exists to keep the
      // graph pulling, which is what the worklet's own comment describes.
      node.connect(ctx.destination)
      recording.value = true
    }
    catch (e) {
      release() // never leave the mic open on a half-built graph
      throw e
    }
  }

  /** Stop and return the recording, or null if nothing was captured. */
  function stop(): Blob | null {
    if (!recording.value) return null
    const merged = new Float32Array(total)
    let offset = 0
    for (const c of chunks) {
      merged.set(c, offset)
      offset += c.length
    }
    const capturedAt = rate
    release()
    chunks = []
    return merged.length ? encodeWavMono16(merged, capturedAt) : null
  }

  /** Abandon the recording, discarding audio. */
  function cancel() {
    release()
    chunks = []
    total = 0
    seconds.value = 0
  }

  // A settings panel can be navigated away from mid-recording; the mic must not
  // outlive the component that opened it.
  onScopeDispose(cancel)

  return { recording, seconds, maxSeconds, start, stop, cancel }
}
