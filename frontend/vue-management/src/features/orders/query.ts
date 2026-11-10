import type { LocationQuery, LocationQueryRaw, LocationQueryValue } from 'vue-router'
import { ORDER_STATUSES, type OrderQueryParams, type OrderStatus } from './types'

// 訂單列表的條件，放在 URL query；URL 上的 page 從 1 開始，送給後端時減 1
export interface OrderListQuery {
  status: OrderStatus | ''
  userId: string
  page: number
  size: number
}

export const ORDER_PAGE_SIZES = [10, 20, 50, 100]

export const DEFAULT_ORDER_QUERY: OrderListQuery = {
  status: '',
  userId: '',
  page: 1,
  size: 20,
}

export const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function first(value: LocationQueryValue | LocationQueryValue[] | undefined): string {
  const single = Array.isArray(value) ? value[0] : value
  return typeof single === 'string' ? single.trim() : ''
}

function positiveInt(value: string, fallback: number): number {
  const parsed = Number(value)
  return Number.isInteger(parsed) && parsed >= 1 ? parsed : fallback
}

// 不合法的值改回預設值：不合法的 userId 送到後端會回 400
export function parseOrderQuery(query: LocationQuery): OrderListQuery {
  const status = first(query.status)
  const userId = first(query.userId)
  const size = positiveInt(first(query.size), DEFAULT_ORDER_QUERY.size)
  return {
    status: (ORDER_STATUSES as string[]).includes(status) ? (status as OrderStatus) : '',
    userId: UUID_PATTERN.test(userId) ? userId : '',
    page: positiveInt(first(query.page), DEFAULT_ORDER_QUERY.page),
    size: ORDER_PAGE_SIZES.includes(size) ? size : DEFAULT_ORDER_QUERY.size,
  }
}

export function toOrderRouteQuery(query: OrderListQuery): LocationQueryRaw {
  const result: LocationQueryRaw = {}
  for (const key of Object.keys(DEFAULT_ORDER_QUERY) as (keyof OrderListQuery)[]) {
    const value = query[key]
    if (value !== DEFAULT_ORDER_QUERY[key] && value !== '') {
      result[key] = String(value)
    }
  }
  return result
}

export function toOrderApiParams(query: OrderListQuery): OrderQueryParams {
  return {
    status: query.status || undefined,
    userId: query.userId || undefined,
    page: query.page - 1,
    size: query.size,
  }
}
