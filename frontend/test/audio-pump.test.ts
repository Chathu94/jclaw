import { describe, it, expect } from 'vitest'
import { AudioPump } from '~/utils/audio-pump'

function harness(capacity: number) {
  const sent: number[] = []
  const pump = new AudioPump(capacity, c => sent.push(c.length))
  return { pump, sent, total: () => sent.reduce((a, b) => a + b, 0) }
}

const chunk = (n: number) => new Float32Array(n)

describe('AudioPump', () => {
  it('pushes immediately without waiting to be told the free space', () => {
    // The regression that silenced voice mode outright: free space started at 0
    // and only a `level` message could raise it, but no level message can arrive
    // until something is pushed. Seeding from capacity breaks the cycle.
    const { pump, sent } = harness(1000)
    pump.enqueue(chunk(400))
    expect(sent).toEqual([400])
  })

  it('stops at capacity and holds the remainder', () => {
    const { pump, sent, total } = harness(1000)
    pump.enqueue(chunk(600))
    pump.enqueue(chunk(600))
    // 1000 fits; the 200-sample tail waits on the heap rather than overrunning.
    expect(total()).toBe(1000)
    expect(sent).toEqual([600, 400])
    expect(pump.pendingSamples).toBe(200)
    expect(pump.isEmpty).toBe(false)
  })

  it('splits a chunk across the boundary rather than stalling on it', () => {
    const { pump, sent } = harness(100)
    pump.enqueue(chunk(250))
    expect(sent).toEqual([100])
    pump.setFree(100)
    expect(sent).toEqual([100, 100])
    pump.setFree(100)
    expect(sent).toEqual([100, 100, 50])
    expect(pump.isEmpty).toBe(true)
  })

  it('resumes from the queue when the worklet reports space', () => {
    const { pump, total } = harness(500)
    pump.enqueue(chunk(2000))
    expect(total()).toBe(500)
    pump.setFree(500)
    expect(total()).toBe(1000)
    pump.setFree(500)
    pump.setFree(500)
    expect(total()).toBe(2000)
    expect(pump.isEmpty).toBe(true)
  })

  it('delivers every sample of a reply far larger than the ring', () => {
    // 30x the ring, drained in ring-sized steps — nothing may be lost.
    const { pump, total } = harness(1000)
    for (let i = 0; i < 10; i++) pump.enqueue(chunk(3000))
    let guard = 0
    while (!pump.isEmpty && guard++ < 1000) pump.setFree(1000)
    expect(total()).toBe(30000)
  })

  it('preserves order across queueing and splitting', () => {
    const seen: number[] = []
    const pump = new AudioPump(4, (c) => {
      seen.push(...Array.from(c))
    })
    pump.enqueue(Float32Array.from([1, 2, 3, 4, 5, 6]))
    pump.setFree(4)
    expect(seen).toEqual([1, 2, 3, 4, 5, 6])
  })

  it('clamps a bogus free report to capacity', () => {
    const { pump, total } = harness(100)
    pump.enqueue(chunk(500))
    pump.setFree(10_000) // never trust a report wider than the ring
    expect(total()).toBeLessThanOrEqual(200)
  })

  it('treats a negative free report as zero', () => {
    const { pump, total } = harness(100)
    pump.enqueue(chunk(500))
    expect(total()).toBe(100)
    pump.setFree(-5)
    expect(total()).toBe(100)
  })

  it('reset drops queued audio and restores full free space', () => {
    const { pump, sent } = harness(100)
    pump.enqueue(chunk(500))
    expect(pump.isEmpty).toBe(false)
    pump.reset()
    expect(pump.isEmpty).toBe(true)
    expect(pump.pendingSamples).toBe(0)
    // Post-reset the pump behaves like a fresh one — full capacity available.
    sent.length = 0
    pump.enqueue(chunk(100))
    expect(sent).toEqual([100])
  })

  it('ignores empty chunks', () => {
    const { pump, sent } = harness(100)
    pump.enqueue(chunk(0))
    expect(sent).toEqual([])
    expect(pump.isEmpty).toBe(true)
  })
})
