import { describe, expect, it } from 'vitest'
import { DEFAULT_PRODUCT_QUERY, parseProductQuery, toApiParams, toRouteQuery } from '../query'

describe('parseProductQuery', () => {
  it('沒有參數：預設值', () => {
    expect(parseProductQuery({})).toEqual(DEFAULT_PRODUCT_QUERY)
  })

  it('合法的參數照樣使用', () => {
    expect(
      parseProductQuery({
        keyword: ' 鞋 ',
        categoryId: 'shoes-id',
        status: 'ACTIVE',
        minPrice: '100',
        maxPrice: '999.5',
        page: '3',
        size: '50',
        sortBy: 'price',
        sortDirection: 'asc',
      }),
    ).toEqual({
      keyword: '鞋',
      categoryId: 'shoes-id',
      status: 'ACTIVE',
      minPrice: '100',
      maxPrice: '999.5',
      page: 3,
      size: 50,
      sortBy: 'price',
      sortDirection: 'asc',
    })
  })

  it('不合法的參數改回預設值', () => {
    expect(
      parseProductQuery({
        status: 'DELETED',
        minPrice: '-1',
        maxPrice: 'abc',
        page: '0',
        size: '7',
        sortBy: 'sku',
        sortDirection: 'up',
      }),
    ).toEqual(DEFAULT_PRODUCT_QUERY)
  })

  it('同一個參數出現多次：取第一個', () => {
    expect(parseProductQuery({ page: ['2', '5'] }).page).toBe(2)
  })
})

describe('toRouteQuery', () => {
  it('和預設值相同的條件不放進 URL', () => {
    expect(toRouteQuery(DEFAULT_PRODUCT_QUERY)).toEqual({})
  })

  it('只放有變動的條件，值轉成字串', () => {
    expect(
      toRouteQuery({ ...DEFAULT_PRODUCT_QUERY, keyword: ' 鞋 ', page: 2, sortDirection: 'asc' }),
    ).toEqual({ keyword: '鞋', page: '2', sortDirection: 'asc' })
  })
})

describe('toApiParams', () => {
  it('page 減 1、價格轉數字、空條件不送', () => {
    expect(
      toApiParams({ ...DEFAULT_PRODUCT_QUERY, page: 2, minPrice: '100', status: 'DRAFT' }),
    ).toEqual({
      keyword: undefined,
      categoryId: undefined,
      status: 'DRAFT',
      minPrice: 100,
      maxPrice: undefined,
      page: 1,
      size: 20,
      sortBy: 'createdAt',
      sortDirection: 'desc',
    })
  })
})
