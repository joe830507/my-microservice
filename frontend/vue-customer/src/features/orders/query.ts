import type { LocationQuery, LocationQueryRaw, LocationQueryValue } from 'vue-router'
import { ORDER_STATUSES, type OrderQueryParams, type OrderStatus } from './types'

// 訂單列表的條件，放在 URL query；URL 上的 page 從 1 開始，送給後端時減 1
export interface OrderListQuery {
  status: OrderStatus | ''
  page: number
}

export const ORDER_PAGE_SIZE = 10

export const DEFAULT_ORDER_QUERY: OrderListQuery = { status: '', page: 1 }

function first(value: LocationQueryValue | LocationQueryValue[] | undefined): string {
  const single = Array.isArray(value) ? value[0] : value
  return typeof single === 'string' ? single.trim() : ''
}

// 不合法的值（手改網址）改回預設值
export function parseOrderQuery(query: LocationQuery): OrderListQuery {
  const status = first(query.status)
  const page = Number(first(query.page))
  return {
    status: (ORDER_STATUSES as string[]).includes(status) ? (status as OrderStatus) : '',
    page: Number.isInteger(page) && page >= 1 ? page : DEFAULT_ORDER_QUERY.page,
  }
}

// 和預設值相同的條件不放進網址
export function toOrderRouteQuery(query: OrderListQuery): LocationQueryRaw {
  const result: LocationQueryRaw = {}
  if (query.status) result.status = query.status
  if (query.page !== DEFAULT_ORDER_QUERY.page) result.page = String(query.page)
  return result
}

export function toOrderApiParams(query: OrderListQuery): OrderQueryParams {
  return {
    status: query.status || undefined,
    page: query.page - 1,
    size: ORDER_PAGE_SIZE,
  }
}
