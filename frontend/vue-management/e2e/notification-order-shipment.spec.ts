import {
  ensureE2eCategory,
  get,
  login,
  post,
  productBySku,
  put,
} from './support/api'
import { adminCredentials } from './support/env'
import { expect, test } from './support/test'
import { startSignedIn } from './support/ui'

test('管理員在後台出貨已付款訂單', async ({ page, request }) => {
  const { email, password } = adminCredentials()
  const admin = await login(request, email, password)
  await ensureE2eCategory(admin)
  let product = await productBySku(admin, 'E2E-SHOP-001')
  if (product.status !== 'ACTIVE') {
    product = await post<typeof product>(admin, `/products/${product.id}/publish`, {
      version: product.version,
    })
  }
  await put(admin, `/inventory/${product.sku}`, { available: 20 })

  const order = await post<{ id: string }>(admin, '/orders', {
    items: [{ productId: product.id, qty: 1 }],
  })
  const payment = await post<{ id: string }>(admin, '/payments', { orderId: order.id })
  await post(admin, `/payments/${payment.id}/mock-callback`, { result: 'SUCCESS' })

  let status = ''
  const deadline = Date.now() + 15_000
  while (Date.now() < deadline) {
    status = (await get<{ status: string }>(admin, `/orders/${order.id}`)).status
    if (status === 'PAID') break
    await new Promise((resolve) => setTimeout(resolve, 300))
  }
  expect(status).toBe('PAID')

  await startSignedIn(page, admin.refreshToken)
  await page.goto(`/orders/${order.id}`)
  await expect(page.locator('.status-tag')).toHaveText('已付款')
  await page.locator('.ship-button').click()
  await page.getByRole('button', { name: '確定' }).click()
  await expect(page.locator('.status-tag')).toHaveText('已出貨')
  await expect.poll(async () => (await get<{ status: string }>(admin, `/orders/${order.id}`)).status)
    .toBe('SHIPPED')
})
