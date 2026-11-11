import type { Page } from '@playwright/test'

// 和 src/features/auth/store.ts 的 REFRESH_TOKEN_KEY、src/features/cart/store.ts 的 CART_KEY 一致
const REFRESH_TOKEN_KEY = 'vue-customer.refreshToken'
const CART_KEY = 'vue-customer.cart'

// 直接把 refresh token 放進瀏覽器，等於「已經登入過」，不必走登入畫面（也不必再消耗一次登入限流）。
// refresh token 是單次使用的：頁面一用它換 token，舊的就失效，所以每個測試要用自己登入拿到的，不能共用。
export async function startSignedIn(page: Page, refreshToken: string): Promise<void> {
  await page.addInitScript(
    // 頁面每次載入都會執行這段：只在還沒有的時候設定，否則重新整理會把已經輪替過的新 token 蓋回舊的
    ([key, value]) => {
      if (!window.localStorage.getItem(key as string)) {
        window.localStorage.setItem(key as string, value as string)
      }
    },
    [REFRESH_TOKEN_KEY, refreshToken],
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
