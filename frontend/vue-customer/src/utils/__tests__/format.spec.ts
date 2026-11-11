import { describe, expect, it } from 'vitest'
import { discountPercent, formatPrice } from '../format'

describe('formatPrice', () => {
  it.each([
    [100, '100'],
    [1234.5, '1,234.5'],
    [1234567, '1,234,567'],
    [0.456, '0.46'],
  ])('%s → %s', (value, expected) => {
    expect(formatPrice(value)).toBe(expected)
  })
})

describe('discountPercent', () => {
  it('有原價且高於售價：折扣百分比（四捨五入）', () => {
    expect(discountPercent(80, 100)).toBe(20)
    expect(discountPercent(1, 3)).toBe(67)
  })

  it('原價只高一點點：至少 1%', () => {
    expect(discountPercent(999, 1000)).toBe(1)
    expect(discountPercent(99.9, 100)).toBe(1)
  })

  it.each([
    ['沒有原價', 100, null],
    ['原價等於售價', 100, 100],
    ['原價低於售價', 100, 80],
    ['原價為 0', 0, 0],
  ])('%s：沒有折扣', (_, price, original) => {
    expect(discountPercent(price, original)).toBeNull()
  })
})
