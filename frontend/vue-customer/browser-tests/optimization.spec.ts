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

