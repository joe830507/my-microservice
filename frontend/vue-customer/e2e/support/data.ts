import { readFileSync } from 'node:fs'
import type { APIRequestContext } from '@playwright/test'
import type { Session } from './api'

// global-setup 準備好的資料：管理員的 access token（15 分鐘有效，一次執行綽綽有餘）與 E2E 專用商品
export const E2E_DATA_FILE = 'e2e/.auth/e2e-data.json'

export interface E2eData {
  adminToken: string
  product: { id: string; sku: string; name: string; price: number; originalPrice: number | null }
}

export function readE2eData(): E2eData {
  return JSON.parse(readFileSync(E2E_DATA_FILE, 'utf8')) as E2eData
}

// 管理員 Session（只有 API 用；測試不用瀏覽器登入後台）
export function adminSession(request: APIRequestContext): Session {
  return { request, token: readE2eData().adminToken, refreshToken: '' }
}
