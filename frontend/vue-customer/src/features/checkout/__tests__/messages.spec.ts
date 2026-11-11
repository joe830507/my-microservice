import { describe, expect, it } from 'vitest'
import { ApiError } from '@/api/errors'
import { buildLines } from '@/features/cart/lines'
import { createOrderFailure } from '../messages'

const lines = buildLines(
  [
    { productId: 'id-1', qty: 1 },
    { productId: 'id-2', qty: 2 },
  ],
  [
    { id: 'id-1', sku: 'SKU-A', name: '運動鞋', price: 100, status: 'ACTIVE' },
    { id: 'id-2', sku: 'SKU-B', name: '帆布包', price: 50, status: 'ACTIVE' },
  ],
)

const fail = (status: number, code: string, detail = code) =>
  createOrderFailure(new ApiError(status, code, detail), lines)

describe('createOrderFailure', () => {
  it('OUT_OF_STOCK：把 SKU 換成商品名稱，不重新查商品', () => {
    const result = fail(409, 'OUT_OF_STOCK', 'out of stock: SKU-A, SKU-B.')

    expect(result.message).toBe('「運動鞋」、「帆布包」庫存不足，請調整數量或移除後再試')
    expect(result).toMatchObject({ refresh: false, uncertain: false })
  })

  it('OUT_OF_STOCK 只有一個商品、其他商品不出現', () => {
    expect(fail(409, 'OUT_OF_STOCK', 'out of stock: SKU-B.').message).toContain('「帆布包」')
    expect(fail(409, 'OUT_OF_STOCK', 'out of stock: SKU-B.').message).not.toContain('運動鞋')
  })

  it.each(['PRODUCT_NOT_AVAILABLE', 'PRODUCT_NOT_FOUND'])(
    '%s：把商品 id 換成名稱，要求重新查商品',
    (code) => {
      const result = fail(409, code, 'product is not available for purchase: id-2.')

      expect(result.message).toBe('「帆布包」已無法購買，請移除後再結帳')
      expect(result).toMatchObject({ refresh: true, uncertain: false })
    },
  )

  it('對不到商品（detail 格式不符、不認識的 SKU）：說「部分商品」，不顯示識別碼', () => {
    expect(fail(409, 'OUT_OF_STOCK', 'out of stock: SKU-ZZZ.').message).toBe(
      '部分商品庫存不足，請調整數量或移除後再試',
    )
    expect(fail(409, 'OUT_OF_STOCK', 'weird').message).toContain('部分商品')
    expect(fail(409, 'OUT_OF_STOCK', 'out of stock: SKU-ZZZ.').message).not.toContain('SKU-ZZZ')
  })

  it.each(['PRODUCT_SERVICE_UNAVAILABLE', 'INVENTORY_SERVICE_UNAVAILABLE', 'TOO_MANY_REQUESTS'])(
    '%s：訂單確定沒有建立，可以稍後再試',
    (code) => {
      const result = fail(503, code)

      expect(result.message).toContain('訂單尚未建立')
      expect(result.uncertain).toBe(false)
    },
  )

  it.each(['NETWORK_ERROR', 'TIMEOUT'])('%s（沒收到回應）：不確定訂單有沒有建立', (code) => {
    const result = fail(0, code)

    expect(result.uncertain).toBe(true)
    expect(result.message).toContain('我的訂單')
  })

  it('其他錯誤：顯示後端的 detail', () => {
    expect(fail(400, 'VALIDATION_FAILED', 'items: size must be between 1 and 50').message).toBe(
      'items: size must be between 1 and 50',
    )
  })
})
