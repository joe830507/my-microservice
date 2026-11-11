import type { Page } from '@playwright/test'

// 和 src/features/auth/store.ts 的 SESSION_HINT_KEY、src/features/cart/store.ts 的 CART_KEY 一致
const SESSION_HINT_KEY = 'vue-customer.hasSession'
const CART_KEY = 'vue-customer.cart'

// 把登入拿到的 refresh token 放進瀏覽器的 cookie，等於「已經登入過」，不必走登入畫面（也不必再消耗一次登入限流）。
// cookie 的屬性和後端簽發的一樣（HttpOnly、SameSite=Strict、Path=/api/v1/auth）；頁面的 JavaScript 讀不到它。
// refresh token 是單次使用的：頁面一用它換 token，舊的就失效，所以每個測試要用自己登入拿到的，不能共用。
export async function startSignedIn(page: Page, refreshToken: string): Promise<void> {
  const host = new URL(process.env.E2E_BASE_URL ?? 'http://localhost:5184').hostname
  await page.context().addCookies([
    {
      name: 'refresh_token',
      value: refreshToken,
      domain: host,
      path: '/api/v1/auth',
      httpOnly: true,
      sameSite: 'Strict',
      secure: true,
    },
  ])
  // 前端只靠 localStorage 的提示知道要不要呼叫 /auth/refresh（見 src/features/auth/store.ts 的 SESSION_HINT_KEY）
  await page.addInitScript(
    // 頁面每次載入都會執行這段：只在還沒有的時候設定
    ([key, value]) => {
      if (!window.localStorage.getItem(key as string)) {
        window.localStorage.setItem(key as string, value as string)
      }
    },
    [SESSION_HINT_KEY, '1'],
  )
}

// 預先放好購物車內容（同樣只在還沒有的時候設定）
export async function startWithCart(
  page: Page,
  items: { productId: string; qty: number }[],
): Promise<void> {
  await page.addInitScript(
    ([key, value]) => {
      if (!window.localStorage.getItem(key as string)) {
        window.localStorage.setItem(key as string, value as string)
      }
    },
    [CART_KEY, JSON.stringify(items)],
  )
}

// 頁面沒有水平捲軸（手機寬度下版面沒有溢出）
export async function hasNoHorizontalScroll(page: Page): Promise<boolean> {
  return page.evaluate(
    () => document.documentElement.scrollWidth <= document.documentElement.clientWidth,
  )
}

// 圖片真的載入了（有寬度）；對著 nginx 跑時會經過 CSP 的 img-src
export async function imageLoaded(page: Page, selector: string): Promise<boolean> {
  return page
    .locator(selector)
    .first()
    .evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth > 0)
}
