// 對應 product-service 的前台用 DTO；BigDecimal 在 JSON 是數字

// GET /categories/tree：只有啟用中的分類，頂層放在 roots，子分類在 children
export interface CategoryTreeNode {
  id: string
  name: string
  slug: string
  children: CategoryTreeNode[]
}

export interface GetCategoriesTreeRes {
  roots: CategoryTreeNode[]
}

// GET /categories/slug/{slug}：停用的分類回 404 CATEGORY_NOT_FOUND
export interface CategoryBySlug {
  id: string
  parentId: string | null
  name: string
  slug: string
  description: string | null
  sortOrder: number
}

// GET /products 的一筆；公開 API 只會有 ACTIVE 的商品
export interface ProductListItem {
  id: string
  sku: string
  name: string
  categoryId: string | null
  brand: string | null
  price: number
  // null 代表沒有原價（不顯示折扣）
  originalPrice: number | null
  status: string
  createdAt: string
  // 主圖的公開網址；沒有主圖為 null
  primaryImageUrl: string | null
}

// GET /products/{id}：非上架中（草稿、下架、封存）的商品對顧客回 404 PRODUCT_NOT_FOUND
export interface Product {
  id: string
  sku: string
  name: string
  description: string | null
  categoryId: string | null
  brand: string | null
  price: number
  originalPrice: number | null
  status: string
  createdAt: string
  updatedAt: string
}

// GET /products/{id}/images 的一張；回應的順序就是顯示順序
export interface ProductImage {
  id: string
  url: string
  altText: string | null
  sortOrder: number
  isPrimary: boolean
}

export interface ProductImagesRes {
  productId: string
  images: ProductImage[]
}

// GET /inventory/{sku}
export interface Stock {
  sku: string
  // 可賣的數量，不含已預扣的
  available: number
  reserved: number
  updatedAt: string
}

export interface ProductPage {
  items: ProductListItem[]
  // 從 0 開始
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export type ProductSortBy = 'createdAt' | 'price' | 'relevance'
export type SortDirection = 'asc' | 'desc'

// GET /products 的查詢參數（page 從 0 開始）
export interface ProductQueryParams {
  keyword?: string
  categoryId?: string
  // 連同所有子孫分類一起查
  includeSubcategories?: boolean
  minPrice?: number
  maxPrice?: number
  page: number
  size: number
  sortBy: ProductSortBy
  sortDirection?: SortDirection
}
