import type { CategoryTreeNode, ProductListItem, ProductPage } from '../types'

// catalog 測試共用的假資料

export function product(n: number, overrides: Partial<ProductListItem> = {}): ProductListItem {
  return {
    id: `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`,
    sku: `SKU-${n}`,
    name: `商品 ${n}`,
    categoryId: null,
    brand: null,
    price: 100 * n,
    originalPrice: null,
    status: 'ACTIVE',
    createdAt: '2026-11-01T00:00:00Z',
    primaryImageUrl: `http://img.example/${n}.jpg`,
    ...overrides,
  }
}

export function productPage(
  items: ProductListItem[],
  overrides: Partial<ProductPage> = {},
): ProductPage {
  return {
    items,
    page: 0,
    size: 12,
    totalElements: items.length,
    totalPages: items.length > 0 ? 1 : 0,
    ...overrides,
  }
}

export const categoryTree: CategoryTreeNode[] = [
  {
    id: 'cat-shoes',
    name: '鞋子',
    slug: 'shoes',
    children: [{ id: 'cat-boots', name: '靴子', slug: 'boots', children: [] }],
  },
  { id: 'cat-bags', name: '包包', slug: 'bags', children: [] },
]

// 陣列的最後一個元素（專案的 lib 是 ES2020，沒有 Array.prototype.at）
export function last<T>(items: T[]): T | undefined {
  return items[items.length - 1]
}
