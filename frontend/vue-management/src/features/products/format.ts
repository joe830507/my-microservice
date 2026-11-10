// 價格顯示：千分位，小數最多兩位（100 → 100、1234.5 → 1,234.5）
export function formatPrice(value: number): string {
  return value.toLocaleString('zh-TW', { maximumFractionDigits: 2 })
}
