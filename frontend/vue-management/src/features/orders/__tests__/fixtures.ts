import type { Order, OrderListItem, OrderPage, OrderStatus } from '../types'

export const USER_ID = '0193a1b2-0000-7000-8000-000000000001'
export const OTHER_USER_ID = '0193a1b2-0000-7000-8000-000000000002'
export const ORDER_ID = '0193a1b2-0000-7000-8000-0000000000aa'

export function listItem(id: string, status: OrderStatus, userId = USER_ID): OrderListItem {
  return { id, userId, status, totalAmount: 2400, createdAt: '2026-11-01T00:00:00Z' }
}

export function page(items: OrderListItem[], overrides: Partial<OrderPage> = {}): OrderPage {
  return { items, page: 0, size: 20, totalElements: items.length, totalPages: 1, ...overrides }
}

export function order(status: OrderStatus, overrides: Partial<Order> = {}): Order {
  return {
    id: ORDER_ID,
    userId: USER_ID,
    status,
    totalAmount: 2650,
    items: [
      {
        productId: 'p1',
        sku: 'SKU-001',
        productName: 'Runner（下單時的名稱）',
        unitPrice: 1200,
        qty: 2,
        subtotal: 2400,
      },
      {
        productId: 'p2',
        sku: 'SKU-002',
        productName: 'Socks',
        unitPrice: 250,
        qty: 1,
        subtotal: 250,
      },
    ],
    createdAt: '2026-11-01T00:00:00Z',
    paidAt: status === 'PENDING_PAYMENT' || status === 'CANCELLED' ? null : '2026-11-01T00:05:00Z',
    shippedAt: status === 'SHIPPED' || status === 'COMPLETED' ? '2026-11-02T00:00:00Z' : null,
    completedAt: status === 'COMPLETED' ? '2026-11-05T00:00:00Z' : null,
    cancelledAt: status === 'CANCELLED' ? '2026-11-01T00:30:00Z' : null,
    ...overrides,
  }
}
