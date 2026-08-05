import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref } from 'vue'
import { useChatAttachments } from '~/composables/useChatAttachments'

// JCLAW-131: the frontend caps are UX only — the server re-applies them
// authoritatively on upload. What is under test here is that the refusal happens
// before bytes leave the browser and names the right limit, not that it is a
// security control.

type ConfigRef = Parameters<typeof useChatAttachments>[0]

function configRef(entries: { key: string, value: string }[] = []): ConfigRef {
  return ref({ entries }) as unknown as ConfigRef
}

function file(name: string, type: string, size: number): File {
  const f = new File(['x'], name, { type })
  // jsdom derives size from the blob parts; override it so a test can describe a
  // 30 MB upload without allocating 30 MB.
  Object.defineProperty(f, 'size', { value: size })
  return f
}

const MB = 1024 * 1024

describe('useChatAttachments', () => {
  beforeEach(() => {
    let n = 0
    globalThis.URL.createObjectURL = vi.fn(() => `blob:preview-${n++}`)
    globalThis.URL.revokeObjectURL = vi.fn()
  })

  describe('accepting files', () => {
    it('accepts an image, an audio file and a plain file under the caps', () => {
      const a = useChatAttachments(configRef())
      a.addAttachments([
        file('a.png', 'image/png', 1 * MB),
        file('b.mp3', 'audio/mpeg', 1 * MB),
        file('c.pdf', 'application/pdf', 1 * MB),
      ])

      expect(a.attachedFiles.value.map(f => f.name)).toEqual(['a.png', 'b.mp3', 'c.pdf'])
      expect(a.attachError.value).toBeNull()
    })

    it('only makes a preview URL for images', () => {
      const a = useChatAttachments(configRef())
      a.addAttachments([file('a.png', 'image/png', 1 * MB), file('c.pdf', 'application/pdf', 1 * MB)])

      expect(a.attachmentPreviews.value.size).toBe(1)
      expect(a.attachmentPreviews.value.get(a.attachedFiles.value[0]!)).toMatch(/^blob:/)
      expect(URL.createObjectURL).toHaveBeenCalledTimes(1)
    })

    it('stops at five files per message and says so', () => {
      const a = useChatAttachments(configRef())
      a.addAttachments(Array.from({ length: 7 }, (_, i) => file(`f${i}.pdf`, 'application/pdf', 1)))

      expect(a.attachedFiles.value).toHaveLength(5)
      expect(a.attachError.value).toContain('Maximum 5 files')
    })

    it('clears a previous error when a later call succeeds', () => {
      const a = useChatAttachments(configRef())
      a.addAttachments([file('huge.pdf', 'application/pdf', 500 * MB)])
      expect(a.attachError.value).not.toBeNull()

      a.addAttachments([file('ok.pdf', 'application/pdf', 1)])
      expect(a.attachError.value).toBeNull()
    })
  })

  describe('per-kind size caps', () => {
    it('names the kind in the refusal so the operator knows which limit to raise', () => {
      const a = useChatAttachments(configRef())
      a.addAttachments([file('big.png', 'image/png', 25 * MB)])

      expect(a.attachedFiles.value).toHaveLength(0)
      expect(a.attachError.value).toContain('big.png')
      expect(a.attachError.value).toContain('20 MB')
      expect(a.attachError.value).toContain('image')
    })

    it('applies a different default cap per kind', () => {
      // 25 MB is over the image cap but well under the audio and file caps.
      const a = useChatAttachments(configRef())
      a.addAttachments([file('big.mp3', 'audio/mpeg', 25 * MB), file('big.pdf', 'application/pdf', 25 * MB)])

      expect(a.attachedFiles.value.map(f => f.name)).toEqual(['big.mp3', 'big.pdf'])
      expect(a.attachError.value).toBeNull()
    })

    it('skips only the oversize file and keeps the rest of the batch', () => {
      // `continue`, not `break` — one bad file in a multi-select must not silently
      // drop everything the user picked after it.
      const a = useChatAttachments(configRef())
      a.addAttachments([
        file('big.png', 'image/png', 25 * MB),
        file('fine.png', 'image/png', 1 * MB),
      ])

      expect(a.attachedFiles.value.map(f => f.name)).toEqual(['fine.png'])
      expect(a.attachError.value).toContain('big.png')
    })

    it('takes the cap from config when Settings overrides it', () => {
      const a = useChatAttachments(configRef([{ key: 'upload.maxImageBytes', value: String(2 * MB) }]))
      a.addAttachments([file('mid.png', 'image/png', 5 * MB)])

      expect(a.attachedFiles.value).toHaveLength(0)
      expect(a.attachError.value).toContain('2 MB')
    })

    it.each([
      ['not a number', 'abc'],
      ['zero', '0'],
      ['negative', '-1'],
      ['empty', ''],
    ])('falls back to the default when the configured cap is %s', (_label, value) => {
      const a = useChatAttachments(configRef([{ key: 'upload.maxImageBytes', value }]))
      // 5 MB passes only if the 20 MB default is in force.
      a.addAttachments([file('mid.png', 'image/png', 5 * MB)])

      expect(a.attachedFiles.value).toHaveLength(1)
      expect(a.attachError.value).toBeNull()
    })

    it('falls back to the default when config has not loaded yet', () => {
      const a = useChatAttachments(ref(null) as unknown as ConfigRef)
      a.addAttachments([file('mid.png', 'image/png', 5 * MB)])

      expect(a.attachedFiles.value).toHaveLength(1)
    })
  })

  describe('removing files', () => {
    it('revokes the preview URL so blob handles do not leak across a long session', () => {
      const a = useChatAttachments(configRef())
      a.addAttachments([file('a.png', 'image/png', 1 * MB)])
      const url = a.attachmentPreviews.value.get(a.attachedFiles.value[0]!)

      a.removeAttachment(0)

      expect(URL.revokeObjectURL).toHaveBeenCalledWith(url)
      expect(a.attachmentPreviews.value.size).toBe(0)
      expect(a.attachedFiles.value).toHaveLength(0)
    })

    it('removes the file at the given index, not the first one', () => {
      const a = useChatAttachments(configRef())
      a.addAttachments([
        file('a.pdf', 'application/pdf', 1),
        file('b.pdf', 'application/pdf', 1),
        file('c.pdf', 'application/pdf', 1),
      ])

      a.removeAttachment(1)

      expect(a.attachedFiles.value.map(f => f.name)).toEqual(['a.pdf', 'c.pdf'])
    })

    it('is a no-op for an index that is not there', () => {
      const a = useChatAttachments(configRef())
      a.addAttachments([file('a.pdf', 'application/pdf', 1)])

      a.removeAttachment(9)

      expect(a.attachedFiles.value).toHaveLength(1)
      expect(URL.revokeObjectURL).not.toHaveBeenCalled()
    })

    it('removing a non-image does not revoke anything', () => {
      const a = useChatAttachments(configRef())
      a.addAttachments([file('c.pdf', 'application/pdf', 1)])

      a.removeAttachment(0)

      expect(URL.revokeObjectURL).not.toHaveBeenCalled()
      expect(a.attachedFiles.value).toHaveLength(0)
    })

    it('frees a slot so a sixth file fits after one is removed', () => {
      const a = useChatAttachments(configRef())
      a.addAttachments(Array.from({ length: 5 }, (_, i) => file(`f${i}.pdf`, 'application/pdf', 1)))
      a.removeAttachment(0)
      a.addAttachments([file('new.pdf', 'application/pdf', 1)])

      expect(a.attachedFiles.value.map(f => f.name)).toEqual(['f1.pdf', 'f2.pdf', 'f3.pdf', 'f4.pdf', 'new.pdf'])
      expect(a.attachError.value).toBeNull()
    })
  })

  describe('file picker', () => {
    it('clicks the hidden input', () => {
      const a = useChatAttachments(configRef())
      const click = vi.fn()
      a.fileInput.value = { click } as unknown as HTMLInputElement

      a.triggerFileUpload()

      expect(click).toHaveBeenCalledOnce()
    })

    it('does nothing when the input is not mounted', () => {
      const a = useChatAttachments(configRef())
      expect(() => a.triggerFileUpload()).not.toThrow()
    })
  })
})
