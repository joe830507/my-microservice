import {
  ensureCustomer,
  login,
  publishE2eProduct,
  setStock,
  unpublishE2eProduct,
} from './support/api'
import { adminSession, readE2eData } from './support/data'
import { expect, test } from './support/test'
import { startSignedIn, startWithCart } from './support/ui'

// 購物車與結帳遇到「狀態在你不知道的時候變了」的情況：這兩個錯誤格式是對真實後端確認過的
const { product } = readE2eData()
const CUSTOMER = { email: 'e2e-customer@example.com', password: 'password123' }

test.describe('結帳與購物車的例外情況（已登入的固定顧客）', () => {
  test.beforeEach(async ({ page, request }) => {
    await ensureCustomer(request, CUSTOMER.email, CUSTOMER.password)
    // 自己登入拿 refresh token（單次使用，不能和其他測試共用）
    const session = await login(request, CUSTOMER.email, CUSTOMER.password)
    await startSignedIn(page, session.refreshToken)
    await startWithCart(page, [{ productId: product.id, qty: 2 }])
  })

  test('結帳時庫存不夠：指出是哪個商品，購物車不變，可以再試', async ({ page, request }) => {
    const admin = adminSession(request)
    await setStock(admin, 1) // 購物車裡要 2 件，只剩 1 件
    try {
      await page.goto('/checkout')
      await expect(page.locator('.checkout-line')).toContainText(product.name)

      await page.getByRole('button', { name: '送出訂單' }).click()

      const alert = page.getByRole('alert')
      await expect(alert).toContainText(product.name)
      await expect(alert).toContainText('庫存不足')
      // 留在結帳頁，購物車不變，按鈕可以再按
      await expect(page).toHaveURL(/\/checkout$/)
      await expect(page.locator('.store-nav__badge')).toHaveText('2')
      await expect(page.getByRole('button', { name: '送出訂單' })).toBeEnabled()
    } finally {
      await setStock(admin, 20)
    }
  })

  test('購物車裡的商品被下架：顯示「此商品已下架」，不能結帳（後端對非上架商品只回 id 與狀態）', async ({
    page,
    request,
  }) => {
    const admin = adminSession(request)
    await unpublishE2eProduct(admin)
    try {
      await page.goto('/cart')

      const line = page.locator('.cart-line')
      await expect(line).toContainText('此商品已下架')
      await expect(line).toContainText('無法購買')
      // 不洩漏下架商品的名稱與價格
      await expect(line).not.toContainText(product.name)
      await expect(page.locator('.cart-summary__total')).toContainText('NT$ 0')
      await expect(page.getByRole('button', { name: '前往結帳' })).toBeDisabled()

      await page.getByRole('button', { name: '全部移除' }).click()
      await expect(page.getByText('購物車是空的')).toBeVisible()
    } finally {
      await publishE2eProduct(admin)
    }
  })
})
