// 畫面共用的格式化

// 價格：千分位，小數最多兩位（100 → 100、1234.5 → 1,234.5）
export function formatPrice(value: number): string {
  return value.toLocaleString('zh-TW', { maximumFractionDigits: 2 })
}

// 折扣百分比：有原價而且原價高於售價才有（80 / 100 → 20）；其餘回傳 null
// 至少 1：原價只比售價高一點點時，四捨五入成 0% 的折扣標籤沒有意義
export function discountPercent(price: number, originalPrice: number | null): number | null {
  if (originalPrice === null || originalPrice <= price || originalPrice <= 0) {
    return null
  }
  return Math.max(1, Math.round((1 - price / originalPrice) * 100))
}
