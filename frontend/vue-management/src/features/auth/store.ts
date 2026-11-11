import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { ApiError } from '@/api/errors'
import * as authApi from './api'
import type { LoginReq, Me, TokenRes } from './types'

// auth 自己產生的錯誤代碼（不是後端回的）
export const AuthErrorCode = {
  // 登入成功但不是 ADMIN：後台不接受，登入後立即登出
  NOT_ADMIN: 'NOT_ADMIN',
  // 沒有 refresh token 可以換新：需要重新登入
  SESSION_EXPIRED: 'SESSION_EXPIRED',
} as const

// refresh token 放在後端設定的 HttpOnly cookie（SEC-06）：JavaScript 讀不到，頁面被 XSS 也偷不走；
// 瀏覽器在呼叫 /auth/refresh、/auth/logout 時自動帶上。access token 只放記憶體，重新整理頁面後用 cookie 換新
// 前端因此不知道「有沒有 cookie」：localStorage 只記一個「這個瀏覽器登入過」的提示（不是密碼或 token），
// 沒有提示就不必打 /auth/refresh（一定會 401）；提示掉了或過期，只是要重新登入
// key 和商店（vue-customer）分開：同一台電腦可以同時登入兩邊的不同帳號
export const SESSION_HINT_KEY = 'vue-management.hasSession'

function readSessionHint(): boolean {
  try {
    return localStorage.getItem(SESSION_HINT_KEY) === '1'
  } catch {
    return false
  }
}

function writeSessionHint(value: boolean): void {
  try {
    if (value) {
      localStorage.setItem(SESSION_HINT_KEY, '1')
    } else {
      localStorage.removeItem(SESSION_HINT_KEY)
    }
  } catch {
    // 無痕模式、儲存空間被封鎖時讀寫會失敗：只是重新整理後要重新登入，不影響這次使用
  }
}

function isAdmin(me: Me): boolean {
  return me.roles.includes('ADMIN')
}

export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref<string | null>(null)
  // 這個瀏覽器可能還有可用的 refresh token cookie（提示，不是保證）
  const hasSession = ref(readSessionHint())
  const user = ref<Me | null>(null)

  const isAuthenticated = computed(() => accessToken.value !== null && user.value !== null)

  // 同時只發一次 refresh：後端的 refresh token 每次都會換新，舊的立即失效
  // 多個請求同時收到 401 時共用同一個 Promise（瀏覽器各自帶 cookie 出去，後面的會拿到已經失效的 token）
  let refreshing: Promise<string> | null = null
  // 重新整理頁面後，路由守衛可能同時被觸發多次；共用同一次恢復
  let restoring: Promise<boolean> | null = null

  function setTokens(tokens: TokenRes): void {
    accessToken.value = tokens.accessToken
    hasSession.value = true
    writeSessionHint(true)
  }

  function clear(): void {
    accessToken.value = null
    hasSession.value = false
    user.value = null
    writeSessionHint(false)
  }

  async function login(req: LoginReq): Promise<void> {
    clear()
    setTokens(await authApi.login(req))
    try {
      const me = await authApi.getMe()
      if (!isAdmin(me)) {
        throw new ApiError(403, AuthErrorCode.NOT_ADMIN, '此帳號沒有後台管理權限')
      }
      user.value = me
    } catch (error) {
      // 不是 ADMIN，或取得使用者資料失敗：撤銷剛拿到的 refresh token，不留下半登入的狀態
      await logout()
      throw error
    }
  }

  // 換新 access token，回傳新的 access token
  function refreshAccessToken(): Promise<string> {
    refreshing ??= doRefresh().finally(() => {
      refreshing = null
    })
    return refreshing
  }

  async function doRefresh(): Promise<string> {
    if (!hasSession.value) {
      throw new ApiError(401, AuthErrorCode.SESSION_EXPIRED, '登入已過期，請重新登入')
    }
    try {
      const tokens = await authApi.refresh()
      setTokens(tokens)
      return tokens.accessToken
    } catch (error) {
      // 只有後端明確拒絕（4xx，例如 REFRESH_TOKEN_INVALID）才清除登入狀態
      // 網路中斷、5xx 時 refresh token 可能還有效，保留下來讓之後的請求再試
      if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
        clear()
      }
      throw error
    }
  }

  // 重新整理頁面後恢復登入狀態：用 refresh token（cookie）換 access token，再取得使用者資料
  function restoreSession(): Promise<boolean> {
    if (isAuthenticated.value) {
      return Promise.resolve(true)
    }
    if (!hasSession.value) {
      return Promise.resolve(false)
    }
    restoring ??= doRestore().finally(() => {
      restoring = null
    })
    return restoring
  }

  async function doRestore(): Promise<boolean> {
    try {
      await refreshAccessToken()
      const me = await authApi.getMe()
      if (!isAdmin(me)) {
        await logout()
        return false
      }
      user.value = me
      return true
    } catch {
      return false
    }
  }

  async function logout(): Promise<void> {
    const hadSession = hasSession.value
    // 先清除本機狀態，畫面立即反應；伺服器端撤銷失敗不影響本機登出
    clear()
    if (hadSession) {
      try {
        // 後端撤銷 refresh token 並要求瀏覽器刪掉 cookie
        await authApi.logout()
      } catch {
        // refresh token 到期後自然失效
      }
    }
  }

  return {
    accessToken,
    hasSession,
    user,
    isAuthenticated,
    login,
    logout,
    refreshAccessToken,
    restoreSession,
  }
})

export type AuthStore = ReturnType<typeof useAuthStore>
