import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { createPinia, setActivePinia } from 'pinia'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import apiClient from '@/api/http'
import { me } from '@/features/auth/__tests__/fixtures'
import { installAuthInterceptors } from '@/features/auth/interceptors'
import { SESSION_HINT_KEY, useAuthStore } from '@/features/auth/store'
import { CART_KEY, useCartStore } from '@/features/cart/store'
import { installAuthGuard, routes } from '@/router'

const server = setupServer(
  http.get('*/categories/tree', () => HttpResponse.json({ roots: [] })),
  http.get('*/users/me', () => HttpResponse.json(me())),
)

const mounted: VueWrapper[] = []
let eject: () => void = () => {}

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  mounted.splice(0).forEach((wrapper) => wrapper.unmount())
  eject()
})
afterAll(() => server.close())
beforeEach(() => localStorage.clear())

async function mountAccount(
  signedIn = true,
): Promise<{ wrapper: VueWrapper; router: Router; auth: ReturnType<typeof useAuthStore> }> {
  localStorage.setItem(SESSION_HINT_KEY, '1')
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  eject = installAuthInterceptors(apiClient, auth, () => {})
  if (signedIn) {
    auth.accessToken = 'access-1'
    auth.user = me()
  } else {
    auth.hasSession = false
    localStorage.clear()
  }
  const router = createRouter({ history: createMemoryHistory(), routes })
  installAuthGuard(router)
  await router.push('/account')
  await router.isReady()
  const wrapper = mount({ template: '<RouterView />' }, { global: { plugins: [pinia, router] } })
  await flushPromises()
  mounted.push(wrapper)
  return { wrapper, router, auth }
}

const dd = (wrapper: VueWrapper) => wrapper.findAll('.account__info dd').map((d) => d.text())

describe('帳號頁', () => {
  it('未登入：導到登入頁並帶 redirect', async () => {
    const { router } = await mountAccount(false)

    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('login'))
    expect(router.currentRoute.value.query.redirect).toBe('/account')
  })

  it('顯示名稱、Email、註冊時間', async () => {
    const { wrapper } = await mountAccount()

    const [name, email, created] = dd(wrapper)
    expect(name).toBe('小明')
    expect(email).toBe('user@example.com')
    expect(created).toContain('2026')
    expect(wrapper.find('.account__links a').attributes('href')).toBe('/orders')
  })

  it('進頁面重新取得使用者資料並更新畫面', async () => {
    server.use(http.get('*/users/me', () => HttpResponse.json({ ...me(), displayName: '大明' })))

    const { wrapper } = await mountAccount()

    expect(dd(wrapper)[0]).toBe('大明')
  })

  it('重新取得失敗：維持登入時取得的資料，不顯示錯誤', async () => {
    server.use(http.get('*/users/me', () => HttpResponse.error()))

    const { wrapper, auth } = await mountAccount()

    expect(dd(wrapper)[0]).toBe('小明')
    expect(auth.isAuthenticated).toBe(true)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  describe('登出所有裝置', () => {
    const openConfirm = (wrapper: VueWrapper) =>
      wrapper.find('.account > .account__danger').trigger('click')

    it('先要求確認；返回不送出請求', async () => {
      let calls = 0
      server.use(
        http.delete('*/users/me/sessions', () => {
          calls++
          return new HttpResponse(null, { status: 204 })
        }),
      )
      const { wrapper, auth } = await mountAccount()

      await openConfirm(wrapper)
      expect(wrapper.find('[role="alertdialog"]').text()).toContain('確定要登出所有裝置嗎')
      await wrapper.find('[role="alertdialog"] .account__link').trigger('click')

      expect(wrapper.find('[role="alertdialog"]').exists()).toBe(false)
      expect(calls).toBe(0)
      expect(auth.isAuthenticated).toBe(true)
    })

    it('成功：帶 access token 呼叫、清除本機狀態、回首頁，購物車保留', async () => {
      let authorization: string | null = null
      server.use(
        http.delete('*/users/me/sessions', ({ request }) => {
          authorization = request.headers.get('Authorization')
          return new HttpResponse(null, { status: 204 })
        }),
      )
      localStorage.setItem(CART_KEY, JSON.stringify([{ productId: 'p1', qty: 2 }]))
      const { wrapper, router, auth } = await mountAccount()

      await openConfirm(wrapper)
      await wrapper.find('[role="alertdialog"] .btn').trigger('click')
      await flushPromises()

      expect(authorization).toBe('Bearer access-1')
      expect(auth.isAuthenticated).toBe(false)
      expect(auth.accessToken).toBeNull()
      expect(localStorage.getItem(SESSION_HINT_KEY)).toBeNull()
      expect(router.currentRoute.value.name).toBe('home')
      expect(useCartStore().items).toEqual([{ productId: 'p1', qty: 2 }])

      // 登出後再進帳號頁會被導到登入頁
      await router.push('/account')
      await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('login'))
    })

    it('失敗：維持登入，說明其他裝置可能仍然登入，可以再試', async () => {
      let fail = true
      server.use(
        http.delete('*/users/me/sessions', () =>
          fail
            ? HttpResponse.json({ status: 503, code: 'X', detail: '服務忙碌' }, { status: 503 })
            : new HttpResponse(null, { status: 204 }),
        ),
      )
      const { wrapper, router, auth } = await mountAccount()

      await openConfirm(wrapper)
      await wrapper.find('[role="alertdialog"] .btn').trigger('click')
      await flushPromises()

      expect(wrapper.find('[role="alert"]').text()).toContain('其他裝置可能仍然登入')
      expect(wrapper.find('[role="alert"]').text()).toContain('服務忙碌')
      expect(auth.isAuthenticated).toBe(true)
      expect(localStorage.getItem(SESSION_HINT_KEY)).toBe('1')
      expect(router.currentRoute.value.name).toBe('account')

      fail = false
      await openConfirm(wrapper)
      await wrapper.find('[role="alertdialog"] .btn').trigger('click')
      await flushPromises()

      expect(auth.isAuthenticated).toBe(false)
    })

    it('連點確定：只送出一次', async () => {
      let calls = 0
      server.use(
        http.delete('*/users/me/sessions', async () => {
          calls++
          await new Promise((resolve) => setTimeout(resolve, 30))
          return new HttpResponse(null, { status: 204 })
        }),
      )
      const { wrapper } = await mountAccount()

      await openConfirm(wrapper)
      const confirm = wrapper.find('[role="alertdialog"] .btn')
      await confirm.trigger('click')
      await confirm.trigger('click')
      await confirm.trigger('click')

      expect(confirm.attributes('disabled')).toBeDefined()
      await flushPromises()
      await vi.waitFor(() => expect(calls).toBe(1))
    })
  })
})
