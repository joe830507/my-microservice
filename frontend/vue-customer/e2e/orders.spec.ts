import { createOrder, ensureCustomer, getStock, login, setStock } from './support/api'
import { adminSession, readE2eData } from './support/data'
import { expect, test } from './support/test'
import { startSignedIn } from './support/ui'

const { product } = readE2eData()
const CUSTOMER = { email: 'e2e-customer@example.com', password: 'password123' }

test('我的訂單：待付款的訂單可以在畫面上取消，取消後庫存釋放', async ({ page, request }) => {
  const admin = adminSession(request)
  await ensureCustomer(request, CUSTOMER.email, CUSTOMER.password)
  const customer = await login(request, CUSTOMER.email, CUSTOMER.password)
  await setStock(admin, 20)

  // 用 API 建一張待付款的訂單（庫存預扣 3 件）；測試的對象是畫面上的取消流程
  const order = await createOrder(customer, product.id, 3)
  expect(order.status).toBe('PENDING_PAYMENT')
  expect(await getStock(request)).toBe(17)

  await startSignedIn(page, customer.refreshToken)

  await test.step('列表：這張訂單是待付款，有「去付款」', async () => {
    await page.goto('/orders?status=PENDING_PAYMENT')
    const card = page.locator('.orders-item', {
      has: page.locator(`a[href="/orders/${order.id}"]`),
    })
    await expect(card.locator('.status-badge')).toHaveText('待付款')
    await expect(card.getByRole('link', { name: '去付款' })).toHaveAttribute(
      'href',
      `/orders/${order.id}/pay`,
    )
  })

  await test.step('詳情：商品快照、剩餘付款時間，取消前要先確認', async () => {
    await page.goto(`/orders/${order.id}`)
    await expect(page.locator('.od__item')).toContainText(product.name)
    await expect(page.locator('.od__item')).toContainText('NT$ 800 × 3')
    await expect(page.locator('.od__countdown')).toContainText(/\d\d:\d\d/)

    await page.getByRole('button', { name: '取消訂單' }).click()
    await expect(page.getByRole('alertdialog')).toContainText('確定要取消這張訂單嗎')
    // 返回：不取消
    await page.getByRole('alertdialog').getByRole('button', { name: '返回' }).click()
    await expect(page.locator('.od__head .status-badge')).toHaveText('待付款')
  })

  await test.step('確定取消：訂單已取消，沒有付款相關按鈕', async () => {
    await page.getByRole('button', { name: '取消訂單' }).click()
    await page.getByRole('alertdialog').getByRole('button', { name: '確定取消' }).click()

    await expect(page.locator('.od__head .status-badge')).toHaveText('已取消')
    await expect(page.getByRole('status')).toContainText('訂單已取消')
    await expect(page.getByRole('link', { name: '繼續付款' })).toHaveCount(0)
  })

  await test.step('庫存釋放回來', async () => {
    expect(await getStock(request)).toBe(20)
  })
})

test('別人的訂單 id：顯示找不到（後端回 404，不透露存在與否）', async ({ page, request }) => {
  await ensureCustomer(request, CUSTOMER.email, CUSTOMER.password)
  const customer = await login(request, CUSTOMER.email, CUSTOMER.password)
  await startSignedIn(page, customer.refreshToken)

  await page.goto('/orders/00000000-0000-4000-8000-0000000000ff')

  await expect(page.getByRole('heading', { name: '404' })).toBeVisible()
})
