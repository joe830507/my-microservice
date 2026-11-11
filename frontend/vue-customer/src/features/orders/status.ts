import { toApiError } from '@/api/errors'
import type { OrderStatus } from './types'

export const ORDER_STATUS_LABELS: Record<OrderStatus, string> = {
  PENDING_PAYMENT: '待付款',
  PAID: '已付款',
  SHIPPED: '已出貨',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
}

// 訂單編號：UUIDv7 的前段是時間戳，同一時間建立的訂單前綴會一樣，所以取後段（隨機的部分）給人看
export function orderNumber(id: string): string {
  return id.replace(/-/g, '').slice(-8).toUpperCase()
}

// 取消訂單失敗的訊息；失敗後畫面會重新查訂單，所以這裡只說原因
export function cancelErrorMessage(error: unknown): string {
  const apiError = toApiError(error)
  switch (apiError.code) {
    // 例如剛好付款了：已付款的訂單雛型階段不能自己取消（沒有退款流程）
    case 'ORDER_NOT_CANCELLABLE':
      return '這張訂單目前無法取消（可能剛完成付款），已更新為最新狀態'
    case 'ORDER_NOT_FOUND':
      return '找不到這張訂單'
    // 訂單已經取消，只是釋放庫存還沒成功：重新查訂單會看到已取消，庫存由後端重試
    case 'INVENTORY_SERVICE_UNAVAILABLE':
      return '系統忙碌，已更新為最新狀態，請確認訂單是否已取消'
    default:
      return apiError.detail
  }
}
