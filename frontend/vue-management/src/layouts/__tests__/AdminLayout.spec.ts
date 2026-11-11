import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { createPinia, setActivePinia } from 'pinia'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import App from '@/App.vue'
import { SESSION_HINT_KEY, useAuthStore } from '@/features/auth/store'
import { me } from '@/features/auth/__tests__/fixtures'
import { product } from '@/features/products/__tests__/fixtures'
import { menuItems, routes } from '@/router'

const server = setupServer()

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterAll(() => server.close())

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  // 已登入的狀態
  const auth = useAuthStore()
  auth.accessToken = 'access-1'
  auth.hasSession = true
  auth.user = me()
})

afterEach(() => server.resetHandlers())

async function mountAt(path: string): Promise<{ wrapper: VueWrapper; router: Router }> {
  const router = createRouter({ history: createMemoryHistory(), routes })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(App, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}

describe('AdminLayout', () => {
  it('頂部列顯示使用者名稱', async () => {
    const { wrapper } = await mountAt('/')

    expect(wrapper.find('.admin-user-name').text()).toBe('管理員')
  })

  it('側邊選單列出設了 menu 的路由，目前頁面被選中', async () => {
    const { wrapper } = await mountAt('/')

    const items = wrapper.findAll('.el-menu-item')
    expect(items.map((item) => item.text())).toEqual(menuItems.map((item) => item.title))
    expect(wrapper.find('.el-menu-item.is-active').text()).toBe('首頁')
  })

  it('麵包屑顯示目前頁面', async () => {
    const { wrapper } = await mountAt('/')

    const crumbs = wrapper.findAll('.el-breadcrumb__item')
    expect(crumbs.map((crumb) => crumb.text().replace('/', '').trim())).toEqual(['首頁'])
  })

  it('巢狀頁面：麵包屑列出上層，沒有名稱的上層用路徑連結，選單選中上層', async () => {
    server.use(
      http.get('*/categories', () => HttpResponse.json({ categories: [] })),
      http.get('*/products/p1', () => HttpResponse.json(product('DRAFT'))),
    )
    const { wrapper } = await mountAt('/products/p1')

    const crumbs = wrapper.findAll('.el-breadcrumb__item')
    expect(crumbs.map((crumb) => crumb.text().replace('/', '').trim())).toEqual([
      '首頁',
      '商品管理',
      '編輯商品',
    ])
    // 有連結的麵包屑會加上 is-link
    expect(crumbs[1]?.find('.is-link').exists()).toBe(true)
    expect(crumbs[2]?.find('.is-link').exists()).toBe(false)
    expect(wrapper.find('.el-menu-item.is-active').text()).toBe('商品管理')
  })

  it('登出：呼叫 /auth/logout，清除狀態並回到登入頁', async () => {
    const logoutBodies: unknown[] = []
    server.use(
      http.post('*/auth/logout', async ({ request }) => {
        logoutBodies.push(request.headers.get('X-Requested-With'))
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const { wrapper, router } = await mountAt('/')

    await wrapper.find('.admin-user button').trigger('click')

    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('login'))
    expect(logoutBodies).toEqual(['XMLHttpRequest'])
    expect(useAuthStore().isAuthenticated).toBe(false)
    expect(localStorage.getItem(SESSION_HINT_KEY)).toBeNull()
  })
})
