import { toApiError } from '@/api/errors'

// 新增、修改、上下架、封存失敗時依錯誤代碼顯示的訊息
export function productErrorMessage(error: unknown): string {
  const apiError = toApiError(error)
  switch (apiError.code) {
    case 'PRODUCT_SKU_DUPLICATED':
    // 新增時沒有先檢查 SKU，重複時被 DB 的唯一限制擋下
    case 'DATA_CONFLICT':
      return 'SKU 已被其他商品使用'
    case 'PRODUCT_NOT_FOUND':
      return '商品不存在，可能已被刪除'
    case 'PRODUCT_ARCHIVED':
      return '商品已封存，不能修改'
    case 'PRODUCT_ALREADY_ARCHIVED':
      return '商品已經封存'
    case 'PRODUCT_STATUS_TRANSITION_INVALID':
      return '目前的狀態不能執行這個操作，請重新整理後再試'
    case 'PRODUCT_ACTIVE_CATEGORY_REQUIRED':
      return '上架中的商品必須設定啟用中的分類'
    case 'PRODUCT_PRIMARY_IMAGE_REQUIRED':
      return '上架前要先設定主圖'
    case 'CATEGORY_INACTIVE':
      return '選擇的分類已停用'
    case 'VERSION_CONFLICT':
      return '商品已被其他人修改，請重新整理後再試'
    case 'VALIDATION_FAILED':
      return `欄位格式不正確：${apiError.detail}`
    default:
      return apiError.detail
  }
}
