import apiClient from '@/api/http'
import type { CreateOrderReq, Order } from './types'

// 成功回 201：訂單為 PENDING_PAYMENT，庫存同時預扣；付款期限由後端的 payment-timeout 決定
// 沒有冪等 key：網路重送會建立兩張訂單，所以前端要防止重複送出（見之後再處理）
export async function createOrder(req: CreateOrderReq): Promise<Order> {
  const { data } = await apiClient.post<Order>('/orders', req)
  return data
}

// 自己的訂單；不存在或是別人的訂單都回 404 ORDER_NOT_FOUND
export async function getOrder(id: string): Promise<Order> {
  const { data } = await apiClient.get<Order>(`/orders/${encodeURIComponent(id)}`)
  return data
}
