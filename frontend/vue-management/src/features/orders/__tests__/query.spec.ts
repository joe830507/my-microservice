import { describe, expect, it } from 'vitest'
import { ApiError } from '@/api/errors'
import { DEFAULT_ORDER_QUERY, parseOrderQuery, toOrderApiParams, toOrderRouteQuery } from '../query'
import { availableAction, orderErrorMessage } from '../status'
import type { OrderStatus } from '../types'
import { USER_ID } from './fixtures'

describe('parseOrderQuery', () => {
  it('沒有參數：預設值', () => {
    expect(parseOrderQuery({})).toEqual(DEFAULT_ORDER_QUERY)
  })

  it('合法的參數照樣使用', () => {
    expect(parseOrderQuery({ status: 'PAID', userId: USER_ID, page: '2', size: '50' })).toEqual({
      status: 'PAID',
      userId: USER_ID,
      page: 2,
      size: 50,
    })
  })

  it('不合法的參數改回預設值', () => {
    expect(parseOrderQuery({ status: 'LOST', userId: 'abc', page: '-1', size: '7' })).toEqual(
      DEFAULT_ORDER_QUERY,
    )
  })
})

describe('toOrderRouteQuery、toOrderApiParams', () => {
  it('URL 省略預設值；API 的 page 從 0 開始', () => {
    const query = { ...DEFAULT_ORDER_QUERY, status: 'SHIPPED' as const, page: 3 }

    expect(toOrderRouteQuery(query)).toEqual({ status: 'SHIPPED', page: '3' })
    expect(toOrderApiParams(query)).toEqual({
      status: 'SHIPPED',
      userId: undefined,
      page: 2,
      size: 20,
    })
  })
})

describe('availableAction', () => {
  it.each<[OrderStatus, string | null]>([
    ['PENDING_PAYMENT', 'cancel'],
    ['PAID', 'ship'],
    ['SHIPPED', 'complete'],
    ['COMPLETED', null],
    ['CANCELLED', null],
  ])('%s → %s', (status, action) => {
    expect(availableAction(status)).toBe(action)
  })
})

describe('orderErrorMessage', () => {
  it.each([
    ['ORDER_NOT_FOUND', '訂單不存在'],
    ['ORDER_NOT_CANCELLABLE', '只有待付款的訂單可以取消，訂單狀態可能剛改變，請重新整理'],
    ['ORDER_ALREADY_CANCELLED', '訂單已經取消'],
    ['ORDER_NOT_SHIPPABLE', '只有已付款的訂單可以出貨，請重新整理'],
    ['ORDER_NOT_COMPLETABLE', '只有已出貨的訂單可以完成，請重新整理'],
    ['INVENTORY_SERVICE_UNAVAILABLE', '庫存服務暫時無法使用，請稍後再試'],
  ])('%s', (code, message) => {
    expect(orderErrorMessage(new ApiError(409, code, 'server detail'))).toBe(message)
  })

  it('其他代碼：顯示 detail', () => {
    expect(orderErrorMessage(new ApiError(0, 'NETWORK_ERROR', '無法連線'))).toBe('無法連線')
  })
})
