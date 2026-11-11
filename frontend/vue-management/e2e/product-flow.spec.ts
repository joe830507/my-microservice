import {
  deactivateE2eCategory,
  E2E_CATEGORY,
  ensureE2eCategory,
  login,
  productBySku,
  unpublishIfActive,
  type Session,
} from './support/api'
import { makePng } from './support/png'
import { adminCredentials } from './support/env'
import { expect, test } from './support/test'
import { startSignedIn } from './support/ui'

// 計畫的主流程：登入（登入畫面由 auth.spec 涵蓋）→ 建立商品 → 上傳主圖 → 上架（全部在瀏覽器裡操作，再用 API 確認後端的結果）
test.describe.configure({ mode: 'serial' })

let admin: Session
const stamp = Date.now()
const sku = `E2E-UI-${stamp}`
const name = `E2E 自動化測試商品 ${stamp}`

test.beforeAll(async ({ playwright, baseURL }) => {
  // 登入一次：access token 給 API 準備與清理資料用，refresh token 給瀏覽器用（單次使用，不能共用）
  const { email, password } = adminCredentials()
  admin = await login(await playwright.request.newContext({ baseURL }), email, password)
  await ensureE2eCategory(admin)
})

test.afterAll(async () => {
  // 商品沒有刪除 API：下架（不是封存），商店看不到；分類停用，商店的分類列也不會出現
  await unpublishIfActive(admin, sku).catch(() => {})
  await deactivateE2eCategory(admin).catch(() => {})
  await admin.request.dispose()
})

test('登入 → 建立商品 → 上傳主圖 → 上架', async ({ page, request }) => {
  await test.step('已登入（把登入拿到的 refresh token 放進瀏覽器；登入畫面本身由 auth.spec 涵蓋）', async () => {
    await startSignedIn(page, admin.refreshToken)
    await page.goto('/products')
    await expect(page.getByRole('menuitem', { name: '商品管理' })).toBeVisible()
  })

  await test.step('建立商品（草稿）', async () => {
    await page.goto('/products/new')
    // 等分類等資料載入完成再填：頁面載入資料後會重設表單，太早填的欄位會被清掉（真人來不及打字，測試才會遇到）
    await page.waitForLoadState('networkidle')
    await page.getByLabel('SKU').fill(sku)
    await expect(page.getByLabel('SKU')).toHaveValue(sku)
    await page.getByLabel('名稱').fill(name)

    await page
      .locator('.el-form-item')
      .filter({ hasText: '分類' })
      .first()
      .locator('.el-select__wrapper')
      .click()
    await page.getByRole('treeitem', { name: E2E_CATEGORY.name }).click()

    await page.getByLabel('品牌').fill('E2E')
    await page.locator('.el-form-item').filter({ hasText: '售價' }).locator('input').fill('499')
    await page.locator('.el-form-item').filter({ hasText: '原價' }).locator('input').fill('599')
    await page.getByLabel('說明').fill('這是自動化測試建立的商品，可以忽略。')
    await page.getByRole('button', { name: '新增', exact: true }).click()

    await expect(page.getByText('商品已新增')).toBeVisible()
    await expect(page).toHaveURL(/\/products\/[0-9a-f-]{36}$/)
    await expect(page.locator('.status-tag')).toHaveText('草稿')
  })

  await test.step('還沒有主圖就上架：被擋，狀態不變', async () => {
    await page.locator('.publish-button').click()

    await expect(page.getByText('上架前要先設定主圖').first()).toBeVisible()
    await expect(page.locator('.status-tag')).toHaveText('草稿')
  })

  await test.step('上傳主圖（瀏覽器直接上傳到物件儲存）', async () => {
    await page.locator('input.file-input').setInputFiles({
      name: 'e2e-main.png',
      mimeType: 'image/png',
      buffer: makePng(320, 320, [232, 89, 12]),
    })

    await expect(page.getByText('圖片已上傳')).toBeVisible()
    const card = page.locator('.image-card').first()
    await expect(card).toContainText('主圖')
    // 圖片真的載入了（網址指向物件儲存，對著 nginx 跑時會經過 CSP 的 img-src）
    await expect
      .poll(() =>
        card
          .locator('img')
          .evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth > 0),
      )
      .toBe(true)
  })

  await test.step('上架', async () => {
    await page.locator('.publish-button').click()

    await expect(page.getByText('商品已上架')).toBeVisible()
    await expect(page.locator('.status-tag')).toHaveText('上架中')
  })

  await test.step('後端確認：商品上架中，商店（匿名）看得到', async () => {
    const stored = await productBySku(admin, sku)
    expect(stored.status).toBe('ACTIVE')

    const publicView = await request.get(`/api/v1/products/sku/${sku}`)
    expect(publicView.status()).toBe(200)
    expect((await publicView.json()).name).toBe(name)

    const list = await request.get(`/api/v1/products?keyword=${encodeURIComponent(String(stamp))}`)
    const items = (await list.json()).items as { sku: string; primaryImageUrl: string | null }[]
    expect(items.find((item) => item.sku === sku)?.primaryImageUrl).toBeTruthy()
  })

  await test.step('下架後商店看不到', async () => {
    await page.locator('.unpublish-button').click()
    await expect(page.locator('.status-tag')).toHaveText('已下架')

    const publicView = await request.get(`/api/v1/products/sku/${sku}`)
    expect(publicView.status()).toBe(404)
  })
})
