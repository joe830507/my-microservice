import { ensureCustomer, login } from './support/api'
import { adminCredentials } from './support/env'
import { expect, test } from './support/test'
import { fillLogin, startSignedIn } from './support/ui'

test.describe('沒有登入', () => {
  test('進入後台頁面：導到登入頁，並帶 redirect', async ({ page }) => {
    await page.goto('/products?page=2')

    await expect(page).toHaveURL(/\/login\?redirect=/)
    expect(new URL(page.url()).searchParams.get('redirect')).toBe('/products?page=2')
    await expect(page.getByRole('heading', { name: '歡迎回來' })).toBeVisible()
  })

  test('帳密錯誤：顯示錯誤訊息，留在登入頁', async ({ page }) => {
    const { email } = adminCredentials()

    await fillLogin(page, email, 'definitely-wrong-password')

    await expect(page.getByRole('alert')).toContainText('Email 或密碼錯誤')
    await expect(page).toHaveURL(/\/login/)
  })

  test('管理員登入成功：回到原本要去的頁面', async ({ page }) => {
    const { email, password } = adminCredentials()
    await page.goto('/orders')
    await expect(page).toHaveURL(/\/login\?redirect=/)
    expect(new URL(page.url()).searchParams.get('redirect')).toBe('/orders')

    await page.locator('input[name="email"]').fill(email)
    await page.locator('input[name="password"]').fill(password)
    await page.getByRole('button', { name: '登入' }).click()

    await expect(page).toHaveURL(/\/orders$/)
    await expect(page.getByRole('menuitem', { name: '訂單管理' })).toBeVisible()
  })

  test('一般顧客登入：後台拒絕（沒有後台管理權限）', async ({ page, request }) => {
    // 固定一個顧客帳號，不會每次執行都多一個
    await ensureCustomer(request, 'e2e-customer@example.com', 'password123')

    await fillLogin(page, 'e2e-customer@example.com', 'password123')

    await expect(page.getByRole('alert')).toContainText('此帳號沒有後台管理權限')
    await expect(page).toHaveURL(/\/login/)
  })
})

test.describe('已經登入', () => {
  test('重新整理後仍然登入（用 refresh token 恢復，不需要再登入）', async ({ page, request }) => {
    const { email, password } = adminCredentials()
    await startSignedIn(page, (await login(request, email, password)).refreshToken)
    await page.goto('/products')
    await expect(page.getByRole('menuitem', { name: '商品管理' })).toBeVisible()

    await page.reload()

    await expect(page).toHaveURL(/\/products$/)
    await expect(page.getByRole('menuitem', { name: '商品管理' })).toBeVisible()
  })
})
