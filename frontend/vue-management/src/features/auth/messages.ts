import { toApiError } from '@/api/errors'
import { AuthErrorCode } from './store'

// 登入失敗時依錯誤代碼顯示的訊息；沒有對應的代碼就顯示 ApiError 的 detail
export function loginErrorMessage(error: unknown): string {
  const apiError = toApiError(error)
  switch (apiError.code) {
    case 'INVALID_CREDENTIALS':
      return 'Email 或密碼錯誤'
    case 'USER_DISABLED':
      return '此帳號已停用'
    // user-service 依 email 限流、Gateway 依 IP 限流
    case 'TOO_MANY_LOGIN_ATTEMPTS':
    case 'TOO_MANY_REQUESTS':
      return '登入嘗試次數過多，請稍後再試'
    case AuthErrorCode.NOT_ADMIN:
      return '此帳號沒有後台管理權限'
    default:
      return apiError.detail
  }
}

// 登入後要回到的頁面；只接受站內路徑，避免 ?redirect=https://evil.example 這種開放重新導向
export function safeRedirect(value: unknown): string {
  if (
    typeof value !== 'string' ||
    !value.startsWith('/') ||
    value.startsWith('//') ||
    value.startsWith('/\\') ||
    value.startsWith('/login')
  ) {
    return '/'
  }
  return value
}
