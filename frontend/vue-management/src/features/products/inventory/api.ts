import { ApiError } from '@/api/errors'
import apiClient from '@/api/http'

// 對應 inventory-service 的 StockResDto
export interface Stock {
  sku: string
  // 可賣的數量，不含已預扣的
  available: number
  // 已預扣、等待付款的數量
  reserved: number
  updatedAt: string
}

// SKU 放在路徑上，要編碼（SKU 可能有 / 之類的字元）
function path(sku: string): string {
  return `/inventory/${encodeURIComponent(sku)}`
}

// 還沒建立庫存時回 null（後端回 404 STOCK_NOT_FOUND）
export async function getStock(sku: string): Promise<Stock | null> {
  try {
    const { data } = await apiClient.get<Stock>(path(sku))
    return data
  } catch (error) {
    if (error instanceof ApiError && error.code === 'STOCK_NOT_FOUND') {
      return null
    }
    throw error
  }
}

// 直接設定可賣數量（盤點後）；不存在就建立。沒有版本檢查，以最後一次設定為準
export async function setStock(sku: string, available: number): Promise<Stock> {
  const { data } = await apiClient.put<Stock>(path(sku), { available })
  return data
}
