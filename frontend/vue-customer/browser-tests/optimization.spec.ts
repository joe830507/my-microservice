import { expect, test } from '@playwright/test'

const id = '00000000-0000-4000-8000-000000000001'
const product = {
  id,
  sku: 'SKU',
  name: 'Shoe',
  price: 100,
  originalPrice: null,
  brand: null,
  description: null,
  categoryId: null,
  status: 'ACTIVE',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

test.beforeEach(async ({ page }) => {
  await page.route('**/api/v1/categories/tree', (route) => route.fulfill({ json: { roots: [] } }))
  await page.route('**/api/v1/products?*', (route) =>
    route.fulfill({ json: { items: [], page: 0, size: 12, totalElements: 0, totalPages: 0 } }),
  )
})

test('search defaults to relevance and clearing the keyword restores latest', async ({ page }) => {
  const request = page.waitForRequest((r) => r.url().includes('/api/v1/products?'))
  await page.goto('/search?q=shoe')
  const url = new URL((await request).url())
  expect(url.searchParams.get('sortBy')).toBe('relevance')
  expect(url.searchParams.has('sortDirection')).toBe(false)
  await expect(page.locator('#sort')).toHaveValue('relevance')
  await page.goto('/search?sort=relevance')
  await expect(page.locator('#sort')).toHaveValue('latest')
  await expect(page.locator('#sort option[value="relevance"]')).toHaveCount(0)
})

test('cart updates across browser tabs without a reload', async ({ page, context }) => {
  await page.goto('/')
  const second = await context.newPage()
  await second.goto('/')
  await page.evaluate(() =>
    localStorage.setItem('vue-customer.cart', JSON.stringify([{ productId: 'other', qty: 3 }])),
  )
  await expect(second.locator('.store-nav__badge')).toHaveText('3')
  await page.evaluate(() => localStorage.removeItem('vue-customer.cart'))
  await expect(second.locator('.store-nav__badge')).toHaveCount(0)
})

test('images start before the product response completes', async ({ page }) => {
  let release!: () => void
  const gate = new Promise<void>((resolve) => {
    release = resolve
  })
  let imageRequested = false
  await page.route(`**/api/v1/products/${id}`, async (route) => {
    await gate
    await route.fulfill({ json: product })
  })
  await page.route(`**/api/v1/products/${id}/images`, (route) => {
    imageRequested = true
    return route.fulfill({ json: { productId: id, images: [] } })
  })
  await page.route('**/api/v1/inventory/SKU', (route) =>
    route.fulfill({ json: { sku: 'SKU', available: 2, reserved: 0, updatedAt: 'now' } }),
  )
  await page.goto(`/p/${id}`)
  await expect.poll(() => imageRequested).toBe(true)
  release()
  await expect(page.locator('h1.detail__name')).toHaveText('Shoe')
})

test('user logout clears the cart in this tab and another open tab', async ({ page, context }) => {
  await context.route('**/api/v1/categories/tree', (route) =>
    route.fulfill({ json: { roots: [] } }),
  )
  await context.route('**/api/v1/products?*', (route) =>
    route.fulfill({ json: { items: [], page: 0, size: 12, totalElements: 0, totalPages: 0 } }),
  )
  await page.route('**/api/v1/auth/login', (route) =>
    route.fulfill({ json: { accessToken: 'access-1', tokenType: 'Bearer', expiresIn: 900 } }),
  )
  await context.route('**/api/v1/auth/refresh', (route) =>
    route.fulfill({ json: { accessToken: 'access-2', tokenType: 'Bearer', expiresIn: 900 } }),
  )
  await context.route('**/api/v1/users/me', (route) =>
    route.fulfill({
      json: {
        id,
        email: 'customer@example.com',
        displayName: '顧客',
        roles: ['CUSTOMER'],
        status: 'ACTIVE',
      },
    }),
  )
  await page.route('**/api/v1/auth/logout', (route) => route.fulfill({ status: 204 }))
  await page.route(`**/api/v1/products/${id}`, (route) => route.fulfill({ json: product }))
  await page.route(`**/api/v1/products/${id}/images`, (route) =>
    route.fulfill({ json: { productId: id, images: [] } }),
  )
  await page.route('**/api/v1/inventory/SKU', (route) =>
    route.fulfill({ json: { sku: 'SKU', available: 2, reserved: 0, updatedAt: 'now' } }),
  )
  await page.goto(`/login?redirect=/p/${id}`)
  await page.getByLabel('Email').fill('customer@example.com')
  await page.getByLabel('密碼').fill('password123')
  await page.getByRole('button', { name: '登入', exact: true }).click()
  await expect(page.locator('.store-nav__logout')).toBeVisible()
  await page.getByRole('button', { name: '加入購物車' }).click()
  await expect(page.locator('.store-nav__badge')).toHaveText('1')
  const second = await context.newPage()
  await second.goto('/search')
  await expect(second.locator('.store-nav__badge')).toHaveText('1')
  await page.getByRole('button', { name: '登出', exact: true }).click()
  await expect(page.locator('.store-nav__badge')).toHaveCount(0)
  await expect(second.locator('.store-nav__badge')).toHaveCount(0)
  expect(await page.evaluate(() => localStorage.getItem('vue-customer.cart'))).toBeNull()
})
