import { describe, expect, it } from 'vitest'
import { ApiError } from '@/api/errors'
import { loginErrorMessage, safeRedirect } from '../messages'
import { AuthErrorCode } from '../store'

describe('loginErrorMessage', () => {
  it.each([
    ['INVALID_CREDENTIALS', 401, 'Email 或密碼錯誤'],
    ['USER_DISABLED', 403, '此帳號已停用'],
    ['TOO_MANY_LOGIN_ATTEMPTS', 429, '登入嘗試次數過多，請稍後再試'],
    ['TOO_MANY_REQUESTS', 429, '登入嘗試次數過多，請稍後再試'],
    [AuthErrorCode.NOT_ADMIN, 403, '此帳號沒有後台管理權限'],
  ])('%s → 對應的訊息', (code, status, message) => {
    expect(loginErrorMessage(new ApiError(status, code, 'server detail'))).toBe(message)
  })

  it('其他代碼：顯示 ApiError 的 detail', () => {
    expect(loginErrorMessage(new ApiError(0, 'NETWORK_ERROR', '無法連線到伺服器'))).toBe(
      '無法連線到伺服器',
    )
  })
})

describe('safeRedirect', () => {
  it.each(['/', '/orders', '/products?page=2#top'])('站內路徑 %s：照原樣使用', (path) => {
    expect(safeRedirect(path)).toBe(path)
  })

  it.each([
    ['undefined', undefined],
    ['陣列（重複的 query）', ['/a', '/b']],
    ['外部網址', 'https://evil.example'],
    ['protocol-relative', '//evil.example'],
    ['反斜線', '/\\evil.example'],
    ['相對路徑', 'orders'],
    ['登入頁本身', '/login?redirect=/'],
  ])('%s：回首頁', (_, value) => {
    expect(safeRedirect(value)).toBe('/')
  })
})
