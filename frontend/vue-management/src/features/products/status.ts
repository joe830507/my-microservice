import type { ProductStatus } from './types'

// 狀態流轉（和 product-service 一致）：
//   DRAFT、INACTIVE --publish--> ACTIVE --unpublish--> INACTIVE
//   ARCHIVED 以外 --archive--> ARCHIVED（不能再改回來，也不能修改內容）

export const STATUS_LABELS: Record<ProductStatus, string> = {
  DRAFT: '草稿',
  ACTIVE: '上架中',
  INACTIVE: '已下架',
  ARCHIVED: '已封存',
}

export const STATUS_TAG_TYPES: Record<ProductStatus, 'info' | 'success' | 'warning' | 'danger'> = {
  DRAFT: 'info',
  ACTIVE: 'success',
  INACTIVE: 'warning',
  ARCHIVED: 'danger',
}

export function canPublish(status: ProductStatus): boolean {
  return status === 'DRAFT' || status === 'INACTIVE'
}

export function canUnpublish(status: ProductStatus): boolean {
  return status === 'ACTIVE'
}

export function canArchive(status: ProductStatus): boolean {
  return status !== 'ARCHIVED'
}

export function canEdit(status: ProductStatus): boolean {
  return status !== 'ARCHIVED'
}
