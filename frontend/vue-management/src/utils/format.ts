// 畫面共用的格式化

// 價格：千分位，小數最多兩位（100 → 100、1234.5 → 1,234.5）
export function formatPrice(value: number): string {
  return value.toLocaleString('zh-TW', { maximumFractionDigits: 2 })
}

// 時間：本地時區、24 小時制；沒有值顯示「—」
export function formatDateTime(value: string | null | undefined): string {
  return value ? new Date(value).toLocaleString('zh-TW', { hour12: false }) : '—'
}
