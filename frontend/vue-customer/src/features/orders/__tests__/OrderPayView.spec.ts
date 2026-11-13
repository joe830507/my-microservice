import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { delay, http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { createPinia, setActivePinia } from 'pinia'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { me } from '@/features/auth/__tests__/fixtures'
import { useAuthStore } from '@/features/auth/store'
import { pollConfig } from '@/features/payments/usePayFlow'
import type { Payment } from '@/features/payments/types'
import { installAuthGuard, routes } from '@/router'
import type { Order, OrderStatus } from '../types'

const server = setupServer(http.get('*/categories/tree', () => HttpResponse.json({ roots: [] })))

const originalPoll = { ...pollConfig }

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())
beforeEach(() => {
  localStorage.clear()
  // 不用真的等 1 秒、10 次
  pollConfig.intervalMs = 5
  pollConfig.maxAttempts = 4
})
afterEach(() => {
  Object.assign(pollConfig, originalPoll)
  // 沒有卸載的頁面會繼續跑計時器與輪詢，打到下一個測試的 mock
  mounted.splice(0).forEach((wrapper) => wrapper.unmount())
})

const mounted: VueWrapper[] = []

const ORDER_ID = 'order-1'

const minutesAgo = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString()

function order(status: OrderStatus = 'PENDING_PAYMENT', createdAt = minutesAgo(10)): Order {
  return {
    id: ORDER_ID,
    userId: 'u1',
    status,
    totalAmount: 1234.5,
    items: [
      { productId: 'p1', sku: 'S1', productName: '運動鞋', unitPrice: 600, qty: 2, subtotal: 1200 },
    ],
    createdAt,
    paidAt: null,
    shippedAt: null,
    completedAt: null,
    cancelledAt: null,
  }
}

function payment(id = 'pay-1', status: Payment['status'] = 'PENDING'): Payment {
  return {
    id,
    orderId: ORDER_ID,
    amount: 1234.5,
    status,
    createdAt: minutesAgo(0),
    completedAt: null,
  }
}

const problem = (status: number, code: string) =>
  HttpResponse.json({ status, code, detail: code }, { status })

// 一組可以隨時改變的後端行為，並記錄收到的請求
interface Backend {
  getOrder: () => Response
  createPayment: () => Response
  callback: (paymentId: string, result: string) => Response | Promise<Response>
  getPayment: (paymentId: string) => Response
}

function serve(overrides: Partial<Backend> = {}) {
  const backend: Backend = {
    getOrder: () => HttpResponse.json(order()),
    createPayment: () => HttpResponse.json(payment(), { status: 201 }),
    callback: (id, result) =>
      HttpResponse.json(payment(id, result === 'SUCCESS' ? 'SUCCEEDED' : 'FAILED')),
    getPayment: (id) => HttpResponse.json(payment(id)),
    ...overrides,
  }
  const calls = { getOrder: 0, createPayment: 0, callbacks: [] as [string, string][] }
  server.use(
    http.get('*/orders/:id', () => {
      calls.getOrder++
      return backend.getOrder()
    }),
    http.post('*/payments', async ({ request }) => {
      calls.createPayment++
      expect(await request.json()).toEqual({ orderId: ORDER_ID })
      return backend.createPayment()
    }),
    http.post('*/payments/:id/mock-callback', async ({ params, request }) => {
      const { result } = (await request.json()) as { result: string }
      calls.callbacks.push([String(params.id), result])
      return backend.callback(String(params.id), result)
    }),
    http.get('*/payments/:id', ({ params }) => backend.getPayment(String(params.id))),
  )
  return { backend, calls }
}

async function mountPay(): Promise<VueWrapper> {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.accessToken = 'access-1'
  auth.user = me()
  const router = createRouter({ history: createMemoryHistory(), routes })
  installAuthGuard(router)
  await router.push(`/orders/${ORDER_ID}/pay`)
  await router.isReady()
  const wrapper = mount({ template: '<RouterView />' }, { global: { plugins: [pinia, router] } })
  await flushPromises()
  mounted.push(wrapper)
  return wrapper
}

const buttons = (wrapper: VueWrapper) => ({
  success: wrapper.findAll('.pay__actions button')[0]!,
  fail: wrapper.findAll('.pay__actions button')[1]!,
})

