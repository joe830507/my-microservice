import apiClient from '@/api/http'
import type { CreateOrderReq, Order, OrderPage, OrderQueryParams } from './types'

// 成功回 201：訂單為 PENDING_PAYMENT，庫存同時預扣；付款期限由後端的 payment-timeout 決定
// idempotencyKey：同一次結帳重送時帶同一個 key，後端回原本那一張訂單（200），不會建立第二張；
// 同一個 key 搭配不同的商品內容會被拒絕（422 IDEMPOTENCY_KEY_REUSED）
export async function createOrder(req: CreateOrderReq, idempotencyKey?: string): Promise<Order> {
  const { data } = await apiClient.post<Order>('/orders', req, {
    headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
  })
  return data
}

// 自己的訂單；不存在或是別人的訂單都回 404 ORDER_NOT_FOUND
export async function getOrder(id: string): Promise<Order> {
  const { data } = await apiClient.get<Order>(`/orders/${encodeURIComponent(id)}`)
  return data
}

// 只回自己的訂單（後端依登入者過濾，沒有帶 userId 的參數）
export async function getOrders(params: OrderQueryParams): Promise<OrderPage> {
  const { data } = await apiClient.get<OrderPage>('/orders', { params })
  return data
}

// 只有 PENDING_PAYMENT 可以取消，取消後釋放預扣的庫存；已經取消的重複呼叫也回 200（冪等）
// 其他狀態回 409 ORDER_NOT_CANCELLABLE（例如剛好付款了）
export async function cancelOrder(id: string): Promise<Order> {
  const { data } = await apiClient.post<Order>(`/orders/${encodeURIComponent(id)}/cancel`)
  return data
}
