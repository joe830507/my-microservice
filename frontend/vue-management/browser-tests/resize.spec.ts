import { expect, test } from '@playwright/test'

test('browser canvas converts a large upload to a smaller WebP', async ({ page }) => {
  type Result = { type: string; before: number; after: number; width: number; height: number }
  let result: Result | undefined
  // 乾淨環境（例如 CI）第一次載入時，Vite 會因為重新優化依賴而整頁重載，
  // 重載當下 evaluate 會丟出 "Execution context was destroyed"；失敗時重新載入再試
  await expect(async () => {
    await page.goto('/')
    result = await page.evaluate(async () => {
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
  }).toPass({ timeout: 30_000 })
  expect(result).toBeDefined()
  const output = result!
  expect(output.type).toBe('image/webp')
  expect([output.width, output.height]).toEqual([1600, 800])
  expect(output.after).toBeLessThan(output.before)
})