describe('付款頁', () => {
  describe('等待付款', () => {
    it('建立付款回應已 CANCELLED：顯示取消且不能送出回呼', async () => {
      const { calls } = serve({
        createPayment: () => HttpResponse.json(payment('pay-1', 'CANCELLED')),
      })
      const wrapper = await mountPay()
      expect(wrapper.text()).toContain('訂單已取消')
      expect(wrapper.find('.pay__actions').exists()).toBe(false)
      expect(calls.callbacks).toEqual([])
    })

    it('顯示金額、商品與剩餘時間；建立付款只帶 orderId', async () => {
      const { calls } = serve()

      const wrapper = await mountPay()

      expect(wrapper.find('.pay__amount').text()).toContain('NT$ 1,234.5')
      expect(wrapper.find('.pay__items').text()).toContain('運動鞋 × 2')
      // 建單 10 分鐘前，還剩約 20 分鐘
      expect(wrapper.find('.pay__deadline').text()).toMatch(/\b(19:5\d|20:0[01])\b/)
      expect(calls.createPayment).toBe(1)
      expect(buttons(wrapper).success.attributes('disabled')).toBeUndefined()
    })

    it('付款期限已到：停用按鈕、提示，不能送出回呼', async () => {
      const { calls } = serve({
        getOrder: () => HttpResponse.json(order('PENDING_PAYMENT', minutesAgo(31))),
      })

      const wrapper = await mountPay()

      expect(wrapper.find('[role="alert"]').text()).toContain('付款期限已到')
      expect(buttons(wrapper).success.attributes('disabled')).toBeDefined()
      expect(buttons(wrapper).fail.attributes('disabled')).toBeDefined()
      await buttons(wrapper).success.trigger('click')
      expect(calls.callbacks).toEqual([])
    })
  })

  describe('付款成功', () => {
    it('送出 SUCCESS 回呼，輪詢訂單到 PAID（付款通知是非同步的）', async () => {
      let polls = 0
      const { calls } = serve({
        getOrder: () => {
          polls++
          // 第 1 次是進頁面時查的；付款後前兩次還沒更新，第 3 次才是 PAID
          return HttpResponse.json(order(polls >= 4 ? 'PAID' : 'PENDING_PAYMENT'))
        },
      })
      const wrapper = await mountPay()

      await buttons(wrapper).success.trigger('click')
      await vi.waitFor(() => expect(wrapper.text()).toContain('付款完成'))

      expect(calls.callbacks).toEqual([['pay-1', 'SUCCESS']])
      expect(polls).toBe(4)
      expect(wrapper.find('a[href="/orders"]').exists()).toBe(true)
    })

    it('輪詢到上限仍不是 PAID：提示付款處理中，按「再查一次」後更新', async () => {
      let paid = false
      serve({ getOrder: () => HttpResponse.json(order(paid ? 'PAID' : 'PENDING_PAYMENT')) })
      const wrapper = await mountPay()

      await buttons(wrapper).success.trigger('click')
      await vi.waitFor(() => expect(wrapper.text()).toContain('付款處理中'))
      expect(wrapper.text()).toContain('請稍後到「我的訂單」查看')

      paid = true
      await wrapper.find('.pay__result .btn').trigger('click')
      await vi.waitFor(() => expect(wrapper.text()).toContain('付款完成'))
    })

    it('付款成功時訂單已被取消（付款逾時）：說明款項會退回', async () => {
      let status: OrderStatus = 'PENDING_PAYMENT'
      serve({
        getOrder: () => HttpResponse.json(order(status)),
        callback: () => {
          status = 'CANCELLED'
          return HttpResponse.json(payment('pay-1', 'SUCCEEDED'))
        },
      })
      const wrapper = await mountPay()

      await buttons(wrapper).success.trigger('click')
      await vi.waitFor(() => expect(wrapper.text()).toContain('訂單已取消'))

      expect(wrapper.text()).toContain('已付的款項會退回')
    })

    it('處理中連點：只送出一次回呼，按鈕停用', async () => {
      const { calls } = serve({
        callback: async (id) => {
          await delay(30)
          return HttpResponse.json(payment(id, 'SUCCEEDED'))
        },
      })
      const wrapper = await mountPay()

      await buttons(wrapper).success.trigger('click')
      await buttons(wrapper).success.trigger('click')
      await buttons(wrapper).fail.trigger('click')

      expect(buttons(wrapper).success.attributes('disabled')).toBeDefined()
      expect(wrapper.find('[role="status"]').text()).toContain('處理中')
      await flushPromises()
      expect(calls.callbacks).toEqual([['pay-1', 'SUCCESS']])
    })
  })

  describe('付款失敗', () => {
    it('送出 FAILED 回呼：顯示失敗，重新付款會建立新的付款並用新的付款 id 回呼', async () => {
      let created = 0
      const { calls } = serve({
        createPayment: () => HttpResponse.json(payment(`pay-${++created}`), { status: 201 }),
      })
      const wrapper = await mountPay()

      await buttons(wrapper).fail.trigger('click')
      await flushPromises()
      expect(wrapper.text()).toContain('付款失敗')
      expect(wrapper.text()).toContain('可以重新付款')

      await wrapper.find('.pay__result .btn').trigger('click')
      await flushPromises()
      expect(calls.createPayment).toBe(2)

      await buttons(wrapper).success.trigger('click')
      await flushPromises()
      expect(calls.callbacks).toEqual([
        ['pay-1', 'FAILED'],
        ['pay-2', 'SUCCESS'],
      ])
    })
  })

  describe('回呼失敗時以後端記錄的付款狀態為準', () => {
    it('付款已 CANCELLED：顯示訂單已取消，停止操作且不輪詢訂單', async () => {
      const { calls } = serve({
        callback: () => problem(409, 'PAYMENT_ALREADY_COMPLETED'),
        getPayment: (id) => HttpResponse.json({ ...payment(id), status: 'CANCELLED' }),
      })
      const wrapper = await mountPay()
      await buttons(wrapper).success.trigger('click')
      await flushPromises()
      expect(wrapper.text()).toContain('訂單已取消')
      expect(wrapper.text()).not.toContain('已付的款項會退回')
      expect(wrapper.find('.pay__actions').exists()).toBe(false)
      expect(calls.getOrder).toBe(1)
    })
    it('409 PAYMENT_ALREADY_COMPLETED、實際上已成功：不當成失敗，繼續確認訂單', async () => {
      serve({
        callback: () => problem(409, 'PAYMENT_ALREADY_COMPLETED'),
        getPayment: (id) => HttpResponse.json(payment(id, 'SUCCEEDED')),
        getOrder: (() => {
          let n = 0
          return () => HttpResponse.json(order(++n >= 2 ? 'PAID' : 'PENDING_PAYMENT'))
        })(),
      })
      const wrapper = await mountPay()

      await buttons(wrapper).fail.trigger('click')
      await vi.waitFor(() => expect(wrapper.text()).toContain('付款完成'))
    })

    it('沒收到回應、付款還是 PENDING：回到可以再按，顯示錯誤', async () => {
      const { calls } = serve({
        callback: () => HttpResponse.error(),
        getPayment: (id) => HttpResponse.json(payment(id, 'PENDING')),
      })
      const wrapper = await mountPay()

      await buttons(wrapper).success.trigger('click')
      await flushPromises()

      expect(wrapper.find('[role="alert"]').text()).toContain('無法連線')
      expect(buttons(wrapper).success.attributes('disabled')).toBeUndefined()
      expect(calls.callbacks).toHaveLength(1)
    })

    it('沒收到回應、但付款其實已失敗：顯示付款失敗', async () => {
      serve({
        callback: () => HttpResponse.error(),
        getPayment: (id) => HttpResponse.json(payment(id, 'FAILED')),
      })
      const wrapper = await mountPay()

      await buttons(wrapper).success.trigger('click')
      await flushPromises()

      expect(wrapper.text()).toContain('付款失敗')
    })
  })

  describe('進入頁面時訂單的狀態', () => {
    it.each(['PAID', 'SHIPPED', 'COMPLETED'] as OrderStatus[])(
      '%s：顯示已付款，不能再付、不建立付款',
      async (status) => {
        const { calls } = serve({ getOrder: () => HttpResponse.json(order(status)) })

        const wrapper = await mountPay()

        expect(wrapper.text()).toContain('付款完成')
        expect(wrapper.find('.pay__actions').exists()).toBe(false)
        expect(calls.createPayment).toBe(0)
      },
    )

    it('CANCELLED：顯示已取消，不建立付款', async () => {
      const { calls } = serve({ getOrder: () => HttpResponse.json(order('CANCELLED')) })

      const wrapper = await mountPay()

      expect(wrapper.text()).toContain('訂單已取消')
      expect(wrapper.text()).toContain('無法再付款')
      expect(calls.createPayment).toBe(0)
    })

    it('訂單不存在（或是別人的）：顯示 404，不建立付款', async () => {
      const { calls } = serve({ getOrder: () => problem(404, 'ORDER_NOT_FOUND') })

      const wrapper = await mountPay()

      expect(wrapper.find('h1').text()).toBe('404')
      expect(calls.createPayment).toBe(0)
    })

    it('查詢訂單失敗：顯示錯誤，重試後顯示付款畫面', async () => {
      let fail = true
      serve({ getOrder: () => (fail ? problem(503, 'X') : HttpResponse.json(order())) })
      const wrapper = await mountPay()

      expect(wrapper.find('[role="alert"]').text()).toContain('X')

      fail = false
      await wrapper.find('[role="alert"] button').trigger('click')
      await flushPromises()

      expect(wrapper.find('.pay__actions').exists()).toBe(true)
    })
  })

  describe('建立付款的錯誤', () => {
    it('ORDER_ALREADY_PAID（上次付完重新整理）：不能再付，確認訂單後顯示完成', async () => {
      let polls = 0
      serve({
        createPayment: () => problem(409, 'ORDER_ALREADY_PAID'),
        getOrder: () => HttpResponse.json(order(++polls >= 3 ? 'PAID' : 'PENDING_PAYMENT')),
      })

      const wrapper = await mountPay()
      await vi.waitFor(() => expect(wrapper.text()).toContain('付款完成'))

      expect(wrapper.find('.pay__actions button').exists()).toBe(false)
    })

    it('ORDER_NOT_PAYABLE（訂單剛被取消）：重新查訂單，顯示已取消', async () => {
      let polls = 0
      serve({
        createPayment: () => problem(409, 'ORDER_NOT_PAYABLE'),
        getOrder: () => HttpResponse.json(order(++polls >= 2 ? 'CANCELLED' : 'PENDING_PAYMENT')),
      })

      const wrapper = await mountPay()

      expect(wrapper.text()).toContain('訂單已取消')
    })

    it('ORDER_SERVICE_UNAVAILABLE：顯示錯誤，重試後建立付款', async () => {
      let fail = true
      const { calls } = serve({
        createPayment: () =>
          fail
            ? problem(503, 'ORDER_SERVICE_UNAVAILABLE')
            : HttpResponse.json(payment(), { status: 201 }),
      })
      const wrapper = await mountPay()

      expect(wrapper.find('[role="alert"]').text()).toContain('ORDER_SERVICE_UNAVAILABLE')

      fail = false
      await wrapper.find('[role="alert"] button').trigger('click')
      await flushPromises()

      expect(calls.createPayment).toBe(2)
    })

    it('重新整理（已有進行中的付款，後端回 200 同一筆）：照樣顯示付款畫面，用那一筆的 id', async () => {
      const { calls } = serve({
        createPayment: () => HttpResponse.json(payment('pay-existing'), { status: 200 }),
      })
      const wrapper = await mountPay()

      await buttons(wrapper).success.trigger('click')
      await flushPromises()

      expect(calls.callbacks[0]?.[0]).toBe('pay-existing')
    })
  })

  it('離開頁面後停止輪詢', async () => {
    let polls = 0
    serve({
      getOrder: () => {
        polls++
        return HttpResponse.json(order())
      },
    })
    pollConfig.maxAttempts = 50
    const wrapper = await mountPay()
    await buttons(wrapper).success.trigger('click')
    await vi.waitFor(() => expect(polls).toBeGreaterThan(2))

    wrapper.unmount()
    const atUnmount = polls
    await delay(60)

    expect(polls - atUnmount).toBeLessThanOrEqual(1)
  })
})
