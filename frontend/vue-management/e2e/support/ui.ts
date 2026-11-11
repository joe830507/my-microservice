import type { Page } from '@playwright/test'

// 在登入頁填表單並送出
export async function fillLogin(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login')
  await page.locator('input[name="email"]').fill(email)
  await page.locator('input[name="password"]').fill(password)
  await page.getByRole('button', { name: '登入' }).click()
}

// 和 src/features/auth/store.ts 的 REFRESH_TOKEN_KEY 一致
const REFRESH_TOKEN_KEY = 'vue-management.refreshToken'

// 直接把 refresh token 放進瀏覽器，等於「已經登入過」，不必走登入畫面（也不必再消耗一次登入限流）。
// refresh token 是單次使用的：頁面一用它換 token，舊的就失效，所以每個測試要用自己登入拿到的，不能共用。
export async function startSignedIn(page: Page, refreshToken: string): Promise<void> {
  await page.addInitScript(
    // 頁面每次載入都會執行這段：只在還沒有的時候設定，否則重新整理會把已經輪替過的新 token 蓋回舊的
    ([key, value]) => {
      if (!window.localStorage.getItem(key as string))
        window.localStorage.setItem(key as string, value as string)
    },
    [REFRESH_TOKEN_KEY, refreshToken],
  )
}
