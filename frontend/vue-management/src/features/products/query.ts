import type { LocationQuery, LocationQueryRaw, LocationQueryValue } from 'vue-router'
import {
  PRODUCT_STATUSES,
  type ProductQueryParams,
  type ProductSortBy,
  type ProductStatus,
  type SortDirection,
} from './types'

// 商品列表的條件，放在 URL query：重新整理、分享連結都能保留篩選、分頁、排序
// URL 上的 page 從 1 開始（給人看），送給後端時再減 1
export interface ProductListQuery {
  keyword: string
  categoryId: string
  status: ProductStatus | ''
  // 保留字串：使用者還在輸入時不要被轉成數字
  minPrice: string
  maxPrice: string
  page: number
  size: number
  sortBy: ProductSortBy
  sortDirection: SortDirection
}

export const PAGE_SIZES = [10, 20, 50, 100]

export const DEFAULT_PRODUCT_QUERY: ProductListQuery = {
  keyword: '',
  categoryId: '',
  status: '',
  minPrice: '',
  maxPrice: '',
  page: 1,
  size: 20,
  sortBy: 'createdAt',
  sortDirection: 'desc',
}

const SORT_FIELDS: ProductSortBy[] = ['createdAt', 'price', 'name']
const PRICE_PATTERN = /^\d+(\.\d{1,2})?$/

// 同一個 key 出現多次時 vue-router 給陣列，只取第一個
function first(value: LocationQueryValue | LocationQueryValue[] | undefined): string {
  const single = Array.isArray(value) ? value[0] : value
  return typeof single === 'string' ? single.trim() : ''
}

function positiveInt(value: string, fallback: number): number {
  const parsed = Number(value)
  return Number.isInteger(parsed) && parsed >= 1 ? parsed : fallback
}

// 不合法的值（手動改網址、舊連結）一律改回預設值，不讓錯誤的參數送到後端
export function parseProductQuery(query: LocationQuery): ProductListQuery {
  const status = first(query.status)
  const sortBy = first(query.sortBy)
  const sortDirection = first(query.sortDirection)
  const size = positiveInt(first(query.size), DEFAULT_PRODUCT_QUERY.size)
  const minPrice = first(query.minPrice)
  const maxPrice = first(query.maxPrice)

  return {
    keyword: first(query.keyword),
    categoryId: first(query.categoryId),
    status: (PRODUCT_STATUSES as string[]).includes(status) ? (status as ProductStatus) : '',
    minPrice: PRICE_PATTERN.test(minPrice) ? minPrice : '',
    maxPrice: PRICE_PATTERN.test(maxPrice) ? maxPrice : '',
    page: positiveInt(first(query.page), DEFAULT_PRODUCT_QUERY.page),
    size: PAGE_SIZES.includes(size) ? size : DEFAULT_PRODUCT_QUERY.size,
    sortBy: (SORT_FIELDS as string[]).includes(sortBy)
      ? (sortBy as ProductSortBy)
      : DEFAULT_PRODUCT_QUERY.sortBy,
    sortDirection: sortDirection === 'asc' || sortDirection === 'desc' ? sortDirection : 'desc',
  }
}

// 和預設值相同的條件不放進 URL，網址保持簡短
export function toRouteQuery(query: ProductListQuery): LocationQueryRaw {
  const result: LocationQueryRaw = {}
  for (const key of Object.keys(DEFAULT_PRODUCT_QUERY) as (keyof ProductListQuery)[]) {
    const value = typeof query[key] === 'string' ? (query[key] as string).trim() : query[key]
    if (value !== DEFAULT_PRODUCT_QUERY[key] && value !== '') {
      result[key] = String(value)
    }
  }
  return result
}

export function toApiParams(query: ProductListQuery): ProductQueryParams {
  return {
    keyword: query.keyword || undefined,
    categoryId: query.categoryId || undefined,
    status: query.status || undefined,
    minPrice: query.minPrice ? Number(query.minPrice) : undefined,
    maxPrice: query.maxPrice ? Number(query.maxPrice) : undefined,
    page: query.page - 1,
    size: query.size,
    sortBy: query.sortBy,
    sortDirection: query.sortDirection,
  }
}
