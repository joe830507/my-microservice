import { describe, expect, it } from 'vitest'
import { ApiError } from '@/api/errors'
import { loginErrorMessage, registerErrorMessage, safeRedirect } from '../messages'

describe('loginErrorMessage', () => {
  it.each([
    ['INVALID_CREDENTIALS', 401, 'Email 或密碼錯誤'],
    ['USER_DISABLED', 403, '此帳號已停用'],
    ['TOO_MANY_LOGIN_ATTEMPTS', 429, '登入嘗試次數過多，請稍後再試'],
    ['TOO_MANY_REQUESTS', 429, '登入嘗試次數過多，請稍後再試'],
  ])('%s → 對應的訊息', (code, status, message) => {
    expect(loginErrorMessage(new ApiError(status, code, 'server detail'))).toBe(message)
  })

  it('其他代碼：顯示 ApiError 的 detail', () => {
    expect(loginErrorMessage(new ApiError(0, 'NETWORK_ERROR', '無法連線到伺服器'))).toBe(
      '無法連線到伺服器',
    )
  })
})

describe('registerErrorMessage', () => {
  it.each([
    ['EMAIL_ALREADY_EXISTS', 409, '這個 Email 已經註冊過了，請直接登入'],
    ['TOO_MANY_REQUESTS', 429, '操作過於頻繁，請稍後再試'],
  ])('%s → 對應的訊息', (code, status, message) => {
    expect(registerErrorMessage(new ApiError(status, code, 'server detail'))).toBe(message)
  })

  it('其他代碼：顯示 ApiError 的 detail', () => {
    expect(registerErrorMessage(new ApiError(400, 'VALIDATION_FAILED', '密碼太短'))).toBe(
      '密碼太短',
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
    ['註冊頁本身', '/register'],
  ])('%s：回首頁', (_, value) => {
    expect(safeRedirect(value)).toBe('/')
  })
})
