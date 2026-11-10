import apiClient from '@/api/http'
import type { Order, OrderPage, OrderQueryParams } from './types'

// ADMIN 看得到所有人的訂單，可以用 userId 篩選
export async function getOrders(params: OrderQueryParams): Promise<OrderPage> {
  const { data } = await apiClient.get<OrderPage>('/orders', { params })
  return data
}

export async function getOrder(id: string): Promise<Order> {
  const { data } = await apiClient.get<Order>(`/orders/${id}`)
  return data
}

export type OrderAction = 'cancel' | 'ship' | 'complete'

// 三個操作都回傳更新後的訂單；重複呼叫（已經是目標狀態）也回 200
export async function changeOrderStatus(id: string, action: OrderAction): Promise<Order> {
  const { data } = await apiClient.post<Order>(`/orders/${id}/${action}`)
  return data
}
