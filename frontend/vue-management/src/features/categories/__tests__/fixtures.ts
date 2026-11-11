import type { Category } from '../types'

export function category(overrides: Partial<Category> & Pick<Category, 'id' | 'name'>): Category {
  return {
    parentId: null,
    slug: overrides.name.toLowerCase(),
    description: null,
    sortOrder: 0,
    active: true,
    createdAt: '2026-11-01T00:00:00Z',
    updatedAt: '2026-11-01T00:00:00Z',
    version: 0,
    ...overrides,
  }
}

// 後端排序：sortOrder、name。Bags（停用）、Boots（Shoes 的子分類）、Shoes
export const bags = category({ id: 'bags-id', name: 'Bags', active: false, version: 1 })
export const boots = category({ id: 'boots-id', name: 'Boots', parentId: 'shoes-id', version: 3 })
export const shoes = category({ id: 'shoes-id', name: 'Shoes', sortOrder: 1 })
export const allCategories = [bags, boots, shoes]
