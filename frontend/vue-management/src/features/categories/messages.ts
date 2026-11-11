import { toApiError } from '@/api/errors'

// 新增、修改失敗時的訊息
export function saveErrorMessage(error: unknown): string {
  const apiError = toApiError(error)
  switch (apiError.code) {
    case 'CATEGORY_SLUG_DUPLICATED':
      return '這個 slug 已被其他分類使用'
    case 'CATEGORY_NAME_DUPLICATED':
      return '同一層已有相同名稱的分類'
    case 'CATEGORY_PARENT_NOT_FOUND':
      return '上層分類不存在，請重新整理後再試'
    case 'CATEGORY_PARENT_INVALID':
      return '上層分類不能是自己或自己的子分類'
    case 'CATEGORY_NOT_FOUND':
      return '分類不存在，可能已被刪除，請重新整理'
    case 'VERSION_CONFLICT':
      return '分類已被其他人修改，請重新整理後再試'
    // 同時新增同名或同 slug，被 DB 的唯一限制擋下
    case 'DATA_CONFLICT':
      return '名稱或 slug 和其他分類重複'
    case 'VALIDATION_FAILED':
      return `欄位格式不正確：${apiError.detail}`
    default:
      return apiError.detail
  }
}

// 刪除失敗時的訊息
export function deleteErrorMessage(error: unknown): string {
  const apiError = toApiError(error)
  switch (apiError.code) {
    // 子分類、商品的外鍵是 ON DELETE RESTRICT
    case 'DATA_CONFLICT':
      return '這個分類底下還有子分類或商品，無法刪除'
    default:
      return apiError.detail
  }
}
