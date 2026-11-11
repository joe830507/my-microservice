import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname } from 'node:path'
import { request as playwrightRequest, type FullConfig } from '@playwright/test'
import {
  deactivateE2eCategory,
  E2E_PRODUCT,
  ensureE2eCategory,
  ensureE2eProduct,
  login,
  unpublishE2eProduct,
} from './support/api'
import { E2E_DATA_FILE, type E2eData } from './support/data'
import { adminCredentials } from './support/env'

// 每次執行前：確保 E2E 專用的分類與商品存在、上架、有庫存；執行後：商品下架、分類停用（商店看不到）
// 登入管理員只做一次（Gateway 的登入限流依來源 IP，約每分鐘 10 次）
export default async function globalSetup(config: FullConfig): Promise<() => Promise<void>> {
  const baseURL = config.projects[0]?.use.baseURL
  const request = await playwrightRequest.newContext({ baseURL })
  const { email, password } = adminCredentials()
  const admin = await login(request, email, password)

  const category = await ensureE2eCategory(admin)
  const product = await ensureE2eProduct(admin, category.id)

  const data: E2eData = {
    adminToken: admin.token,
    product: {
      id: product.id,
      sku: product.sku,
      name: product.name,
      price: product.price,
      originalPrice: product.originalPrice,
    },
  }
  mkdirSync(dirname(E2E_DATA_FILE), { recursive: true })
  writeFileSync(E2E_DATA_FILE, JSON.stringify(data))
  console.log(`E2E 專用商品：${E2E_PRODUCT.sku}（上架中，庫存 ${E2E_PRODUCT.stock}）`)

  return async () => {
    await unpublishE2eProduct(admin).catch((error) => console.warn('下架 E2E 商品失敗：', error))
    await deactivateE2eCategory(admin).catch((error) => console.warn('停用 E2E 分類失敗：', error))
    await request.dispose()
  }
}
