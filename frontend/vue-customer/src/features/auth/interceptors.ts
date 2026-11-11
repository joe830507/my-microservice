import axios, { type AxiosInstance, type InternalAxiosRequestConfig } from 'axios'
import { ApiError } from '@/api/errors'
import type { AuthStore } from './store'

// 失敗的請求原本的設定；http.ts 已經把錯誤轉成 ApiError，原始的 AxiosError 在 cause
function requestConfigOf(error: unknown): InternalAxiosRequestConfig | undefined {
  if (error instanceof ApiError && axios.isAxiosError(error.cause)) {
    return error.cause.config
  }
  return undefined
}

function bearerOf(config: InternalAxiosRequestConfig): string | null {
  const header = config.headers.get('Authorization')
  return typeof header === 'string' && header.startsWith('Bearer ') ? header.slice(7) : null
}

// 帶 access token；收到 401 時 refresh 後重送一次
// refresh 失敗而且登入狀態已被清除時呼叫 onSessionExpired（導回登入頁）
// 回傳移除攔截器的函式，測試時用
export function installAuthInterceptors(
  client: AxiosInstance,
  store: AuthStore,
  onSessionExpired: () => void,
): () => void {
  const requestId = client.interceptors.request.use((config) => {
    if (!config.skipAuth && store.accessToken) {
      config.headers.set('Authorization', `Bearer ${store.accessToken}`)
    }
    return config
  })

  const responseId = client.interceptors.response.use(undefined, async (error: unknown) => {
    const config = requestConfigOf(error)
    const shouldRefresh =
      error instanceof ApiError &&
      error.status === 401 &&
      config !== undefined &&
      !config.skipAuth &&
      !config.authRetried
    if (!shouldRefresh) {
      throw error
    }

    try {
      // 這個請求送出後，其他請求已經換過 token：直接用新的重送，不必再 refresh
      const sentToken = bearerOf(config)
      if (store.accessToken === null || sentToken === store.accessToken) {
        await store.refreshAccessToken()
      }
    } catch (refreshError) {
      if (!store.hasSession) {
        onSessionExpired()
      }
      throw refreshError
    }

    // request 攔截器會換上新的 access token
    return client.request({ ...config, authRetried: true })
  })

  return () => {
    client.interceptors.request.eject(requestId)
    client.interceptors.response.eject(responseId)
  }
}
