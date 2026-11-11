import type { Order, OrderListItem, OrderPage, OrderStatus } from '../types'

// orders 測試共用的假資料

export const minutesAgo = (minutes: number): string =>
  new Date(Date.now() - minutes * 60_000).toISOString()

export function order(
  status: OrderStatus = 'PENDING_PAYMENT',
  overrides: Partial<Order> = {},
): Order {
  return {
    id: '00000000-0000-4000-8000-0000000000a1',
    userId: 'u1',
    status,
    totalAmount: 1234.5,
    items: [
      { productId: 'p1', sku: 'S1', productName: '運動鞋', unitPrice: 600, qty: 2, subtotal: 1200 },
      {
        productId: 'p2',
        sku: 'S2',
        productName: '帆布包',
        unitPrice: 34.5,
        qty: 1,
        subtotal: 34.5,
      },
    ],
    createdAt: minutesAgo(10),
    paidAt: null,
    shippedAt: null,
    completedAt: null,
    cancelledAt: null,
    ...overrides,
  }
}

export function listItem(n: number, overrides: Partial<OrderListItem> = {}): OrderListItem {
  return {
    id: `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`,
    userId: 'u1',
    status: 'PAID',
    totalAmount: 100 * n,
    createdAt: '2026-11-10T08:00:00Z',
    ...overrides,
  }
}

export function orderPage(items: OrderListItem[], overrides: Partial<OrderPage> = {}): OrderPage {
  return {
    items,
    page: 0,
    size: 10,
    totalElements: items.length,
    totalPages: items.length > 0 ? 1 : 0,
    ...overrides,
  }
}
