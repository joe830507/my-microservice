import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { delay, http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { createPinia, setActivePinia } from 'pinia'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import apiClient from '@/api/http'
import { routes } from '@/router'
import { installAuthInterceptors } from '../interceptors'
import LoginView from '../LoginView.vue'
import { useAuthStore } from '../store'
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

async function mountLogin(url = '/login'): Promise<{ wrapper: VueWrapper; router: Router }> {
  const router = createRouter({ history: createMemoryHistory(), routes })
  await router.push(url)
  await router.isReady()
  const wrapper = mount(LoginView, { global: { plugins: [router] } })
  return { wrapper, router }
}

async function submit(wrapper: VueWrapper, email: string, password: string): Promise<void> {
  await wrapper.find('input[name="email"]').setValue(email)
  await wrapper.find('input[name="password"]').setValue(password)
  await wrapper.find('form').trigger('submit')
  await flushPromises()
}

function loginOk() {
  server.use(
    http.post('*/auth/login', () => HttpResponse.json(tokens(1))),
    http.get('*/users/me', () => HttpResponse.json(me())),
  )
}

describe('LoginView', () => {
  it('登入成功：回到 redirect 指定的頁面', async () => {
    loginOk()
    const { wrapper, router } = await mountLogin('/login?redirect=%2F%3Fpage%3D2')

    await submit(wrapper, 'user@example.com', 'secret')

    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/?page=2'))
  })

  it('redirect 是外部網址：回首頁', async () => {
    loginOk()
    const { wrapper, router } = await mountLogin('/login?redirect=https%3A%2F%2Fevil.example')

    await submit(wrapper, 'user@example.com', 'secret')

    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/'))
  })

  it.each([
    ['INVALID_CREDENTIALS', 401, 'Email 或密碼錯誤'],
    ['USER_DISABLED', 403, '此帳號已停用'],
    ['TOO_MANY_LOGIN_ATTEMPTS', 429, '登入嘗試次數過多，請稍後再試'],
    ['TOO_MANY_REQUESTS', 429, '登入嘗試次數過多，請稍後再試'],
  ])('%s：顯示對應的訊息，留在登入頁', async (code, status, message) => {
    server.use(http.post('*/auth/login', () => problem(status, code)))
    const { wrapper, router } = await mountLogin()

    await submit(wrapper, 'user@example.com', 'secret')

    await vi.waitFor(() => expect(wrapper.find('[role="alert"]').text()).toBe(message))
    expect(router.currentRoute.value.name).toBe('login')
  })

  it('欄位沒填、Email 格式不對：顯示驗證訊息，不發請求', async () => {
    // 沒有註冊任何 handler：發出請求會因 onUnhandledRequest: 'error' 失敗
    const { wrapper } = await mountLogin()

    await submit(wrapper, '', '')
    expect(wrapper.text()).toContain('請輸入 Email')
    expect(wrapper.text()).toContain('請輸入密碼')

    await submit(wrapper, 'not-an-email', 'x')
    expect(wrapper.text()).toContain('Email 格式不正確')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('送出中按鈕停用，連點只送出一次', async () => {
    let calls = 0
    server.use(
      http.post('*/auth/login', async () => {
        calls++
        await delay(50)
        return HttpResponse.json(tokens(1))
      }),
      http.get('*/users/me', () => HttpResponse.json(me())),
    )
    const { wrapper, router } = await mountLogin()

    await wrapper.find('input[name="email"]').setValue('user@example.com')
    await wrapper.find('input[name="password"]').setValue('secret')
    await wrapper.find('form').trigger('submit')
    await wrapper.find('form').trigger('submit')

    expect(wrapper.find('button[type="submit"]').attributes('disabled')).toBeDefined()
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('home'))
    expect(calls).toBe(1)
  })

  it('註冊連結保留 redirect', async () => {
    const { wrapper } = await mountLogin('/login?redirect=%2Fcheckout')

    expect(wrapper.find('a[href^="/register"]').attributes('href')).toBe(
      '/register?redirect=/checkout',
    )
  })
})
