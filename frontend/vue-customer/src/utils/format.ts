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

// 倒數時間：mm:ss（90 秒 → 01:30）；不足一秒進位，所以剩 0.4 秒顯示 00:01，到 0 才是真的到期
export function formatCountdown(ms: number): string {
  const totalSeconds = Math.max(0, Math.ceil(ms / 1000))
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
}

// 時間：本地時區、24 小時制；沒有值顯示「—」
export function formatDateTime(value: string | null | undefined): string {
  return value ? new Date(value).toLocaleString('zh-TW', { hour12: false }) : '—'
}
