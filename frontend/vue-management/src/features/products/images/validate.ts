// 和 product-service 的 storage.allowed-content-types、storage.max-size 一致
// 前端先檢查，不合格的檔案不必申請上傳網址；後端仍會再檢查一次
export const ALLOWED_IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp']
export const MAX_IMAGE_BYTES = 5 * 1024 * 1024

// 合格回 null，不合格回要顯示的訊息
export function validateImageFile(file: File): string | null {
  if (!ALLOWED_IMAGE_TYPES.includes(file.type)) {
    return '只能上傳 JPEG、PNG、WebP 圖片'
  }
  if (file.size === 0) {
    return '檔案是空的'
  }
  if (file.size > MAX_IMAGE_BYTES) {
    return '圖片不能超過 5 MB'
  }
  return null
}

// 把 from 位置的項目移到 to，回傳新陣列
export function moveItem<T>(items: T[], from: number, to: number): T[] {
  const result = [...items]
  const [moved] = result.splice(from, 1)
  if (moved !== undefined) {
    result.splice(to, 0, moved)
  }
  return result
}
