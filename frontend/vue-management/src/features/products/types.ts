// 對應 product-service 的商品 DTO；BigDecimal 在 JSON 是數字

export type ProductStatus = 'DRAFT' | 'ACTIVE' | 'INACTIVE' | 'ARCHIVED'

export const PRODUCT_STATUSES: ProductStatus[] = ['DRAFT', 'ACTIVE', 'INACTIVE', 'ARCHIVED']

// GET /products 的一筆（沒有 description、version）
export interface ProductListItem {
  id: string
  sku: string
  name: string
  categoryId: string | null
  brand: string | null
  price: number
  originalPrice: number | null
  status: ProductStatus
  createdAt: string
}

export interface ProductPage {
  items: ProductListItem[]
  // 從 0 開始
  page: number
  size: number
  totalElements: number
  totalPages: number
}

// GET /products/{id}、新增、修改的回應
export interface Product extends ProductListItem {
  description: string | null
  // 新增的回應沒有 updatedAt
  updatedAt?: string
  // 修改、上下架時要帶回，做樂觀鎖檢查
  version: number
}

export interface ProductReq {
  sku: string
  name: string
  description: string | null
  // 草稿可以先不指定分類，上架前才檢查
  categoryId: string | null
  brand: string | null
  price: number
  // null 代表沒有原價（不顯示折扣）
  originalPrice: number | null
}

export interface UpdateProductReq extends ProductReq {
  version: number
}

// 上架、下架、封存的回應
export interface ChangeProductStatusRes {
  id: string
  status: ProductStatus
  updatedAt: string
  version: number
}

export type ProductSortBy = 'createdAt' | 'price' | 'name'
export type SortDirection = 'asc' | 'desc'

// GET /products 的查詢參數（page 從 0 開始）
export interface ProductQueryParams {
  keyword?: string
  categoryId?: string
  status?: ProductStatus
  minPrice?: number
  maxPrice?: number
  page: number
  size: number
  sortBy: ProductSortBy
  sortDirection: SortDirection
}
