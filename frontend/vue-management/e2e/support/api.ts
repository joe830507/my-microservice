import type { APIRequestContext } from '@playwright/test'

// 直接呼叫後端 API 準備與清理測試資料（經過 baseURL 的 /api/v1，和瀏覽器走同一條路）
const API = '/api/v1'

export interface Session {
  request: APIRequestContext
  // access token：給 API 呼叫用
  token: string
  // refresh token：給瀏覽器用（放進 localStorage，見 ui.ts 的 startSignedIn）
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
    const body = await json<{ accessToken: string; refreshToken: string }>(
      response,
      `登入 ${email}`,
    )
    return { request, token: body.accessToken, refreshToken: body.refreshToken }
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
// 商店的分類列就不會出現這個分類（商品沒有刪除 API，所以分類也盡量不要留著）
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
  status: string
  version: number
}

export async function productBySku(session: Session, sku: string): Promise<ProductRow> {
  return get<ProductRow>(session, `/products/sku/${encodeURIComponent(sku)}`)
}

// 下架（INACTIVE）：商店看不到，而且和封存不同，之後還可以再上架
export async function unpublishIfActive(admin: Session, sku: string): Promise<void> {
  const product = await productBySku(admin, sku)
  if (product.status === 'ACTIVE') {
    await post(admin, `/products/${product.id}/unpublish`, { version: product.version })
  }
}

// 固定的一般顧客帳號（不會越建越多）；已經註冊過回 409，當成成功
export async function ensureCustomer(
  request: APIRequestContext,
  email: string,
  password: string,
): Promise<void> {
  const response = await request.post(`${API}/auth/register`, {
    data: { email, password, displayName: 'E2E 顧客' },
  })
  if (!response.ok() && response.status() !== 409) {
    throw new Error(`註冊 ${email} 失敗：HTTP ${response.status()} ${await response.text()}`)
  }
}
