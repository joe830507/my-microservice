import { describe, expect, it } from 'vitest'
import {
  DEFAULT_PRODUCT_QUERY,
  PAGE_SIZE,
  parseProductQuery,
  priceRangeError,
  toApiParams,
  toRouteQuery,
} from '../query'

describe('parseProductQuery', () => {
  it('沒有參數：預設值', () => {
    expect(parseProductQuery({})).toEqual(DEFAULT_PRODUCT_QUERY)
  })

  it('合法的參數照樣使用', () => {
    expect(
      parseProductQuery({
        q: ' 鞋 ',
        minPrice: '100',
        maxPrice: '999.5',
        sort: 'price-asc',
        page: '3',
      }),
    ).toEqual({ q: '鞋', minPrice: '100', maxPrice: '999.5', sort: 'price-asc', page: 3 })
  })

  it.each([
    ['價格是負數', { minPrice: '-1' }],
    ['價格不是數字', { maxPrice: 'abc' }],
    ['價格超過兩位小數', { minPrice: '1.234' }],
    ['不認識的排序', { sort: 'name' }],
    ['頁碼是 0', { page: '0' }],
    ['頁碼是小數', { page: '1.5' }],
    ['頁碼不是數字', { page: 'x' }],
  ])('%s：改回預設值', (_, query) => {
    expect(parseProductQuery(query)).toEqual(DEFAULT_PRODUCT_QUERY)
  })

  it('同一個 key 出現多次：只取第一個', () => {
    expect(parseProductQuery({ q: ['鞋', '包'], page: ['2', '3'] })).toMatchObject({
      q: '鞋',
      page: 2,
    })
  })

  it('關鍵字最多 255 字元（後端上限）', () => {
    expect(parseProductQuery({ q: 'a'.repeat(300) }).q).toHaveLength(255)
  })
})

describe('toRouteQuery', () => {
  it('預設值不放進網址', () => {
    expect(toRouteQuery(DEFAULT_PRODUCT_QUERY)).toEqual({})
  })

  it('只放和預設值不同的條件', () => {
    expect(
      toRouteQuery({ q: ' 鞋 ', minPrice: '', maxPrice: '500', sort: 'price-desc', page: 2 }),
    ).toEqual({ q: '鞋', maxPrice: '500', sort: 'price-desc', page: '2' })
  })

  it('和 parseProductQuery 互為反向', () => {
    const query = { q: '鞋', minPrice: '1', maxPrice: '9', sort: 'price-asc' as const, page: 4 }
    expect(parseProductQuery(toRouteQuery(query) as Record<string, string>)).toEqual(query)
  })
})

describe('toApiParams', () => {
  it('預設值：第一頁（從 0 開始）、最新上架、沒有篩選', () => {
    expect(toApiParams(DEFAULT_PRODUCT_QUERY)).toEqual({
      keyword: undefined,
      categoryId: undefined,
      includeSubcategories: undefined,
      minPrice: undefined,
      maxPrice: undefined,
      page: 0,
      size: PAGE_SIZE,
      sortBy: 'createdAt',
      sortDirection: 'desc',
    })
  })

  it('URL 條件轉成 API 參數', () => {
    expect(
      toApiParams({ q: '鞋', minPrice: '100', maxPrice: '999.5', sort: 'price-asc', page: 3 }),
    ).toMatchObject({
      keyword: '鞋',
      minPrice: 100,
      maxPrice: 999.5,
      page: 2,
      sortBy: 'price',
      sortDirection: 'asc',
    })
    expect(toApiParams({ ...DEFAULT_PRODUCT_QUERY, sort: 'price-desc' })).toMatchObject({
      sortBy: 'price',
      sortDirection: 'desc',
    })
  })

  it('有分類：帶 categoryId 並包含子分類；沒有分類則都不帶', () => {
    expect(toApiParams(DEFAULT_PRODUCT_QUERY, 'cat-1')).toMatchObject({
      categoryId: 'cat-1',
      includeSubcategories: true,
    })
    expect(toApiParams(DEFAULT_PRODUCT_QUERY)).toMatchObject({
      categoryId: undefined,
      includeSubcategories: undefined,
    })
  })
})

describe('priceRangeError', () => {
  it('最低價高於最高價：有錯誤訊息', () => {
    expect(priceRangeError({ minPrice: '500', maxPrice: '100' })).toBe('最低價不能高於最高價')
  })

  it.each([
    ['相等', '100', '100'],
    ['只填一邊', '100', ''],
    ['都沒填', '', ''],
    ['數字比較，不是字串比較', '9', '100'],
  ])('%s：沒問題', (_, minPrice, maxPrice) => {
    expect(priceRangeError({ minPrice, maxPrice })).toBe('')
  })
})
