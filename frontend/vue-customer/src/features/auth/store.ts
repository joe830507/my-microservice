import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { ApiError } from '@/api/errors'
import * as authApi from './api'
import type { LoginReq, Me, RegisterReq, TokenRes } from './types'

// auth 自己產生的錯誤代碼（不是後端回的）
export const AuthErrorCode = {
  // 沒有 refresh token 可以換新：需要重新登入
  SESSION_EXPIRED: 'SESSION_EXPIRED',
  // 註冊成功，但接著的自動登入失敗
  REGISTERED_LOGIN_FAILED: 'REGISTERED_LOGIN_FAILED',
} as const

// refresh token 雛型階段放 localStorage（有 XSS 風險，之後考慮改成 httpOnly cookie）
// access token 只放記憶體，重新整理頁面後用 refresh token 換新
// key 和後台（vue-management）分開：同一台電腦可以同時登入兩邊的不同帳號
// key 是內部用的，不跟著顯示名稱改：改了會讓已登入的人都被登出
export const REFRESH_TOKEN_KEY = 'vue-customer.refreshToken'

function readStoredRefreshToken(): string | null {
  try {
    return localStorage.getItem(REFRESH_TOKEN_KEY)
  } catch {
    return null
  }
}

function writeStoredRefreshToken(token: string | null): void {
  try {
    if (token) {
      localStorage.setItem(REFRESH_TOKEN_KEY, token)
    } else {
      localStorage.removeItem(REFRESH_TOKEN_KEY)
    }
  } catch {
    // 無痕模式、儲存空間被封鎖時讀寫會失敗：只是重新整理後要重新登入，不影響這次使用
  }
}

export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref<string | null>(null)
  const refreshToken = ref<string | null>(readStoredRefreshToken())
  const user = ref<Me | null>(null)

  const isAuthenticated = computed(() => accessToken.value !== null && user.value !== null)

  // 同時只發一次 refresh：後端的 refresh token 每次都會換新，舊的立即失效
  // 多個請求同時收到 401 時共用同一個 Promise，否則後面的請求會拿已經失效的 token 去換
  let refreshing: Promise<string> | null = null
  // 重新整理頁面後，路由守衛可能同時被觸發多次；共用同一次恢復
  let restoring: Promise<boolean> | null = null

  function setTokens(tokens: TokenRes): void {
    accessToken.value = tokens.accessToken
    refreshToken.value = tokens.refreshToken
    writeStoredRefreshToken(tokens.refreshToken)
  }

  function clear(): void {
    accessToken.value = null
    refreshToken.value = null
    user.value = null
    writeStoredRefreshToken(null)
  }

  async function login(req: LoginReq): Promise<void> {
    clear()
    setTokens(await authApi.login(req))
    try {
      user.value = await authApi.getMe()
    } catch (error) {
      // 取得使用者資料失敗：撤銷剛拿到的 refresh token，不留下半登入的狀態
      await logout()
      throw error
    }
  }

  // 註冊成功後直接登入；登入失敗時帳號其實已經建立，用專用的錯誤代碼告訴呼叫端
  async function register(req: RegisterReq): Promise<void> {
    await authApi.register(req)
    try {
      await login({ email: req.email, password: req.password })
    } catch (error) {
      const status = error instanceof ApiError ? error.status : 0
      throw new ApiError(
        status,
        AuthErrorCode.REGISTERED_LOGIN_FAILED,
        '帳號已建立，但自動登入失敗，請到登入頁登入',
        error,
      )
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
    const token = refreshToken.value
    if (!token) {
      throw new ApiError(401, AuthErrorCode.SESSION_EXPIRED, '登入已過期，請重新登入')
    }
    try {
      const tokens = await authApi.refresh(token)
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

  // 重新整理頁面後恢復登入狀態：用 refresh token 換 access token，再取得使用者資料
  function restoreSession(): Promise<boolean> {
    if (isAuthenticated.value) {
      return Promise.resolve(true)
    }
    if (!refreshToken.value) {
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
      user.value = await authApi.getMe()
      return true
    } catch {
      return false
    }
  }

  async function logout(): Promise<void> {
    const token = refreshToken.value
    // 先清除本機狀態，畫面立即反應；伺服器端撤銷失敗不影響本機登出
    clear()
    if (token) {
      try {
        await authApi.logout(token)
      } catch {
        // refresh token 到期後自然失效
      }
    }
  }

  return {
    accessToken,
    refreshToken,
    user,
    isAuthenticated,
    login,
    register,
    logout,
    refreshAccessToken,
    restoreSession,
  }
})

export type AuthStore = ReturnType<typeof useAuthStore>
