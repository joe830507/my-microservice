import type { LocationQuery, LocationQueryRaw, LocationQueryValue } from 'vue-router'
import type { ProductQueryParams } from './types'

// 商品列表的條件，放在 URL query：重新整理、上一頁、分享連結都能保留
// URL 上的 page 從 1 開始（給人看），送給後端時再減 1
export type ProductSort = 'latest' | 'price-asc' | 'price-desc'

export interface ProductListQuery {
  // 搜尋關鍵字（網址上是 q）
  q: string
  // 保留字串：使用者還在輸入時不要被轉成數字
  minPrice: string
  maxPrice: string
  sort: ProductSort
  page: number
}

export const PAGE_SIZE = 12

export const SORT_OPTIONS: { value: ProductSort; label: string }[] = [
  { value: 'latest', label: '最新上架' },
  { value: 'price-asc', label: '價格由低到高' },
  { value: 'price-desc', label: '價格由高到低' },
]

export const DEFAULT_PRODUCT_QUERY: ProductListQuery = {
  q: '',
  minPrice: '',
  maxPrice: '',
  sort: 'latest',
  page: 1,
}

const PRICE_PATTERN = /^\d+(\.\d{1,2})?$/

// 同一個 key 出現多次時 vue-router 給陣列，只取第一個
function first(value: LocationQueryValue | LocationQueryValue[] | undefined): string {
  const single = Array.isArray(value) ? value[0] : value
  return typeof single === 'string' ? single.trim() : ''
}

// 不合法的值（手動改網址、舊連結）一律改回預設值，不讓錯誤的參數送到後端
export function parseProductQuery(query: LocationQuery): ProductListQuery {
  const sort = first(query.sort)
  const page = Number(first(query.page))
  const minPrice = first(query.minPrice)
  const maxPrice = first(query.maxPrice)

  return {
    q: first(query.q).slice(0, 255),
    minPrice: PRICE_PATTERN.test(minPrice) ? minPrice : '',
    maxPrice: PRICE_PATTERN.test(maxPrice) ? maxPrice : '',
    sort: SORT_OPTIONS.some((option) => option.value === sort)
      ? (sort as ProductSort)
      : DEFAULT_PRODUCT_QUERY.sort,
    page: Number.isInteger(page) && page >= 1 ? page : DEFAULT_PRODUCT_QUERY.page,
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

// 價格區間的錯誤訊息；沒問題回傳空字串
export function priceRangeError(query: Pick<ProductListQuery, 'minPrice' | 'maxPrice'>): string {
  if (query.minPrice && query.maxPrice && Number(query.minPrice) > Number(query.maxPrice)) {
    return '最低價不能高於最高價'
  }
  return ''
}

// categoryId 有值時連子分類一起查（點「鞋子」要看到「靴子」底下的商品）
export function toApiParams(query: ProductListQuery, categoryId?: string): ProductQueryParams {
  const sortParams: Pick<ProductQueryParams, 'sortBy' | 'sortDirection'> =
    query.sort === 'price-asc'
      ? { sortBy: 'price', sortDirection: 'asc' }
      : query.sort === 'price-desc'
        ? { sortBy: 'price', sortDirection: 'desc' }
        : { sortBy: 'createdAt', sortDirection: 'desc' }

  return {
    keyword: query.q || undefined,
    categoryId: categoryId || undefined,
    includeSubcategories: categoryId ? true : undefined,
    minPrice: query.minPrice ? Number(query.minPrice) : undefined,
    maxPrice: query.maxPrice ? Number(query.maxPrice) : undefined,
    page: query.page - 1,
    size: PAGE_SIZE,
    ...sortParams,
  }
}
