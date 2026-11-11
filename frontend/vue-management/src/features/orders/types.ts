// 對應 order-service 的 GetOrdersResDto、OrderResDto；BigDecimal 在 JSON 是數字

// PENDING_PAYMENT → PAID → SHIPPED → COMPLETED
// PENDING_PAYMENT → CANCELLED（使用者或後台取消、付款逾時）
export type OrderStatus = 'PENDING_PAYMENT' | 'PAID' | 'SHIPPED' | 'COMPLETED' | 'CANCELLED'

export const ORDER_STATUSES: OrderStatus[] = [
  'PENDING_PAYMENT',
  'PAID',
  'SHIPPED',
  'COMPLETED',
  'CANCELLED',
]

// 列表只有摘要，沒有明細
export interface OrderListItem {
  id: string
  userId: string
  status: OrderStatus
  totalAmount: number
  createdAt: string
}

export interface OrderPage {
  items: OrderListItem[]
  // 從 0 開始
  page: number
  size: number
  totalElements: number
  totalPages: number
}

// 下單當下的商品快照
export interface OrderItem {
  productId: string
  sku: string
  productName: string
  unitPrice: number
  qty: number
  subtotal: number
}

export interface Order {
  id: string
  userId: string
  status: OrderStatus
  totalAmount: number
  // 依 SKU 排序
  items: OrderItem[]
  createdAt: string
  paidAt: string | null
  shippedAt: string | null
  completedAt: string | null
  cancelledAt: string | null
}

// GET /orders 的查詢參數（page 從 0 開始；固定最新的在前）
export interface OrderQueryParams {
  status?: OrderStatus
  userId?: string
  page: number
  size: number
}
