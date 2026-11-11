// 對應 payment-service 的 PaymentResDto；BigDecimal 在 JSON 是數字

// PENDING → SUCCEEDED 或 FAILED（由金流回呼決定），兩者都是終點
export type PaymentStatus = 'PENDING' | 'SUCCEEDED' | 'FAILED'

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
