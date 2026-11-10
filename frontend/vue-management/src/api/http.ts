import axios from 'axios'
import { toApiError } from './errors'

// Create an instance
const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE,
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' },
})

// 所有失敗的請求都轉成 ApiError，呼叫端只需要處理一種錯誤
apiClient.interceptors.response.use(
  (response) => response,
  (error: unknown) => Promise.reject(toApiError(error)),
)

export default apiClient
