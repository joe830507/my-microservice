import { delay, http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { createPinia, setActivePinia } from 'pinia'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, ClientErrorCode } from '@/api/errors'
import apiClient from '@/api/http'
import { installAuthInterceptors } from '../interceptors'
import { REFRESH_TOKEN_KEY, useAuthStore, type AuthStore } from '../store'
import { problem, tokens } from './fixtures'

const server = setupServer()

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterAll(() => server.close())

let store: AuthStore
let onSessionExpired: ReturnType<typeof vi.fn<() => void>>
let eject: () => void

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  store = useAuthStore()
  onSessionExpired = vi.fn<() => void>()
  eject = installAuthInterceptors(apiClient, store, onSessionExpired)
})

afterEach(() => {
  eject()
  server.resetHandlers()
})

// 已登入：access-1 / refresh-1
function signIn(): void {
  store.accessToken = 'access-1'
  store.refreshToken = 'refresh-1'
}

// 只接受 access-2 的 API；其他 token 一律 401，和 Gateway 一樣
function ordersAcceptingOnly(token: string) {
  return http.get('*/orders', ({ request }) =>
    request.headers.get('Authorization') === `Bearer ${token}`
      ? HttpResponse.json({ ok: true })
      : problem(401, 'UNAUTHENTICATED'),
  )
}

async function rejectionOf(request: Promise<unknown>): Promise<ApiError> {
  const error = await request.then(
    () => {
      throw new Error('預期請求失敗，但成功了')
    },
    (reason: unknown) => reason,
  )
  expect(error).toBeInstanceOf(ApiError)
  return error as ApiError
}

describe('installAuthInterceptors', () => {
  it('帶上 Authorization: Bearer <access token>', async () => {
    signIn()
    let authorization: string | null = null
    server.use(
      http.get('*/products', ({ request }) => {
        authorization = request.headers.get('Authorization')
        return HttpResponse.json([])
      }),
    )

    await apiClient.get('/products')

    expect(authorization).toBe('Bearer access-1')
  })

  it('skipAuth 的請求不帶 token', async () => {
    signIn()
    let authorization: string | null = 'not-called'
    server.use(
      http.post('*/auth/login', ({ request }) => {
        authorization = request.headers.get('Authorization')
        return HttpResponse.json(tokens(2))
      }),
    )

    await apiClient.post('/auth/login', {}, { skipAuth: true })

    expect(authorization).toBeNull()
  })

  it('同時兩個請求收到 401：只發一次 refresh，兩個都用新 token 重送成功', async () => {
    signIn()
    const refreshBodies: unknown[] = []
    server.use(
      ordersAcceptingOnly('access-2'),
      http.post('*/auth/refresh', async ({ request }) => {
        refreshBodies.push(await request.json())
        await delay(20)
        return HttpResponse.json(tokens(2))
      }),
    )

    const [first, second] = await Promise.all([apiClient.get('/orders'), apiClient.get('/orders')])

    expect(first.data).toEqual({ ok: true })
    expect(second.data).toEqual({ ok: true })
    expect(refreshBodies).toEqual([{ refreshToken: 'refresh-1' }])
    expect(store.accessToken).toBe('access-2')
    expect(store.refreshToken).toBe('refresh-2')
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBe('refresh-2')
    expect(onSessionExpired).not.toHaveBeenCalled()
  })

  it('請求送出後 token 已被其他請求換新：直接用新 token 重送，不再 refresh', async () => {
    signIn()
    let refreshCalls = 0
    server.use(
      http.get('*/orders', ({ request }) => {
        if (request.headers.get('Authorization') === 'Bearer access-2') {
          return HttpResponse.json({ ok: true })
        }
        // 模擬這個請求在路上時，另一個請求已經 refresh 完成
        store.accessToken = 'access-2'
        return problem(401, 'UNAUTHENTICATED')
      }),
      http.post('*/auth/refresh', () => {
        refreshCalls++
        return HttpResponse.json(tokens(3))
      }),
    )

    const response = await apiClient.get('/orders')

    expect(response.data).toEqual({ ok: true })
    expect(refreshCalls).toBe(0)
  })

  it('refresh 被拒絕：清除登入狀態，導回登入頁', async () => {
    signIn()
    localStorage.setItem(REFRESH_TOKEN_KEY, 'refresh-1')
    server.use(
      ordersAcceptingOnly('access-2'),
      http.post('*/auth/refresh', () => problem(401, 'REFRESH_TOKEN_INVALID')),
    )

    const error = await rejectionOf(apiClient.get('/orders'))

    expect(error.code).toBe('REFRESH_TOKEN_INVALID')
    expect(store.accessToken).toBeNull()
    expect(store.refreshToken).toBeNull()
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull()
    expect(onSessionExpired).toHaveBeenCalled()
  })

  it('沒有 refresh token：不發 refresh，直接導回登入頁', async () => {
    server.use(ordersAcceptingOnly('access-2'))

    const error = await rejectionOf(apiClient.get('/orders'))

    expect(error.status).toBe(401)
    expect(onSessionExpired).toHaveBeenCalledOnce()
  })

  it('refresh 時網路中斷：保留登入狀態，不導回登入頁', async () => {
    signIn()
    server.use(
      ordersAcceptingOnly('access-2'),
      http.post('*/auth/refresh', () => HttpResponse.error()),
    )

    const error = await rejectionOf(apiClient.get('/orders'))

    expect(error.code).toBe(ClientErrorCode.NETWORK_ERROR)
    expect(store.refreshToken).toBe('refresh-1')
    expect(onSessionExpired).not.toHaveBeenCalled()
  })

  it('重送後仍然 401：不再 refresh，避免無限循環', async () => {
    signIn()
    let refreshCalls = 0
    server.use(
      http.get('*/orders', () => problem(401, 'UNAUTHENTICATED')),
      http.post('*/auth/refresh', () => {
        refreshCalls++
        return HttpResponse.json(tokens(refreshCalls + 1))
      }),
    )

    const error = await rejectionOf(apiClient.get('/orders'))

    expect(error.status).toBe(401)
    expect(refreshCalls).toBe(1)
  })

  it('401 以外的錯誤不觸發 refresh', async () => {
    signIn()
    server.use(http.get('*/orders', () => problem(403, 'FORBIDDEN')))

    const error = await rejectionOf(apiClient.get('/orders'))

    expect(error.status).toBe(403)
    expect(store.accessToken).toBe('access-1')
  })
})
