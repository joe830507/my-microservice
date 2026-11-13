import { expect, test } from '@playwright/test'

test('browser canvas converts a large upload to a smaller WebP', async ({ page }) => {
  await page.goto('/')
  const result = await page.evaluate(async () => {
    const path = '/src/features/products/images/resize.ts'
    const { optimizeImage } = await import(/* @vite-ignore */ path)
    const canvas = document.createElement('canvas')
    canvas.width = 4000
    canvas.height = 2000
    const context = canvas.getContext('2d')!
    context.fillStyle = '#ff0000'
    context.fillRect(0, 0, canvas.width, canvas.height)
    const blob = await new Promise<Blob>((resolve) =>
      canvas.toBlob((value) => resolve(value!), 'image/png'),
    )
    const input = new File([blob], 'photo.png', { type: blob.type })
    const output = await optimizeImage(input)
    const image = await createImageBitmap(output)
    const result = {
      type: output.type,
      before: input.size,
      after: output.size,
      width: image.width,
      height: image.height,
    }
    image.close()
    return result
  })
  expect(result.type).toBe('image/webp')
  expect([result.width, result.height]).toEqual([1600, 800])
  expect(result.after).toBeLessThan(result.before)
})
