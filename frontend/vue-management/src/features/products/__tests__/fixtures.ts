import type { Product, ProductListItem, ProductPage, ProductStatus } from '../types'

export function listItem(
  overrides: Partial<ProductListItem> & Pick<ProductListItem, 'id'>,
): ProductListItem {
  return {
    sku: `SKU-${overrides.id}`,
    name: `商品 ${overrides.id}`,
    categoryId: null,
    brand: null,
    price: 100,
    originalPrice: null,
    status: 'DRAFT',
    createdAt: '2026-11-01T00:00:00Z',
    ...overrides,
  }
}

export function page(items: ProductListItem[], overrides: Partial<ProductPage> = {}): ProductPage {
  return { items, page: 0, size: 20, totalElements: items.length, totalPages: 1, ...overrides }
}

export function product(
  status: ProductStatus = 'DRAFT',
  overrides: Partial<Product> = {},
): Product {
  return {
    id: 'p1',
    sku: 'SKU-001',
    name: 'Runner',
    description: null,
    categoryId: 'shoes-id',
    brand: 'ACME',
    price: 1200,
    originalPrice: 1500,
    status,
    createdAt: '2026-11-01T00:00:00Z',
    updatedAt: '2026-11-01T00:00:00Z',
    version: 2,
    ...overrides,
  }
}
