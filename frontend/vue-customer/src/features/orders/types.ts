// 對應 order-service 的 OrderResDto、CreateOrderReqDto；BigDecimal 在 JSON 是數字

// PENDING_PAYMENT → PAID → SHIPPED → COMPLETED
// PENDING_PAYMENT → CANCELLED（顧客取消、付款逾時）
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

// GET /orders 的查詢參數（page 從 0 開始）；一般使用者只會拿到自己的訂單，固定最新的在前
export interface OrderQueryParams {
  status?: OrderStatus
  page: number
  size: number
}

// 下單當下的商品快照：之後商品改名、改價都不影響這張訂單
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
  items: OrderItem[]
  createdAt: string
  paidAt: string | null
  shippedAt: string | null
  completedAt: string | null
  cancelledAt: string | null
}

// 只接受商品與數量，名稱、價格一律以後端查回來的為準；一張訂單最多 50 種商品、單項 1–99 件
export interface CreateOrderReq {
  items: { productId: string; qty: number }[]
}
