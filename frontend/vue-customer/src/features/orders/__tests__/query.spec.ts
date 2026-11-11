import { describe, expect, it } from 'vitest'
import { DEFAULT_ORDER_QUERY, parseOrderQuery, toOrderApiParams, toOrderRouteQuery } from '../query'
import { orderNumber } from '../status'

describe('parseOrderQuery', () => {
  it('沒有參數：預設值', () => {
    expect(parseOrderQuery({})).toEqual(DEFAULT_ORDER_QUERY)
  })

  it('合法的參數照樣使用', () => {
    expect(parseOrderQuery({ status: 'PAID', page: '3' })).toEqual({ status: 'PAID', page: 3 })
  })

  it.each([
    ['不認識的狀態', { status: 'DELETED' }],
    ['狀態大小寫不對', { status: 'paid' }],
    ['頁碼 0', { page: '0' }],
    ['頁碼小數', { page: '1.5' }],
    ['頁碼不是數字', { page: 'x' }],
  ])('%s：改回預設值', (_, query) => {
    expect(parseOrderQuery(query)).toEqual(DEFAULT_ORDER_QUERY)
  })

  it('同一個 key 出現多次：只取第一個', () => {
    expect(parseOrderQuery({ status: ['PAID', 'SHIPPED'], page: ['2', '9'] })).toEqual({
      status: 'PAID',
      page: 2,
    })
  })
})

describe('toOrderRouteQuery / toOrderApiParams', () => {
  it('預設值不放進網址；其餘照放', () => {
    expect(toOrderRouteQuery(DEFAULT_ORDER_QUERY)).toEqual({})
    expect(toOrderRouteQuery({ status: 'PAID', page: 2 })).toEqual({ status: 'PAID', page: '2' })
  })

  it('API 參數：頁碼從 0 開始、每頁 10、沒有狀態就不帶', () => {
    expect(toOrderApiParams({ status: '', page: 3 })).toEqual({
      status: undefined,
      page: 2,
      size: 10,
    })
    expect(toOrderApiParams({ status: 'CANCELLED', page: 1 }).status).toBe('CANCELLED')
  })
})

describe('orderNumber', () => {
  it('取 UUID 後段的 8 碼大寫（前段是時間戳，同一時間的訂單會一樣）', () => {
    expect(orderNumber('019a7f1c-2a9e-7000-8000-00000000abcd')).toBe('0000ABCD')
    expect(orderNumber('019a7f1c-2a9e-7000-8000-123456789abc')).toBe('56789ABC')
  })
})
