import { describe, expect, it } from 'vitest'
import type { BatchProduct } from '../api'
import { buildLines, cartTotal, lineName, lineSubtotal } from '../lines'

function batch(id: string, price: number, status = 'ACTIVE'): BatchProduct {
  return { id, sku: `SKU-${id}`, name: `商品 ${id}`, price, status }
}

describe('buildLines', () => {
  it('依購物車的順序，配上最新的商品資料', () => {
    const lines = buildLines(
      [
        { productId: 'b', qty: 1 },
        { productId: 'a', qty: 2 },
      ],
      [batch('a', 10), batch('b', 20)],
    )

    expect(lines.map((line) => [line.productId, line.qty, line.product?.price])).toEqual([
      ['b', 1, 20],
      ['a', 2, 10],
    ])
    expect(lines.every((line) => line.purchasable)).toBe(true)
  })

  it.each([
    ['草稿', 'DRAFT'],
    ['已下架', 'INACTIVE'],
    ['已封存', 'ARCHIVED'],
  ])('%s：無法購買', (_, status) => {
    const [line] = buildLines([{ productId: 'a', qty: 1 }], [batch('a', 10, status)])

    expect(line?.purchasable).toBe(false)
    expect(line?.product).not.toBeNull()
  })

  it('查不到的商品（已被刪除）：無法購買，product 是 null', () => {
    const [line] = buildLines([{ productId: 'gone', qty: 1 }], [])

    expect(line).toMatchObject({ product: null, purchasable: false })
  })
})

// 後端對非上架中的商品只回 id 與 status，sku、name、price 是 null
const statusOnly = (id: string, status: string): BatchProduct => ({
  id,
  sku: null,
  name: null,
  price: null,
  status,
})

describe('非上架中的商品只有 id 與 status', () => {
  it('無法購買，小計是 0，不計入總計，名稱顯示「此商品已下架」', () => {
    const lines = buildLines(
      [
        { productId: 'ok', qty: 2 },
        { productId: 'off', qty: 5 },
        { productId: 'gone', qty: 1 },
      ],
      [batch('ok', 100), statusOnly('off', 'DRAFT')],
    )

    expect(lines[1]).toMatchObject({ purchasable: false })
    expect(lineSubtotal(lines[1]!)).toBe(0)
    expect(cartTotal(lines)).toBe(200)
    expect(lineName(lines[0]!)).toBe('商品 ok')
    expect(lineName(lines[1]!)).toBe('此商品已下架')
    expect(lineName(lines[2]!)).toBe('商品已不存在')
  })
})

describe('金額', () => {
  it('小計：單價 × 數量；查不到的商品是 0', () => {
    const [a, gone] = buildLines(
      [
        { productId: 'a', qty: 3 },
        { productId: 'gone', qty: 2 },
      ],
      [batch('a', 12.5)],
    )

    expect(lineSubtotal(a!)).toBe(37.5)
    expect(lineSubtotal(gone!)).toBe(0)
  })

  it('總計只算可購買的商品', () => {
    const lines = buildLines(
      [
        { productId: 'a', qty: 2 },
        { productId: 'b', qty: 5 },
        { productId: 'gone', qty: 1 },
      ],
      [batch('a', 100), batch('b', 999, 'INACTIVE')],
    )

    expect(cartTotal(lines)).toBe(200)
  })

  it('浮點誤差：0.1 × 3 是 0.3，不是 0.30000000000000004', () => {
    const lines = buildLines([{ productId: 'a', qty: 3 }], [batch('a', 0.1)])

    expect(lineSubtotal(lines[0]!)).toBe(0.3)
    expect(cartTotal(lines)).toBe(0.3)
  })

  it('空的購物車：0', () => {
    expect(cartTotal([])).toBe(0)
  })
})
