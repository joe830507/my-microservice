import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { createPinia, setActivePinia } from 'pinia'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import apiClient from '@/api/http'
import { installAuthInterceptors } from '@/features/auth/interceptors'
import { REFRESH_TOKEN_KEY, useAuthStore } from '@/features/auth/store'
import { me } from '@/features/auth/__tests__/fixtures'
import { useCartStore } from '@/features/cart/store'
import { categoryTree } from '@/features/catalog/__tests__/fixtures'
import { installAuthGuard } from '@/router'

import StoreLayout from '../StoreLayout.vue'

const server = setupServer(
  http.get('*/categories/tree', () => HttpResponse.json({ roots: categoryTree })),
)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterAll(() => server.close())

let eject: () => void = () => {}

beforeEach(() => localStorage.clear())

afterEach(() => {
  eject()
  server.resetHandlers()
})

const Page = { template: '<p class="child">content</p>' }

async function mountLayout(path = '/') {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  eject = installAuthInterceptors(apiClient, auth, () => {})
  const router: Router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/',
        component: StoreLayout,
        children: [
          { path: '', name: 'home', component: Page },
          { path: 'search', name: 'search', component: Page },
          { path: 'cart', name: 'cart', component: Page },
          { path: 'c/:slug', name: 'category', component: Page },
          { path: 'login', name: 'login', component: Page },
          { path: 'register', name: 'register', component: Page },
          { path: 'secret', name: 'secret', component: Page, meta: { requiresAuth: true } },
        ],
      },
    ],
  })
  installAuthGuard(router)
  await router.push(path)
  await router.isReady()
  const wrapper = mount({ template: '<RouterView />' }, { global: { plugins: [pinia, router] } })
  return { wrapper, router, auth }
}

describe('StoreLayout', () => {
  it('購物車連結：有商品才顯示件數（數量加總），加入後即時更新', async () => {
    localStorage.setItem(
      'vue-customer.cart',
      JSON.stringify([
        { productId: 'p1', qty: 2 },
        { productId: 'p2', qty: 1 },
      ]),
    )
    const { wrapper } = await mountLayout()

    expect(wrapper.find('.store-nav__badge').text()).toBe('3')
    expect(wrapper.find('a[href="/cart"]').exists()).toBe(true)

    useCartStore().add('p3', 4)
    await flushPromises()

    expect(wrapper.find('.store-nav__badge').text()).toBe('7')
  })

  it('購物車是空的：不顯示件數', async () => {
    const { wrapper } = await mountLayout()

    expect(wrapper.find('.store-nav__badge').exists()).toBe(false)
  })

  it('分類列：顯示頂層分類，連到分類頁', async () => {
    const { wrapper } = await mountLayout()
    await flushPromises()

    const links = wrapper
      .findAll('.store-categories a')
      .map((a) => [a.text(), a.attributes('href')])
    expect(links).toEqual([
      ['鞋子', '/c/shoes'],
      ['包包', '/c/bags'],
    ])
  })

  it('搜尋框：送出後到 /search?q=，空白關鍵字去掉', async () => {
    const { wrapper, router } = await mountLayout()

    await wrapper.find('input[type="search"]').setValue('  運動鞋 ')
    await wrapper.find('form[role="search"]').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('search')
    expect(router.currentRoute.value.query.q).toBe('運動鞋')

    await wrapper.find('input[type="search"]').setValue('   ')
    await wrapper.find('form[role="search"]').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({})
  })

  it('在搜尋頁：搜尋框顯示目前的關鍵字', async () => {
    const { wrapper } = await mountLayout('/search?q=%E9%9E%8B')

    expect((wrapper.find('input[type="search"]').element as HTMLInputElement).value).toBe('鞋')
  })

  it('shows header, content area and footer', async () => {
    const { wrapper } = await mountLayout()

    expect(wrapper.find('header .store-brand').exists()).toBe(true)
    expect(wrapper.find('header input[type="search"]').exists()).toBe(true)
    expect(wrapper.find('nav').text()).toContain('購物車')
    expect(wrapper.find('main .child').text()).toBe('content')
    expect(wrapper.find('footer').text()).toContain('Joe 商城')
  })

  it('未登入：顯示登入、註冊連結，登入連結帶目前頁面的 redirect', async () => {
    const { wrapper } = await mountLayout('/?q=1')

    const links = wrapper.findAll('nav a').map((a) => [a.text(), a.attributes('href')])
    expect(links).toContainEqual(['登入', '/login?redirect=/?q=1'])
    expect(links).toContainEqual(['註冊', '/register'])
    expect(wrapper.text()).not.toContain('登出')
  })

  it('已登入：顯示名稱與登出，登出後回到未登入的樣子', async () => {
    server.use(http.post('*/auth/logout', () => new HttpResponse(null, { status: 204 })))
    localStorage.setItem(REFRESH_TOKEN_KEY, 'refresh-1')
    const { wrapper, auth } = await mountLayout()
    auth.accessToken = 'access-1'
    auth.refreshToken = 'refresh-1'
    auth.user = me()
    await flushPromises()

    expect(wrapper.find('.store-nav__user').text()).toBe('小明')

    await wrapper.find('.store-nav__logout').trigger('click')
    await flushPromises()

    expect(auth.isAuthenticated).toBe(false)
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull()
    expect(wrapper.find('.store-nav__user').exists()).toBe(false)
    expect(wrapper.text()).toContain('登入')
  })

  it('在需要登入的頁面登出：回首頁；再進去會被導到登入頁', async () => {
    server.use(http.post('*/auth/logout', () => new HttpResponse(null, { status: 204 })))
    const { wrapper, router, auth } = await mountLayout()
    auth.accessToken = 'access-1'
    auth.refreshToken = 'refresh-1'
    auth.user = me()
    await router.push('/secret')
    await flushPromises()

    await wrapper.find('.store-nav__logout').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('home')

    await router.push('/secret')
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/secret')
  })
})
