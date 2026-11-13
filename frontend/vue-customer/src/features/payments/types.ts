// 對應 payment-service 的 PaymentResDto；BigDecimal 在 JSON 是數字

// PENDING → SUCCEEDED／FAILED（回呼），或 CANCELLED（訂單取消）；三者都是終態
export type PaymentStatus = 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

export interface Payment {
  id: string
  orderId: string
  // 金額取自 order-service 的訂單，前端不傳
  amount: number
  status: PaymentStatus
  createdAt: string
  completedAt: string | null
}

export type MockPaymentResult = 'SUCCESS' | 'FAILED'
