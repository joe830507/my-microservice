import { getStock, setStock, shipOrder } from './support/api'
import { adminSession, readE2eData } from './support/data'
import { expect, test } from './support/test'

// 計畫的主流程：瀏覽 → 加入購物車 → 註冊 → 結帳 → 付款成功 → 我的訂單看到已付款；
// 搭配後台出貨後，前台看到已出貨
const { product } = readE2eData()

test('瀏覽 → 加入購物車 → 註冊 → 結帳 → 付款成功 → 已付款 → 後台出貨 → 已出貨', async ({
  page,
  request,
}) => {
  const admin = adminSession(request)
  // 庫存是這個測試的觀察對象：先固定成已知的數量，結束時再看少了幾件
  await setStock(admin, 20)
  const email = `e2e-shopper-${Date.now()}@example.com`

  await test.step('瀏覽：搜尋 → 開商品詳情', async () => {
    await page.goto('/')
    await page.locator('input[type="search"]').fill('E2E 測試商品')
    await page.locator('input[type="search"]').press('Enter')
    await page.locator('.product-card', { hasText: product.name }).click()
    await expect(page).toHaveURL(new RegExp(`/p/${product.id}$`))
    await expect(page.locator('h1.detail__name')).toHaveText(product.name)
  })

  await test.step('加入購物車：2 件', async () => {
    await page.locator('[aria-label="增加數量"]').click()
    await page.locator('.detail__add').click()
    await expect(page.locator('.detail__added')).toContainText('已加入 2 件')
    await expect(page.locator('.store-nav__badge')).toHaveText('2')
  })

  await test.step('購物車：價格以最新查詢為準，總計 NT$ 1,600', async () => {
    await page.locator('.store-nav a[href="/cart"]').click()
    const line = page.locator('.cart-line')
    await expect(line).toContainText(product.name)
    await expect(line).toContainText('NT$ 800')
    await expect(page.locator('.cart-summary__total')).toContainText('NT$ 1,600')
  })

  await test.step('前往結帳：還沒登入，導到登入頁；從登入頁改去註冊', async () => {
    await page.getByRole('button', { name: '前往結帳' }).click()
    await expect(page).toHaveURL(/\/login\?redirect=/)
    expect(new URL(page.url()).searchParams.get('redirect')).toBe('/checkout')

    await page.locator('.auth-card').getByRole('link', { name: '註冊' }).click()
    await expect(page).toHaveURL(/\/register\?redirect=/)
  })

  await test.step('註冊：成功後直接登入，回到結帳頁', async () => {
    await page.locator('input[name="email"]').fill(email)
    await page.locator('input[name="password"]').fill('password123')
    await page.locator('input[name="displayName"]').fill('E2E 購物者')
    await page.getByRole('button', { name: '註冊' }).click()

    await expect(page).toHaveURL(/\/checkout$/)
    await expect(page.locator('.store-nav__user')).toHaveText('E2E 購物者')
  })

  await test.step('結帳：確認商品與總計，送出訂單', async () => {
    await expect(page.locator('.checkout-line')).toContainText(product.name)
    await expect(page.locator('.checkout-summary__total')).toContainText('NT$ 1,600')

    await page.getByRole('button', { name: '送出訂單' }).click()

    await expect(page).toHaveURL(/\/orders\/[0-9a-f-]{36}\/pay$/)
    // 建單成功：購物車已清空
    await expect(page.locator('.store-nav__badge')).toHaveCount(0)
  })

  const orderId = page.url().match(/\/orders\/([0-9a-f-]{36})\/pay/)![1]!

  await test.step('付款頁：顯示應付金額與倒數，按「付款成功」', async () => {
    await expect(page.locator('.pay__amount')).toContainText('NT$ 1,600')
    await expect(page.locator('.pay__deadline')).toContainText(/\d\d:\d\d/)

    await page.getByRole('button', { name: '付款成功' }).click()

    // 付款通知 order-service 是非同步的，畫面輪詢到訂單變成 PAID
    await expect(page.getByRole('heading', { name: '付款完成' })).toBeVisible()
  })

  await test.step('我的訂單：看到已付款', async () => {
    await page.getByRole('link', { name: '查看我的訂單' }).click()
    await expect(page).toHaveURL(/\/orders$/)
    const card = page.locator('.orders-card').first()
    await expect(card.locator('.status-badge')).toHaveText('已付款')
    await expect(card).toContainText('NT$ 1,600')
  })

  await test.step('庫存扣了 2 件', async () => {
    expect(await getStock(request)).toBe(18)
  })

  await test.step('後台出貨後，前台看到已出貨', async () => {
    await shipOrder(admin, orderId)

    await page.goto(`/orders/${orderId}`)
    await expect(page.locator('.od__head .status-badge')).toHaveText('已出貨')
    await expect(page.locator('.od__timeline')).toContainText('出貨')
    // 已經出貨：沒有取消、繼續付款的按鈕
    await expect(page.getByRole('button', { name: '取消訂單' })).toHaveCount(0)
  })
})
