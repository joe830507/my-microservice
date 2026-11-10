import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
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

describe('LoginView', () => {
  it('登入成功：回到 redirect 指定的頁面', async () => {
    server.use(
      http.post('*/auth/login', () => HttpResponse.json(tokens(1))),
      http.get('*/users/me', () => HttpResponse.json(me())),
    )
    const { wrapper, router } = await mountLogin('/login?redirect=%2F%3Fpage%3D2')

    await submit(wrapper, 'admin@example.com', 'secret')

    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/?page=2'))
  })

  it('redirect 是外部網址：回首頁', async () => {
    server.use(
      http.post('*/auth/login', () => HttpResponse.json(tokens(1))),
      http.get('*/users/me', () => HttpResponse.json(me())),
    )
    const { wrapper, router } = await mountLogin('/login?redirect=https%3A%2F%2Fevil.example')

    await submit(wrapper, 'admin@example.com', 'secret')

    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/'))
  })

  it('登入限流（429）：顯示提示', async () => {
    server.use(http.post('*/auth/login', () => problem(429, 'TOO_MANY_LOGIN_ATTEMPTS')))
    const { wrapper, router } = await mountLogin()

    await submit(wrapper, 'admin@example.com', 'secret')

    await vi.waitFor(() =>
      expect(wrapper.find('.el-alert').text()).toContain('登入嘗試次數過多，請稍後再試'),
    )
    expect(router.currentRoute.value.name).toBe('login')
  })

  it('不是 ADMIN：顯示沒有權限', async () => {
    server.use(
      http.post('*/auth/login', () => HttpResponse.json(tokens(1))),
      http.get('*/users/me', () => HttpResponse.json(me(['CUSTOMER']))),
      http.post('*/auth/logout', () => new HttpResponse(null, { status: 204 })),
    )
    const { wrapper, router } = await mountLogin()

    await submit(wrapper, 'user@example.com', 'secret')

    await vi.waitFor(() =>
      expect(wrapper.find('.el-alert').text()).toContain('此帳號沒有後台管理權限'),
    )
    expect(router.currentRoute.value.name).toBe('login')
  })

  it('帳密錯誤：顯示錯誤訊息', async () => {
    server.use(http.post('*/auth/login', () => problem(401, 'INVALID_CREDENTIALS')))
    const { wrapper } = await mountLogin()

    await submit(wrapper, 'admin@example.com', 'wrong')

    await vi.waitFor(() => expect(wrapper.find('.el-alert').text()).toContain('Email 或密碼錯誤'))
  })

  it('欄位沒填：顯示驗證訊息，不發請求', async () => {
    // 沒有註冊任何 handler：發出請求會因 onUnhandledRequest: 'error' 失敗
    const { wrapper } = await mountLogin()

    await submit(wrapper, '', '')

    await vi.waitFor(() => expect(wrapper.text()).toContain('請輸入 Email'))
    expect(wrapper.text()).toContain('請輸入密碼')
    expect(wrapper.find('.el-alert').exists()).toBe(false)
  })
})
