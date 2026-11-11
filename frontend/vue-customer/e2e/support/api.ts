import type { APIRequestContext } from '@playwright/test'
import { makePng } from './png'

// 直接呼叫後端 API 準備與清理測試資料（經過 baseURL 的 /api/v1，和瀏覽器走同一條路）
const API = '/api/v1'

export interface Session {
  request: APIRequestContext
  // access token：給 API 呼叫用
  token: string
  // refresh token：後端放在 HttpOnly cookie，這裡從 Set-Cookie 取出，給瀏覽器用（見 ui.ts 的 startSignedIn）
  refreshToken: string
}

async function json<T>(
  response: { ok(): boolean; status(): number; text(): Promise<string>; json(): Promise<T> },
  what: string,
): Promise<T> {
  if (!response.ok()) {
    throw new Error(`${what} 失敗：HTTP ${response.status()} ${await response.text()}`)
  }
  return response.json()
}

// Gateway 的登入限流依來源 IP（約每分鐘 10 次，每 6 秒補 1 次）：被擋（429）時等一下再試，不要直接讓測試失敗
export async function login(
  request: APIRequestContext,
  email: string,
  password: string,
): Promise<Session> {
  for (let attempt = 0; ; attempt++) {
    const response = await request.post(`${API}/auth/login`, { data: { email, password } })
    if (response.status() === 429 && attempt < 5) {
      await new Promise((resolve) => setTimeout(resolve, 7_000))
      continue
    }
    const body = await json<{ accessToken: string }>(response, `登入 ${email}`)
    const cookie = /(?:^|\n)refresh_token=([^;\n]+)/.exec(response.headers()['set-cookie'] ?? '')
    if (!cookie) {
      throw new Error(`登入 ${email} 的回應沒有 refresh_token cookie`)
    }
    return { request, token: body.accessToken, refreshToken: cookie[1] }
  }
}

function auth(session: Session): { headers: { Authorization: string } } {
  return { headers: { Authorization: `Bearer ${session.token}` } }
}

export async function get<T>(session: Session, path: string): Promise<T> {
  return json<T>(await session.request.get(`${API}${path}`, auth(session)), `GET ${path}`)
}

export async function post<T>(session: Session, path: string, data?: unknown): Promise<T> {
  return json<T>(
    await session.request.post(`${API}${path}`, { ...auth(session), data }),
    `POST ${path}`,
  )
}

export async function put<T>(session: Session, path: string, data: unknown): Promise<T> {
  return json<T>(
    await session.request.put(`${API}${path}`, { ...auth(session), data }),
    `PUT ${path}`,
  )
}

interface CategoryRow {
  id: string
  parentId: string | null
  name: string
  slug: string
  description: string | null
  sortOrder: number
  active: boolean
  version: number
}

// E2E 專用的分類：每次執行共用同一個，不會越建越多。測試開始時確保它存在且啟用，結束後停用，
// 商店的分類列就不會出現這個分類（商品沒有刪除 API，所以盡量不要留下東西）
export const E2E_CATEGORY = { slug: 'e2e-playwright', name: 'E2E Playwright 分類' }

export async function ensureE2eCategory(admin: Session): Promise<CategoryRow> {
  const { categories } = await get<{ categories: CategoryRow[] }>(admin, '/categories')
  const existing = categories.find((c) => c.slug === E2E_CATEGORY.slug)
  const body = {
    parentId: null,
    name: E2E_CATEGORY.name,
    slug: E2E_CATEGORY.slug,
    description: '自動化測試用，執行完會停用',
    sortOrder: 99,
    active: true,
  }
  if (!existing) {
    return post<CategoryRow>(admin, '/categories', body)
  }
  return existing.active
    ? existing
    : put<CategoryRow>(admin, `/categories/${existing.id}`, { ...body, version: existing.version })
}

export async function deactivateE2eCategory(admin: Session): Promise<void> {
  const { categories } = await get<{ categories: CategoryRow[] }>(admin, '/categories')
  const existing = categories.find((c) => c.slug === E2E_CATEGORY.slug)
  if (existing?.active) {
    await put(admin, `/categories/${existing.id}`, {
      parentId: existing.parentId,
      name: existing.name,
      slug: existing.slug,
      description: existing.description,
      sortOrder: existing.sortOrder,
      active: false,
      version: existing.version,
    })
  }
}

interface ProductRow {
  id: string
  sku: string
  name: string
  status: string
  price: number
  originalPrice: number | null
  version: number
}

// E2E 專用的商品：固定 SKU，每次執行共用同一件，不會越建越多。
// 結束時「下架」（不是封存，封存不可逆），商店看不到，下次執行再上架
export const E2E_PRODUCT = {
  sku: 'E2E-SHOP-001',
  name: 'E2E 測試商品（自動化）',
  price: 800,
  originalPrice: 1000,
  stock: 20,
}

