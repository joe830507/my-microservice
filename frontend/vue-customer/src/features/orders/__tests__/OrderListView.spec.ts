import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { delay, http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { createPinia, setActivePinia } from 'pinia'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { me } from '@/features/auth/__tests__/fixtures'
import { useAuthStore } from '@/features/auth/store'
import { installAuthGuard, routes } from '@/router'
import { listItem, orderPage } from './fixtures'

const server = setupServer(http.get('*/categories/tree', () => HttpResponse.json({ roots: [] })))

const mounted: VueWrapper[] = []

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  mounted.splice(0).forEach((wrapper) => wrapper.unmount())
})
afterAll(() => server.close())
beforeEach(() => localStorage.clear())

function ordersReturning(handler: (params: URLSearchParams) => Response | Promise<Response>) {
  const calls: URLSearchParams[] = []
  server.use(
    http.get('*/orders', ({ request }) => {
      const params = new URL(request.url).searchParams
      calls.push(params)
      return handler(params)
    }),
  )
  return calls
}

async function mountAt(
  path = '/orders',
  signedIn = true,
): Promise<{ wrapper: VueWrapper; router: Router }> {
  const pinia = createPinia()
  setActivePinia(pinia)
  if (signedIn) {
    const auth = useAuthStore()
    auth.accessToken = 'access-1'
    auth.user = me()
  }
  const router = createRouter({ history: createMemoryHistory(), routes })
  installAuthGuard(router)
  await router.push(path)
  await router.isReady()
  const wrapper = mount({ template: '<RouterView />' }, { global: { plugins: [pinia, router] } })
  await flushPromises()
  mounted.push(wrapper)
  return { wrapper, router }
}

describe('我的訂單列表', () => {
  it('未登入：導到登入頁並帶 redirect，不查訂單', async () => {
    const { router } = await mountAt('/orders?status=PAID', false)

    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('login'))
    expect(router.currentRoute.value.query.redirect).toBe('/orders?status=PAID')
  })

  it('列出訂單：編號、狀態、金額、時間，連到詳情；每頁 10 筆、不帶 userId', async () => {
    const calls = ordersReturning(() =>
      HttpResponse.json(
        orderPage([listItem(1, { status: 'SHIPPED', totalAmount: 1234.5 }), listItem(2)]),
      ),
    )

    const { wrapper } = await mountAt()

    expect(Object.fromEntries(calls[0] ?? [])).toEqual({ page: '0', size: '10' })
    const cards = wrapper.findAll('.orders-card')
    expect(cards).toHaveLength(2)
    expect(cards[0]?.text()).toContain('訂單 #00000001')
    expect(cards[0]?.text()).toContain('已出貨')
    expect(cards[0]?.text()).toContain('NT$ 1,234.5')
    expect(cards[0]?.attributes('href')).toBe('/orders/00000000-0000-4000-8000-000000000001')
  })

  it('待付款的訂單有「去付款」，其他沒有', async () => {
    ordersReturning(() =>
      HttpResponse.json(orderPage([listItem(1, { status: 'PENDING_PAYMENT' }), listItem(2)])),
    )

    const { wrapper } = await mountAt()

    const links = wrapper.findAll('.orders-pay')
    expect(links).toHaveLength(1)
    expect(links[0]?.attributes('href')).toBe('/orders/00000000-0000-4000-8000-000000000001/pay')
  })

  it('依狀態篩選：寫進網址、回到第一頁、帶 status 參數；選「全部」移除', async () => {
    const calls = ordersReturning(() =>
      HttpResponse.json(orderPage([listItem(1)], { totalPages: 3 })),
    )
    const { wrapper, router } = await mountAt('/orders?page=3')

    const filter = (label: string) =>
      wrapper.findAll('.orders-filter button').find((b) => b.text() === label)!
    await filter('已付款').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({ status: 'PAID' })
    expect(calls[calls.length - 1]?.get('status')).toBe('PAID')
    expect(calls[calls.length - 1]?.get('page')).toBe('0')
    expect(filter('已付款').attributes('aria-pressed')).toBe('true')

    await filter('全部').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({})
  })

  it('網址上不合法的狀態、頁碼：改回預設值', async () => {
    const calls = ordersReturning(() => HttpResponse.json(orderPage([listItem(1)])))

    await mountAt('/orders?status=HACKED&page=-2')

    expect(Object.fromEntries(calls[0] ?? [])).toEqual({ page: '0', size: '10' })
  })

  it('分頁：換頁保留狀態', async () => {
    const calls = ordersReturning(() =>
      HttpResponse.json(orderPage([listItem(1)], { totalPages: 3, totalElements: 25 })),
    )
    const { wrapper, router } = await mountAt('/orders?status=PAID')

    await wrapper
      .findAll('.pagination button')
      .find((b) => b.text() === '2')
      ?.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({ status: 'PAID', page: '2' })
    expect(calls[calls.length - 1]?.get('page')).toBe('1')
    expect(calls[calls.length - 1]?.get('status')).toBe('PAID')
  })

  it('沒有訂單：顯示提示與去逛逛；篩選後沒有則說明是哪個狀態', async () => {
    ordersReturning(() => HttpResponse.json(orderPage([])))

    const { wrapper } = await mountAt()
    expect(wrapper.text()).toContain('還沒有訂單')
    expect(wrapper.find('.orders-empty a').attributes('href')).toBe('/search')

    const filtered = await mountAt('/orders?status=CANCELLED')
    expect(filtered.wrapper.text()).toContain('沒有「已取消」的訂單')
  })

  it('查詢失敗：顯示錯誤，重試後顯示', async () => {
    let fail = true
    ordersReturning(() =>
      fail
        ? HttpResponse.json({ status: 503, code: 'X', detail: '訂單服務忙碌' }, { status: 503 })
        : HttpResponse.json(orderPage([listItem(1)])),
    )
    const { wrapper } = await mountAt()

    expect(wrapper.find('[role="alert"]').text()).toContain('訂單服務忙碌')

    fail = false
    await wrapper.find('[role="alert"] button').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.orders-card')).toHaveLength(1)
  })

  it('快速切換條件：舊請求晚回來不覆蓋新結果', async () => {
    ordersReturning(async (params) => {
      if (!params.get('status')) {
        await delay(80)
        return HttpResponse.json(orderPage([listItem(1, { totalAmount: 111 })]))
      }
      return HttpResponse.json(orderPage([listItem(2, { totalAmount: 222 })]))
    })
    const { wrapper } = await mountAt()

    await wrapper
      .findAll('.orders-filter button')
      .find((b) => b.text() === '已付款')
      ?.trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('NT$ 222'))
    await delay(150)
    await flushPromises()

    expect(wrapper.text()).toContain('NT$ 222')
    expect(wrapper.text()).not.toContain('NT$ 111')
  })
})
