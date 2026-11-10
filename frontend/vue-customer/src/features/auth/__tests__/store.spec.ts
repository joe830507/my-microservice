import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { createPinia, setActivePinia } from 'pinia'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { ApiError } from '@/api/errors'
import apiClient from '@/api/http'
import { installAuthInterceptors } from '../interceptors'
import { REFRESH_TOKEN_KEY, useAuthStore, type AuthStore } from '../store'
import { me, problem, tokens } from './fixtures'

const server = setupServer()

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterAll(() => server.close())

let eject: () => void = () => {}

// localStorage 要在建立 store 之前準備好：store 建立時才讀 refresh token
function createStore(): AuthStore {
  setActivePinia(createPinia())
  const store = useAuthStore()
  eject = installAuthInterceptors(apiClient, store, () => {})
  return store
}

beforeEach(() => localStorage.clear())

afterEach(() => {
  eject()
  server.resetHandlers()
})

async function rejectionOf(promise: Promise<unknown>): Promise<ApiError> {
  const error = await promise.then(
    () => {
      throw new Error('預期失敗，但成功了')
    },
    (reason: unknown) => reason,
  )
  expect(error).toBeInstanceOf(ApiError)
  return error as ApiError
}

// 記錄 /auth/logout 收到的 body
function logoutRecorder(): unknown[] {
  const bodies: unknown[] = []
  server.use(
    http.post('*/auth/logout', async ({ request }) => {
      bodies.push(await request.json())
      return new HttpResponse(null, { status: 204 })
    }),
  )
  return bodies
}

describe('useAuthStore.login', () => {
  it('登入成功：保存 token 與使用者，/users/me 帶上新的 access token', async () => {
    let meAuthorization: string | null = null
    server.use(
      http.post('*/auth/login', async ({ request }) => {
        expect(await request.json()).toEqual({ email: 'user@example.com', password: 'secret' })
        return HttpResponse.json(tokens(1))
      }),
      http.get('*/users/me', ({ request }) => {
        meAuthorization = request.headers.get('Authorization')
        return HttpResponse.json(me())
      }),
    )
    const store = createStore()

    await store.login({ email: 'user@example.com', password: 'secret' })

    expect(store.isAuthenticated).toBe(true)
    expect(store.user?.displayName).toBe('小明')
    expect(store.accessToken).toBe('access-1')
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBe('refresh-1')
    expect(meAuthorization).toBe('Bearer access-1')
  })

  it('帳密錯誤（401）：不觸發 refresh，回傳後端的錯誤代碼', async () => {
    // 沒有註冊 /auth/refresh 的 handler：發出 refresh 會因 onUnhandledRequest: 'error' 失敗
    server.use(http.post('*/auth/login', () => problem(401, 'INVALID_CREDENTIALS')))
    const store = createStore()

    const error = await rejectionOf(store.login({ email: 'a@example.com', password: 'wrong' }))

    expect(error.code).toBe('INVALID_CREDENTIALS')
    expect(store.isAuthenticated).toBe(false)
  })

  it('登入限流（429）：回傳 TOO_MANY_LOGIN_ATTEMPTS', async () => {
    server.use(http.post('*/auth/login', () => problem(429, 'TOO_MANY_LOGIN_ATTEMPTS')))
    const store = createStore()

    const error = await rejectionOf(store.login({ email: 'a@example.com', password: 'x' }))

    expect(error.status).toBe(429)
    expect(error.code).toBe('TOO_MANY_LOGIN_ATTEMPTS')
  })
})

describe('useAuthStore.restoreSession', () => {
  it('localStorage 有 refresh token：換新 token、取得使用者，恢復登入狀態', async () => {
    localStorage.setItem(REFRESH_TOKEN_KEY, 'refresh-1')
    let refreshCalls = 0
    server.use(
      http.post('*/auth/refresh', () => {
        refreshCalls++
        return HttpResponse.json(tokens(2))
      }),
      http.get('*/users/me', () => HttpResponse.json(me())),
    )
    const store = createStore()

    // 同時呼叫兩次（例如守衛被觸發兩次）也只 refresh 一次
    const results = await Promise.all([store.restoreSession(), store.restoreSession()])

    expect(results).toEqual([true, true])
    expect(refreshCalls).toBe(1)
    expect(store.isAuthenticated).toBe(true)
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBe('refresh-2')
  })

  it('沒有 refresh token：直接回 false，不發請求', async () => {
    const store = createStore()

    await expect(store.restoreSession()).resolves.toBe(false)
  })

  it('refresh token 已失效：回 false 並清除', async () => {
    localStorage.setItem(REFRESH_TOKEN_KEY, 'refresh-1')
    server.use(http.post('*/auth/refresh', () => problem(401, 'REFRESH_TOKEN_INVALID')))
    const store = createStore()

    await expect(store.restoreSession()).resolves.toBe(false)
    expect(store.refreshToken).toBeNull()
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull()
  })
})

describe('useAuthStore.logout', () => {
  it('呼叫 /auth/logout 並清除狀態', async () => {
    localStorage.setItem(REFRESH_TOKEN_KEY, 'refresh-1')
    const logoutBodies = logoutRecorder()
    const store = createStore()
    store.accessToken = 'access-1'

    await store.logout()

    expect(logoutBodies).toEqual([{ refreshToken: 'refresh-1' }])
    expect(store.accessToken).toBeNull()
    expect(store.refreshToken).toBeNull()
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull()
  })

  it('/auth/logout 失敗：本機狀態一樣清除', async () => {
    localStorage.setItem(REFRESH_TOKEN_KEY, 'refresh-1')
    server.use(http.post('*/auth/logout', () => HttpResponse.error()))
    const store = createStore()

    await store.logout()

    expect(store.refreshToken).toBeNull()
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull()
  })
})
