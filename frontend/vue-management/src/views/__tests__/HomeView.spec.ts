import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { createPinia, setActivePinia } from 'pinia'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { me, problem } from '@/features/auth/__tests__/fixtures'
import { useAuthStore } from '@/features/auth/store'
import { listItem as orderItem } from '@/features/orders/__tests__/fixtures'
import { listItem as productItem } from '@/features/products/__tests__/fixtures'
import { routes } from '@/router'
import HomeView from '../HomeView.vue'

const server = setupServer()

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterAll(() => server.close())

// 各狀態的筆數（totalElements）
const ORDER_TOTALS: Record<string, number> = { PAID: 3, PENDING_PAYMENT: 7, SHIPPED: 2 }
const PRODUCT_TOTALS: Record<string, number> = { ACTIVE: 1234, DRAFT: 4, INACTIVE: 0 }

function page<T>(items: T[], totalElements: number) {
  return { items, page: 0, size: items.length, totalElements, totalPages: 1 }
}

beforeEach(() => {
  setActivePinia(createPinia())
  useAuthStore().user = me()
  server.use(
    http.get('*/orders', ({ request }) => {
      const status = new URL(request.url).searchParams.get('status') ?? ''
      const items =
        status === 'PAID' ? [orderItem('order-0001-aaaa-bbbb-ccccddddeeee', 'PAID')] : []
      return HttpResponse.json(page(items, ORDER_TOTALS[status] ?? 0))
    }),
    http.get('*/products', ({ request }) => {
      const status = new URL(request.url).searchParams.get('status') ?? ''
      const items = status === 'DRAFT' ? [productItem({ id: 'p9', name: 'Draft Boot' })] : []
      return HttpResponse.json(page(items, PRODUCT_TOTALS[status] ?? 0))
    }),
    http.get('*/users', () => HttpResponse.json(page([], 58))),
  )
})

afterEach(() => {
  server.resetHandlers()
  document.body.innerHTML = ''
})

async function mountHome(): Promise<{ wrapper: VueWrapper; router: Router }> {
  const router = createRouter({ history: createMemoryHistory(), routes })
  await router.push('/')
  await router.isReady()
  const wrapper = mount(HomeView, { global: { plugins: [router] }, attachTo: document.body })
  await flushPromises()
  return { wrapper, router }
}

function value(wrapper: VueWrapper, selector: string): string {
  return wrapper.find(selector).text()
}

describe('HomeView', () => {
  it('打招呼', async () => {
    const { wrapper } = await mountHome()

    expect(wrapper.find('.dashboard-title').text()).toBe('歡迎回來，管理員')
  })

  it('數字卡片：各狀態的筆數', async () => {
    const { wrapper } = await mountHome()

    expect(value(wrapper, '.kpi-orders-paid .kpi-value')).toBe('3')
    expect(value(wrapper, '.kpi-orders-pending .kpi-value')).toBe('7')
    expect(value(wrapper, '.kpi-products-active .kpi-value')).toBe('1,234')
    expect(value(wrapper, '.kpi-users .kpi-value')).toBe('58')
    expect(value(wrapper, '.stat-products-draft .stat-value')).toBe('4')
    expect(value(wrapper, '.stat-products-inactive .stat-value')).toBe('0')
    expect(value(wrapper, '.stat-orders-shipped .stat-value')).toBe('2')
  })

  it('點數字卡片：到列表並帶上篩選條件', async () => {
    const { wrapper, router } = await mountHome()

    await wrapper.find('.kpi-orders-paid').trigger('click')

    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('orders'))
    expect(router.currentRoute.value.query).toEqual({ status: 'PAID' })
  })

  it('待辦清單：待出貨訂單與草稿商品，點了進入詳情', async () => {
    const { wrapper, router } = await mountHome()

    expect(wrapper.find('.todo-to-ship').text()).toContain('#ddddeeee')
    expect(wrapper.find('.todo-drafts').text()).toContain('Draft Boot')

    await wrapper.find('.todo-drafts .todo-item').trigger('click')

    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('product-edit'))
    expect(router.currentRoute.value.params.id).toBe('p9')
  })

  it('一塊讀取失敗不影響其他塊', async () => {
    server.use(http.get('*/users', () => problem(503, 'SERVICE_UNAVAILABLE')))

    const { wrapper } = await mountHome()

    expect(value(wrapper, '.kpi-users .kpi-value')).toBe('—')
    expect(value(wrapper, '.kpi-orders-paid .kpi-value')).toBe('3')
  })

  it('沒有待辦：顯示空狀態', async () => {
    server.use(
      http.get('*/orders', () => HttpResponse.json(page([], 0))),
      http.get('*/products', () => HttpResponse.json(page([], 0))),
    )

    const { wrapper } = await mountHome()

    expect(wrapper.find('.todo-to-ship').text()).toContain('沒有待出貨的訂單')
    expect(wrapper.find('.todo-drafts').text()).toContain('沒有草稿商品')
  })
})
