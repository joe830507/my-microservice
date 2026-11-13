import { readE2eData } from './support/data'
import { expect, test } from './support/test'
import { hasNoHorizontalScroll, imageLoaded } from './support/ui'

// 不需要登入就能逛：搜尋、分類、商品詳情、加入購物車
const { product } = readE2eData()

test.describe('瀏覽商品（沒有登入）', () => {
  test('搜尋：找到 E2E 商品，卡片顯示名稱、價格、折扣與主圖', async ({ page }) => {
    await page.goto('/')
    await page.locator('input[type="search"]').fill('E2E 測試商品')
    const searchRequest = page.waitForRequest((request) => {
      const url = new URL(request.url())
      return url.pathname.endsWith('/products') && url.searchParams.has('keyword')
    })
    await page.locator('input[type="search"]').press('Enter')
    const searchUrl = new URL((await searchRequest).url())
    expect(searchUrl.searchParams.get('sortBy')).toBe('relevance')
    expect(searchUrl.searchParams.has('sortDirection')).toBe(false)
    await expect(page.locator('select').first()).toHaveValue('relevance')

    await expect(page).toHaveURL(/\/search\?q=/)
    await expect(page.getByRole('heading', { name: /搜尋「E2E 測試商品」/ })).toBeVisible()
    const card = page.locator('.product-card', { hasText: product.name })
    await expect(card).toBeVisible()
    await expect(card).toContainText('NT$ 800')
    await expect(card).toContainText('NT$ 1,000')
    await expect(card).toContainText('-20%')
    await expect.poll(() => imageLoaded(page, '.product-card img')).toBe(true)
  })

  test('分類頁：用 slug 找到分類，列出該分類的商品', async ({ page }) => {
    await page.goto('/c/e2e-playwright')

    await expect(page.getByRole('heading', { name: 'E2E Playwright 分類' })).toBeVisible()
    await expect(page.locator('.product-card', { hasText: product.name })).toBeVisible()
  })

  test('分類不存在、商品不存在：顯示 404', async ({ page }) => {
    await page.goto('/c/no-such-category')
    await expect(page.getByRole('heading', { name: '404' })).toBeVisible()

    await page.goto('/p/00000000-0000-4000-8000-000000000000')
    await expect(page.getByRole('heading', { name: '404' })).toBeVisible()
  })

  test('商品詳情：資訊、庫存、數量限制；加入購物車後件數會更新，重新整理仍在', async ({ page }) => {
    await page.goto(`/p/${product.id}`)

    await expect(page.locator('h1.detail__name')).toHaveText(product.name)
    await expect(page.locator('.detail__price')).toContainText('NT$ 800')
    await expect(page.locator('.detail__stock')).toHaveText('尚有庫存')
    await expect.poll(() => imageLoaded(page, '.gallery__main img')).toBe(true)

    // 數量輸入超過上限會被拉回範圍內（庫存 20）
    const quantity = page.locator('input[aria-label="數量"]')
    await quantity.fill('500')
    await quantity.blur()
    await expect(quantity).toHaveValue('20')
    await quantity.fill('3')
    await quantity.blur()

    await page.locator('.detail__add').click()

    await expect(page.locator('.detail__added')).toContainText('已加入 3 件')
    await expect(page.locator('.store-nav__badge')).toHaveText('3')

    await page.reload()
    await expect(page.locator('.store-nav__badge')).toHaveText('3')
  })

  test('手機寬度（390px）：頁面沒有水平捲軸，商品排成兩欄', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 800 })

    await page.goto('/')
    expect(await hasNoHorizontalScroll(page)).toBe(true)

    await page.goto('/search?q=E2E')
    const cards = page.locator('.listing__grid li')
    await expect(cards.first()).toBeVisible()
    expect(await hasNoHorizontalScroll(page)).toBe(true)

    await page.goto(`/p/${product.id}`)
    await expect(page.locator('h1.detail__name')).toBeVisible()
    expect(await hasNoHorizontalScroll(page)).toBe(true)

    await page.goto('/login')
    expect(await hasNoHorizontalScroll(page)).toBe(true)
  })
})
