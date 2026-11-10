import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { createPinia, setActivePinia } from 'pinia'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import apiClient from '@/api/http'
import { installAuthInterceptors } from '@/features/auth/interceptors'
import { REFRESH_TOKEN_KEY, useAuthStore } from '@/features/auth/store'
import { me, problem, tokens } from '@/features/auth/__tests__/fixtures'
import { installAuthGuard, installTitleUpdater, routes } from '..'

const server = setupServer()

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterAll(() => server.close())

let eject: () => void = () => {}

beforeEach(() => localStorage.clear())

afterEach(() => {
  eject()
  server.resetHandlers()
})

// localStorage 要先準備好，store 建立時才讀 refresh token
function createGuardedRouter(): Router {
  setActivePinia(createPinia())
  eject = installAuthInterceptors(apiClient, useAuthStore(), () => {})
  const router = createRouter({ history: createMemoryHistory(), routes })
  installAuthGuard(router)
  return router
}

describe('installAuthGuard', () => {
  it('未登入進入後台頁：導到登入頁並帶 redirect', async () => {
    const router = createGuardedRouter()

    await router.push('/?page=2')

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/?page=2')
  })

  it('登入頁不需要登入', async () => {
    const router = createGuardedRouter()

    await router.push('/login')

    expect(router.currentRoute.value.name).toBe('login')
  })

  it('重新整理後有 refresh token：恢復登入狀態並進入原頁面', async () => {
    localStorage.setItem(REFRESH_TOKEN_KEY, 'refresh-1')
    server.use(
      http.post('*/auth/refresh', () => HttpResponse.json(tokens(2))),
      http.get('*/users/me', () => HttpResponse.json(me())),
    )
    const router = createGuardedRouter()

    await router.push('/')

    expect(router.currentRoute.value.name).toBe('home')
    expect(useAuthStore().isAuthenticated).toBe(true)
  })

  it('refresh token 已失效：導到登入頁', async () => {
    localStorage.setItem(REFRESH_TOKEN_KEY, 'refresh-1')
    server.use(http.post('*/auth/refresh', () => problem(401, 'REFRESH_TOKEN_INVALID')))
    const router = createGuardedRouter()

    await router.push('/')

    expect(router.currentRoute.value.name).toBe('login')
  })

  it('已登入還進登入頁：回首頁', async () => {
    const router = createGuardedRouter()
    const auth = useAuthStore()
    auth.accessToken = 'access-1'
    auth.user = me()

    await router.push('/login')

    expect(router.currentRoute.value.name).toBe('home')
  })

  it('不存在的路徑：未登入也直接顯示 404，不導到登入頁', async () => {
    const router = createGuardedRouter()

    await router.push('/no-such-page')

    expect(router.currentRoute.value.name).toBe('not-found')
    expect(router.currentRoute.value.fullPath).toBe('/no-such-page')
  })

  it('403 頁面不需要登入', async () => {
    const router = createGuardedRouter()

    await router.push('/403')

    expect(router.currentRoute.value.name).toBe('forbidden')
  })
})

describe('installTitleUpdater', () => {
  it('瀏覽器分頁標題：頁面標題 - vue-management', async () => {
    const router = createGuardedRouter()
    installTitleUpdater(router)

    await router.push('/login')
    expect(document.title).toBe('登入 - vue-management')

    await router.push('/no-such-page')
    expect(document.title).toBe('找不到頁面 - vue-management')
  })
})
