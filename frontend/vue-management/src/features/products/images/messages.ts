import { toApiError } from '@/api/errors'
import { productErrorMessage } from '../messages'

// 圖片操作失敗時的訊息；圖片沒有對應的代碼就沿用商品的訊息（例如 PRODUCT_ARCHIVED）
export function imageErrorMessage(error: unknown): string {
  const apiError = toApiError(error)
  switch (apiError.code) {
    case 'IMAGE_CONTENT_TYPE_NOT_ALLOWED':
      return '只能上傳 JPEG、PNG、WebP 圖片'
    case 'IMAGE_TOO_LARGE':
      return '圖片不能超過 5 MB'
    // 前端自己的代碼：PUT 到物件儲存失敗
    case 'IMAGE_UPLOAD_FAILED':
      return '圖片上傳失敗，請重新上傳'
    case 'IMAGE_UPLOAD_NOT_FOUND':
      return '找不到上傳的檔案，可能已經過期，請重新上傳'
    case 'IMAGE_OBJECT_KEY_INVALID':
      return '上傳資訊不正確，請重新上傳'
    case 'PRODUCT_IMAGE_NOT_FOUND':
      return '圖片不存在，可能已被刪除，請重新整理'
    case 'PRODUCT_IMAGE_PRIMARY_UNDELETABLE':
      return '上架中的商品不能刪除主圖，請先把其他圖片設為主圖'
    case 'PRODUCT_IMAGE_IDS_MISMATCH':
      return '圖片已被其他人變更，請重新整理後再排序'
    case 'PRODUCT_ARCHIVED':
      return '商品已封存，不能修改圖片'
    default:
      return productErrorMessage(error)
  }
}
