import { toApiError } from '@/api/errors'
import type { OrderAction } from './api'
import type { OrderStatus } from './types'

export const ORDER_STATUS_LABELS: Record<OrderStatus, string> = {
  PENDING_PAYMENT: '待付款',
  PAID: '已付款',
  SHIPPED: '已出貨',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
}

export const ORDER_STATUS_TAG_TYPES: Record<
  OrderStatus,
  'primary' | 'success' | 'info' | 'warning' | 'danger'
> = {
  PENDING_PAYMENT: 'warning',
  PAID: 'primary',
  SHIPPED: 'info',
  COMPLETED: 'success',
  CANCELLED: 'danger',
}

// 每個狀態可以執行的操作（和 order-service 一致）：
//   待付款 → 取消（會釋放預扣的庫存）；已付款 → 出貨；已出貨 → 完成
// 已付款之後不能取消（雛型沒有退款流程）
export function availableAction(status: OrderStatus): OrderAction | null {
  switch (status) {
    case 'PENDING_PAYMENT':
      return 'cancel'
    case 'PAID':
      return 'ship'
    case 'SHIPPED':
      return 'complete'
    default:
      return null
  }
}

export const ORDER_ACTION_LABELS: Record<OrderAction, string> = {
  cancel: '取消訂單',
  ship: '出貨',
  complete: '完成訂單',
}

export const ORDER_ACTION_CONFIRMS: Record<OrderAction, string> = {
  cancel: '確定要取消這張訂單嗎？取消後會釋放預扣的庫存，無法復原。',
  ship: '確定要把這張訂單標記為已出貨嗎？',
  complete: '確定要把這張訂單標記為已完成嗎？',
}

export const ORDER_ACTION_DONE: Record<OrderAction, string> = {
  cancel: '訂單已取消',
  ship: '訂單已出貨',
  complete: '訂單已完成',
}

export function orderErrorMessage(error: unknown): string {
  const apiError = toApiError(error)
  switch (apiError.code) {
    case 'ORDER_NOT_FOUND':
      return '訂單不存在'
    // 例如使用者剛好付款了
    case 'ORDER_NOT_CANCELLABLE':
      return '只有待付款的訂單可以取消，訂單狀態可能剛改變，請重新整理'
    case 'ORDER_ALREADY_CANCELLED':
      return '訂單已經取消'
    case 'ORDER_NOT_SHIPPABLE':
      return '只有已付款的訂單可以出貨，請重新整理'
    case 'ORDER_NOT_COMPLETABLE':
      return '只有已出貨的訂單可以完成，請重新整理'
    case 'INVENTORY_SERVICE_UNAVAILABLE':
      return '庫存服務暫時無法使用，請稍後再試'
    default:
      return apiError.detail
  }
}
