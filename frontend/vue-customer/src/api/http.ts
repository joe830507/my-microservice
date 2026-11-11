import axios from 'axios'
import { toApiError } from './errors'

// 每個請求可以帶的自訂設定
declare module 'axios' {
  interface AxiosRequestConfig {
    // 不帶 access token、收到 401 也不 refresh：登入、refresh、登出本身用
    skipAuth?: boolean
    // 已經 refresh 後重送過一次；再收到 401 就不再重試，避免無限循環
    authRetried?: boolean
  }
}

// Create an instance
const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE,
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' },
})

// 所有失敗的請求都轉成 ApiError，呼叫端只需要處理一種錯誤
// 這個攔截器最先註冊，之後註冊的攔截器（例如 auth 的 401 → refresh）收到的都是 ApiError
apiClient.interceptors.response.use(
  (response) => response,
  (error: unknown) => Promise.reject(toApiError(error)),
)

export default apiClient