async function productBySku(session: Session, sku: string): Promise<ProductRow | null> {
  const response = await session.request.get(
    `${API}/products/sku/${encodeURIComponent(sku)}`,
    auth(session),
  )
  if (response.status() === 404) return null
  return json<ProductRow>(response, `GET 商品 ${sku}`)
}

export async function ensureE2eProduct(admin: Session, categoryId: string): Promise<ProductRow> {
  let product = await productBySku(admin, E2E_PRODUCT.sku)
  if (!product) {
    product = await post<ProductRow>(admin, '/products', {
      sku: E2E_PRODUCT.sku,
      name: E2E_PRODUCT.name,
      description: '這是自動化測試用的商品，可以忽略。\n第二行說明。',
      categoryId,
      brand: 'E2E',
      price: E2E_PRODUCT.price,
      originalPrice: E2E_PRODUCT.originalPrice,
    })
  }
  if (product.status === 'ARCHIVED') {
    throw new Error(`${E2E_PRODUCT.sku} 已封存，不能再使用：請換一個 SKU（E2E_PRODUCT.sku）`)
  }

  // 主圖：上架一定要有
  const { images } = await get<{ images: unknown[] }>(admin, `/products/${product.id}/images`)
  if (images.length === 0) {
    const png = makePng(320, 320, [232, 89, 12])
    const upload = await post<{
      uploadUrl: string
      objectKey: string
      requiredHeaders: Record<string, string>
    }>(admin, `/products/${product.id}/images/upload-url`, {
      contentType: 'image/png',
      contentLength: png.length,
    })
    // Content-Length 由 HTTP 客戶端依本體自動帶上，不能手動設
    const headers = Object.fromEntries(
      Object.entries(upload.requiredHeaders).filter(
        ([name]) => !['content-length', 'host'].includes(name.toLowerCase()),
      ),
    )
    const stored = await admin.request.put(upload.uploadUrl, { headers, data: png })
    if (!stored.ok()) {
      throw new Error(`上傳商品圖片到物件儲存失敗：HTTP ${stored.status()}`)
    }
    await post(admin, `/products/${product.id}/images`, {
      objectKey: upload.objectKey,
      altText: 'E2E 測試商品',
    })
  }

  if (product.status !== 'ACTIVE') {
    const fresh = (await productBySku(admin, E2E_PRODUCT.sku))!
    await post(admin, `/products/${product.id}/publish`, { version: fresh.version })
  }
  await setStock(admin, E2E_PRODUCT.stock)
  return (await productBySku(admin, E2E_PRODUCT.sku))!
}

export async function setStock(admin: Session, available: number): Promise<void> {
  await put(admin, `/inventory/${E2E_PRODUCT.sku}`, { available })
}

export async function getStock(request: APIRequestContext): Promise<number> {
  const response = await request.get(`${API}/inventory/${E2E_PRODUCT.sku}`)
  return (await json<{ available: number }>(response, '查庫存')).available
}

export async function unpublishE2eProduct(admin: Session): Promise<void> {
  const product = await productBySku(admin, E2E_PRODUCT.sku)
  if (product?.status === 'ACTIVE') {
    await post(admin, `/products/${product.id}/unpublish`, { version: product.version })
  }
}

export async function publishE2eProduct(admin: Session): Promise<void> {
  const product = await productBySku(admin, E2E_PRODUCT.sku)
  if (product && product.status !== 'ACTIVE') {
    await post(admin, `/products/${product.id}/publish`, { version: product.version })
  }
}

// 後台出貨（PAID → SHIPPED）
export async function shipOrder(admin: Session, orderId: string): Promise<void> {
  await post(admin, `/orders/${orderId}/ship`)
}

// 固定的一般顧客帳號（給不需要走註冊畫面的測試用，不會越建越多）；已經註冊過回 409，當成成功
export async function ensureCustomer(
  request: APIRequestContext,
  email: string,
  password: string,
): Promise<void> {
  // 已經有這個帳號就不再註冊：Gateway 的註冊限流很嚴（每分鐘約 3 次，SEC-04），固定顧客只需要註冊一次
  const existing = await request.post(`${API}/auth/login`, { data: { email, password } })
  if (existing.ok()) {
    return
  }
  for (let attempt = 0; ; attempt++) {
    const response = await request.post(`${API}/auth/register`, {
      data: { email, password, displayName: 'E2E 顧客' },
    })
    if (response.status() === 429 && attempt < 5) {
      await new Promise((resolve) => setTimeout(resolve, 21_000))
      continue
    }
    if (!response.ok() && response.status() !== 409) {
      throw new Error(`註冊 ${email} 失敗：HTTP ${response.status()} ${await response.text()}`)
    }
    return
  }
}

// 用 API 建立訂單（給「訂單頁」這類不需要走完整購物流程的測試準備資料）
export async function createOrder(
  customer: Session,
  productId: string,
  qty: number,
): Promise<{ id: string; status: string }> {
  return post(customer, '/orders', { items: [{ productId, qty }] })
}
