import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { createPinia, setActivePinia } from 'pinia'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import apiClient from '@/api/http'
import { routes } from '@/router'
import { installAuthInterceptors } from '../interceptors'
import RegisterView from '../RegisterView.vue'
import { SESSION_HINT_KEY, useAuthStore } from '../store'
import { me, problem, tokens } from './fixtures'

const server = setupServer()

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterAll(() => server.close())

let eject: () => void = () => {}

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  eject = installAuthInterceptors(apiClient, useAuthStore(), () => {})
})

afterEach(() => {
  eject()
  server.resetHandlers()
})

async function mountRegister(url = '/register'): Promise<{ wrapper: VueWrapper; router: Router }> {
  const router = createRouter({ history: createMemoryHistory(), routes })
  await router.push(url)
  await router.isReady()
  const wrapper = mount(RegisterView, { global: { plugins: [router] } })
  return { wrapper, router }
}

async function submit(wrapper: VueWrapper, email: string, password: string, name: string) {
  await wrapper.find('input[name="email"]').setValue(email)
  await wrapper.find('input[name="password"]').setValue(password)
  await wrapper.find('input[name="displayName"]').setValue(name)
  await wrapper.find('form').trigger('submit')
  await flushPromises()
}

describe('RegisterView', () => {
  it('註冊成功：送出正確的 body、已登入並回到 redirect', async () => {
    let body: unknown
    server.use(
      http.post('*/auth/register', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(me(), { status: 201 })
      }),
      http.post('*/auth/login', () => HttpResponse.json(tokens(1))),
      http.get('*/users/me', () => HttpResponse.json(me())),
    )
    const { wrapper, router } = await mountRegister('/register?redirect=%2Fcart')

    await submit(wrapper, ' user@example.com ', 'password123', ' 小明 ')

    expect(body).toEqual({
      email: 'user@example.com',
      password: 'password123',
      displayName: '小明',
    })
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/cart'))
    expect(useAuthStore().isAuthenticated).toBe(true)
    expect(localStorage.getItem(SESSION_HINT_KEY)).toBe('1')
  })

  it('EMAIL_ALREADY_EXISTS：顯示 Email 已註冊，不登入', async () => {
    server.use(http.post('*/auth/register', () => problem(409, 'EMAIL_ALREADY_EXISTS')))
    const { wrapper, router } = await mountRegister()

    await submit(wrapper, 'user@example.com', 'password123', '小明')

    await vi.waitFor(() => expect(wrapper.find('[role="alert"]').text()).toContain('已經註冊過'))
    expect(router.currentRoute.value.name).toBe('register')
    expect(useAuthStore().isAuthenticated).toBe(false)
  })

  it('註冊成功但自動登入失敗：說明帳號已建立', async () => {
    server.use(
      http.post('*/auth/register', () => HttpResponse.json(me(), { status: 201 })),
      http.post('*/auth/login', () => problem(429, 'TOO_MANY_REQUESTS')),
    )
    const { wrapper } = await mountRegister()

    await submit(wrapper, 'user@example.com', 'password123', '小明')

    await vi.waitFor(() => expect(wrapper.find('[role="alert"]').text()).toContain('帳號已建立'))
  })

  it('欄位驗證：密碼太短、顯示名稱太長，不發請求', async () => {
    // 沒有註冊任何 handler：發出請求會因 onUnhandledRequest: 'error' 失敗
    const { wrapper } = await mountRegister()

    await submit(wrapper, 'user@example.com', 'short', 'x'.repeat(51))

    expect(wrapper.text()).toContain('密碼長度需為 8–72 個字元')
    expect(wrapper.text()).toContain('顯示名稱最多 50 個字元')
  })

  it('欄位沒填：三個欄位都顯示提示', async () => {
    const { wrapper } = await mountRegister()

    await submit(wrapper, '', '', '')

    expect(wrapper.text()).toContain('請輸入 Email')
    expect(wrapper.text()).toContain('請輸入密碼')
    expect(wrapper.text()).toContain('請輸入顯示名稱')
  })
})
