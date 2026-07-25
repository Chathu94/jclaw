import { describe, it, expect } from 'vitest'
import { encodeWavMono16 } from '~/composables/useVoiceClipRecorder'

/**
 * JCLAW-868 — WAV encoding for the mic-recorded reference clip.
 *
 * The offsets are asserted individually rather than against a golden blob: a
 * canonical 44-byte PCM header is easy to write with a field shifted by two
 * bytes, and the failure is silent at the encoder (the file is still produced)
 * but fatal at the decoder that has to read it.
 */

async function bytesOf(blob: Blob) {
  return new DataView(await blob.arrayBuffer())
}

const ascii = (v: DataView, offset: number, length: number) =>
  Array.from({ length }, (_, i) => String.fromCharCode(v.getUint8(offset + i))).join('')

describe('encodeWavMono16', () => {
  it('writes a canonical 44-byte mono 16-bit PCM header', async () => {
    const samples = new Float32Array(1000)
    const v = await bytesOf(encodeWavMono16(samples, 48000))

    expect(ascii(v, 0, 4)).toBe('RIFF')
    expect(v.getUint32(4, true)).toBe(36 + 1000 * 2) // everything after this field
    expect(ascii(v, 8, 4)).toBe('WAVE')
    expect(ascii(v, 12, 4)).toBe('fmt ')
    expect(v.getUint32(16, true)).toBe(16) // fmt chunk length
    expect(v.getUint16(20, true)).toBe(1) // uncompressed PCM
    expect(v.getUint16(22, true)).toBe(1) // mono
    expect(v.getUint32(24, true)).toBe(48000) // sample rate
    expect(v.getUint32(28, true)).toBe(48000 * 2) // byte rate
    expect(v.getUint16(32, true)).toBe(2) // block align
    expect(v.getUint16(34, true)).toBe(16) // bits per sample
    expect(ascii(v, 36, 4)).toBe('data')
    expect(v.getUint32(40, true)).toBe(1000 * 2) // payload length
  })

  it('sizes the blob as header plus two bytes per sample', async () => {
    const blob = encodeWavMono16(new Float32Array(1234), 16000)
    expect(blob.size).toBe(44 + 1234 * 2)
    expect(blob.type).toBe('audio/wav')
  })

  it('records the rate it was handed rather than assuming one', async () => {
    // The AudioContext's actual rate varies by platform, so whatever it reports
    // has to survive into the header for the sidecar to resample correctly.
    for (const rate of [16000, 44100, 48000]) {
      const v = await bytesOf(encodeWavMono16(new Float32Array(8), rate))
      expect(v.getUint32(24, true)).toBe(rate)
      expect(v.getUint32(28, true)).toBe(rate * 2)
    }
  })

  it('converts float samples to little-endian int16 at full scale', async () => {
    const v = await bytesOf(encodeWavMono16(new Float32Array([0, 1, -1, 0.5]), 48000))
    expect(v.getInt16(44, true)).toBe(0)
    expect(v.getInt16(46, true)).toBe(32767) // +1 maps to positive full scale
    expect(v.getInt16(48, true)).toBe(-32768) // -1 maps to negative full scale
    // setInt16 truncates toward zero rather than rounding — a half-LSB error.
    expect(v.getInt16(50, true)).toBe(Math.trunc(0.5 * 0x7FFF))
  })

  it('clamps out-of-range samples instead of wrapping them', async () => {
    // Float32 from the worklet can exceed unit range; letting it through would
    // wrap around int16 and turn a loud passage into noise.
    const v = await bytesOf(encodeWavMono16(new Float32Array([4, -4]), 48000))
    expect(v.getInt16(44, true)).toBe(32767)
    expect(v.getInt16(46, true)).toBe(-32768)
  })

  it('produces a header-only file for an empty recording', async () => {
    const blob = encodeWavMono16(new Float32Array(0), 48000)
    expect(blob.size).toBe(44)
    const v = await bytesOf(blob)
    expect(v.getUint32(40, true)).toBe(0)
  })
})
