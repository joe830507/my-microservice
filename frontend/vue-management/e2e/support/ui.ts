import type { Page } from '@playwright/test'

// 在登入頁填表單並送出
export async function fillLogin(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login')
  await page.locator('input[name="email"]').fill(email)
  await page.locator('input[name="password"]').fill(password)
  await page.getByRole('button', { name: '登入' }).click()
}

// 和 src/features/auth/store.ts 的 SESSION_HINT_KEY 一致
const SESSION_HINT_KEY = 'vue-management.hasSession'

// 把登入拿到的 refresh token 放進瀏覽器的 cookie，等於「已經登入過」，不必走登入畫面（也不必再消耗一次登入限流）。
// cookie 的屬性和後端簽發的一樣（HttpOnly、SameSite=Strict、Path=/api/v1/auth）；頁面的 JavaScript 讀不到它。
// refresh token 是單次使用的：頁面一用它換 token，舊的就失效，所以每個測試要用自己登入拿到的，不能共用。
export async function startSignedIn(page: Page, refreshToken: string): Promise<void> {
  const host = new URL(process.env.E2E_BASE_URL ?? 'http://localhost:5183').hostname
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

