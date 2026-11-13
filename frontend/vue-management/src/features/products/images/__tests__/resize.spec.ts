import { afterEach, expect, it, vi } from 'vitest'
import { optimizeImage } from '../resize'

afterEach(() => vi.unstubAllGlobals())

it('keeps the original if bitmap decoding is unavailable or fails', async () => {
  const file = new File(['original'], 'photo.png', { type: 'image/png' })
  vi.stubGlobal('createImageBitmap', undefined)
  expect(await optimizeImage(file)).toBe(file)
  vi.stubGlobal('createImageBitmap', vi.fn().mockRejectedValue(new Error('decode')))
  expect(await optimizeImage(file)).toBe(file)
})

it('limits the long edge, uploads a smaller WebP and releases the bitmap', async () => {
  const file = new File([new Uint8Array(1000)], 'photo.png', { type: 'image/png' })
  const bitmap = { width: 4000, height: 2000, close: vi.fn() }
  vi.stubGlobal('createImageBitmap', vi.fn().mockResolvedValue(bitmap))
  const drawImage = vi.fn()
  const canvas = {
    width: 0,
    height: 0,
    getContext: () => ({ drawImage }),
    toBlob: (callback: BlobCallback) => callback(new Blob(['small'], { type: 'image/webp' })),
  }
  const spy = vi
    .spyOn(document, 'createElement')
    .mockReturnValue(canvas as unknown as HTMLCanvasElement)
  try {
    const result = await optimizeImage(file)
    expect(result.type).toBe('image/webp')
    expect(result.name).toBe('photo.webp')
    expect(result.size).toBeLessThan(file.size)
    expect([canvas.width, canvas.height]).toEqual([1600, 800])
    expect(bitmap.close).toHaveBeenCalledOnce()
  } finally {
    spy.mockRestore()
  }
})

it('retains the original when conversion increases size', async () => {
  const file = new File(['tiny'], 'photo.png', { type: 'image/png' })
  const bitmap = { width: 10, height: 10, close: vi.fn() }
  vi.stubGlobal('createImageBitmap', vi.fn().mockResolvedValue(bitmap))
  const canvas = {
    getContext: () => ({ drawImage: vi.fn() }),
    toBlob: (callback: BlobCallback) =>
      callback(new Blob([new Uint8Array(100)], { type: 'image/webp' })),
  }
  const spy = vi
    .spyOn(document, 'createElement')
    .mockReturnValue(canvas as unknown as HTMLCanvasElement)
  try {
    expect(await optimizeImage(file)).toBe(file)
  } finally {
    spy.mockRestore()
  }
})
