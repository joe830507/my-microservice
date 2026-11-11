import apiClient from '@/api/http'
import type { MockPaymentResult, Payment } from './types'

// 同一張訂單已經有進行中（PENDING）的付款時，後端回 200 和那一筆，不會重複建立；新建立回 201
// 所以重新整理頁面、重送都是安全的
export async function createPayment(orderId: string): Promise<Payment> {
  const { data } = await apiClient.post<Payment>('/payments', { orderId })
  return data
}

export async function getPayment(id: string): Promise<Payment> {
  const { data } = await apiClient.get<Payment>(`/payments/${encodeURIComponent(id)}`)
  return data
}

// 模擬第三方金流的回呼（雛型階段由顧客自己呼叫，之後換成真的金流會移除）
// 同樣的結果重送回 200，相反的結果回 409 PAYMENT_ALREADY_COMPLETED
export async function mockCallback(id: string, result: MockPaymentResult): Promise<Payment> {
  const { data } = await apiClient.post<Payment>(
    `/payments/${encodeURIComponent(id)}/mock-callback`,
    {
      result,
    },
  )
  return data
}
