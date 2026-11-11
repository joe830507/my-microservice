import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { delay, http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { createPinia, setActivePinia } from 'pinia'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import type { BatchProduct } from '@/features/cart/api'
import { CART_KEY, useCartStore } from '@/features/cart/store'
import { me } from '@/features/auth/__tests__/fixtures'
import { useAuthStore } from '@/features/auth/store'
import type { Order } from '@/features/orders/types'
import { installAuthGuard, routes } from '@/router'

const server = setupServer(http.get('*/categories/tree', () => HttpResponse.json({ roots: [] })))

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())
beforeEach(() => localStorage.clear())

function batch(id: string, price: number, status = 'ACTIVE'): BatchProduct {
  return { id, sku: `SKU-${id}`, name: `商品 ${id}`, price, status }
}

function order(overrides: Partial<Order> = {}): Order {
  return {
    id: 'order-1',
    userId: 'u1',
    status: 'PENDING_PAYMENT',
    totalAmount: 0,
    items: [],
    createdAt: '2026-11-10T00:00:00Z',
    paidAt: null,
    shippedAt: null,
    completedAt: null,
    cancelledAt: null,
    ...overrides,
  }
}

const problem = (status: number, code: string, detail: string) =>
  HttpResponse.json({ status, code, detail }, { status })

function serveBatch(products: BatchProduct[]) {
  server.use(http.get('*/products/batch', () => HttpResponse.json({ products })))
}

interface Mounted {
  wrapper: VueWrapper
  router: Router
  replace: ReturnType<typeof vi.spyOn>
}

async function mountCheckout(
  items: { productId: string; qty: number }[],
  { signedIn = true } = {},
): Promise<Mounted> {
  localStorage.setItem(CART_KEY, JSON.stringify(items))
  const pinia = createPinia()
  setActivePinia(pinia)
  if (signedIn) {
    const auth = useAuthStore()
    auth.accessToken = 'access-1'
    auth.user = me()
  }
  const router = createRouter({ history: createMemoryHistory(), routes })
  installAuthGuard(router)
  const replace = vi.spyOn(router, 'replace')
  await router.push('/checkout')
  await router.isReady()
  const wrapper = mount({ template: '<RouterView />' }, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return { wrapper, router, replace }
}

const submitButton = (wrapper: VueWrapper) => wrapper.find('.checkout-summary .btn')

describe('結帳頁', () => {
  describe('顯示', () => {
    it('重新查價格：列出商品、數量、小計與總計', async () => {
      serveBatch([batch('a', 100), batch('b', 250.5)])

      const { wrapper } = await mountCheckout([
        { productId: 'a', qty: 2 },
        { productId: 'b', qty: 1 },
      ])

      const lines = wrapper.findAll('.checkout-line').map((l) => l.text())
      expect(lines[0]).toContain('商品 a')
      expect(lines[0]).toContain('NT$ 100 × 2')
      expect(lines[0]).toContain('NT$ 200')
      expect(lines[1]).toContain('NT$ 250.5 × 1')
      expect(wrapper.find('.checkout-summary__total').text()).toContain('NT$ 450.5')
      expect(wrapper.text()).toContain('30 分鐘')
    })

    it('無法購買的商品：不列出、不計入總計，提示回購物車處理', async () => {
      serveBatch([batch('a', 100), batch('off', 999, 'INACTIVE')])

      const { wrapper } = await mountCheckout([
        { productId: 'a', qty: 1 },
        { productId: 'off', qty: 3 },
        { productId: 'gone', qty: 1 },
      ])

      expect(wrapper.findAll('.checkout-line')).toHaveLength(1)
      expect(wrapper.find('.checkout-summary__total').text()).toContain('NT$ 100')
      expect(wrapper.text()).toContain('有 2 項商品已無法購買')
    })

    it('沒有可結帳的商品：不顯示送出按鈕', async () => {
      serveBatch([batch('off', 999, 'ARCHIVED')])

      const { wrapper } = await mountCheckout([{ productId: 'off', qty: 1 }])

      expect(wrapper.text()).toContain('沒有可以結帳的商品')
      expect(submitButton(wrapper).exists()).toBe(false)
    })

    it('空的購物車：顯示提示，不查商品', async () => {
      // 沒有註冊 /products/batch 的 handler：發出請求會因 onUnhandledRequest: 'error' 失敗
      const { wrapper } = await mountCheckout([])

      expect(wrapper.text()).toContain('購物車是空的')
    })

    it('查詢商品失敗：顯示錯誤，重試後顯示', async () => {
      let fail = true
      server.use(
        http.get('*/products/batch', () =>
          fail
            ? problem(503, 'X', '商品服務忙碌')
            : HttpResponse.json({ products: [batch('a', 100)] }),
        ),
      )
      const { wrapper } = await mountCheckout([{ productId: 'a', qty: 1 }])

      expect(wrapper.find('[role="alert"]').text()).toContain('商品服務忙碌')

      fail = false
      await wrapper.find('[role="alert"] button').trigger('click')
      await flushPromises()

      expect(wrapper.findAll('.checkout-line')).toHaveLength(1)
    })
  })

  it('未登入：導到登入頁並帶 redirect，不查商品', async () => {
    const { router } = await mountCheckout([{ productId: 'a', qty: 1 }], { signedIn: false })

    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('login'))
    expect(router.currentRoute.value.query.redirect).toBe('/checkout')
  })

  describe('送出訂單', () => {
    it('成功：送出正確的 body、只移除已下單的商品、replace 到付款頁', async () => {
      serveBatch([batch('a', 100), batch('b', 50), batch('off', 999, 'INACTIVE')])
      let body: unknown
      server.use(
        http.post('*/orders', async ({ request }) => {
          body = await request.json()
          return HttpResponse.json(order({ id: 'order-9' }), { status: 201 })
        }),
      )
      const { wrapper, router, replace } = await mountCheckout([
        { productId: 'a', qty: 2 },
        { productId: 'off', qty: 3 },
        { productId: 'b', qty: 1 },
      ])

      await submitButton(wrapper).trigger('click')
      await flushPromises()

      // 無法購買的商品不會送出，價格與名稱也不在 body 裡（以後端為準）
      expect(body).toEqual({
        items: [
          { productId: 'a', qty: 2 },
          { productId: 'b', qty: 1 },
        ],
      })
      expect(useCartStore().items).toEqual([{ productId: 'off', qty: 3 }])
      expect(JSON.parse(localStorage.getItem(CART_KEY) ?? '[]')).toEqual([
        { productId: 'off', qty: 3 },
      ])
      expect(replace).toHaveBeenCalledWith({ name: 'order-pay', params: { id: 'order-9' } })
      await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/orders/order-9/pay'))
    })

    it('送出中按鈕停用，連點只送出一次', async () => {
      serveBatch([batch('a', 100)])
      let calls = 0
      server.use(
        http.post('*/orders', async () => {
          calls++
          await delay(50)
          return HttpResponse.json(order(), { status: 201 })
        }),
      )
      const { wrapper, router } = await mountCheckout([{ productId: 'a', qty: 1 }])

      await submitButton(wrapper).trigger('click')
      await submitButton(wrapper).trigger('click')
      await submitButton(wrapper).trigger('click')

      expect(submitButton(wrapper).attributes('disabled')).toBeDefined()
      expect(submitButton(wrapper).text()).toBe('送出中…')
      await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('order-pay'))
      expect(calls).toBe(1)
    })

    it('庫存不足：指出是哪個商品，購物車不變，可以再送出', async () => {
      serveBatch([batch('a', 100), batch('b', 50)])
      server.use(http.post('*/orders', () => problem(409, 'OUT_OF_STOCK', 'out of stock: SKU-b.')))
      const { wrapper, router } = await mountCheckout([
        { productId: 'a', qty: 1 },
        { productId: 'b', qty: 5 },
      ])

      await submitButton(wrapper).trigger('click')
      await flushPromises()

      expect(wrapper.find('[role="alert"]').text()).toContain('「商品 b」庫存不足')
      expect(useCartStore().items).toHaveLength(2)
      expect(router.currentRoute.value.name).toBe('checkout')
      expect(submitButton(wrapper).attributes('disabled')).toBeUndefined()
    })

    it('商品已下架：指出商品、重新查商品後標成無法購買，購物車不變', async () => {
      let status = 'ACTIVE'
      server.use(
        http.get('*/products/batch', () =>
          HttpResponse.json({ products: [batch('a', 100), batch('b', 50, status)] }),
        ),
        http.post('*/orders', () => {
          status = 'INACTIVE' // 在結帳頁停留的期間被後台下架
          return problem(409, 'PRODUCT_NOT_AVAILABLE', 'product is not available for purchase: b.')
        }),
      )
      const { wrapper } = await mountCheckout([
        { productId: 'a', qty: 1 },
        { productId: 'b', qty: 1 },
      ])

      await submitButton(wrapper).trigger('click')
      await flushPromises()

      expect(wrapper.find('[role="alert"]').text()).toContain('「商品 b」已無法購買')
      expect(wrapper.findAll('.checkout-line')).toHaveLength(1)
      expect(wrapper.text()).toContain('有 1 項商品已無法購買')
      expect(useCartStore().items).toHaveLength(2)
    })

    it.each(['PRODUCT_SERVICE_UNAVAILABLE', 'INVENTORY_SERVICE_UNAVAILABLE'])(
      '%s（503）：說明訂單尚未建立，稍後再試',
      async (code) => {
        serveBatch([batch('a', 100)])
        server.use(http.post('*/orders', () => problem(503, code, 'unavailable')))
        const { wrapper } = await mountCheckout([{ productId: 'a', qty: 1 }])

        await submitButton(wrapper).trigger('click')
        await flushPromises()

        expect(wrapper.find('[role="alert"]').text()).toContain('訂單尚未建立')
        expect(useCartStore().items).toHaveLength(1)
        expect(submitButton(wrapper).attributes('disabled')).toBeUndefined()
      },
    )

    it('沒收到回應：不確定訂單有沒有建立，提示先到我的訂單查看，購物車不變', async () => {
      serveBatch([batch('a', 100)])
      server.use(http.post('*/orders', () => HttpResponse.error()))
      const { wrapper } = await mountCheckout([{ productId: 'a', qty: 1 }])

      await submitButton(wrapper).trigger('click')
      await flushPromises()

      const alert = wrapper.find('[role="alert"]')
      expect(alert.text()).toContain('無法確認訂單是否已建立')
      expect(alert.find('a').attributes('href')).toBe('/orders')
      expect(useCartStore().items).toHaveLength(1)
    })

    it('每次送出都帶 Idempotency-Key；沒收到回應後重送沿用同一個 key（不會變成兩張訂單）', async () => {
      serveBatch([batch('a', 100)])
      const keys: (string | null)[] = []
      let calls = 0
      server.use(
        http.post('*/orders', ({ request }) => {
          keys.push(request.headers.get('Idempotency-Key'))
          calls++
          return calls === 1
            ? HttpResponse.error()
            : HttpResponse.json(order({ id: 'order-7' }), { status: 200 })
        }),
      )
      const { wrapper, router } = await mountCheckout([{ productId: 'a', qty: 1 }])

      await submitButton(wrapper).trigger('click')
      await flushPromises()
      expect(wrapper.find('[role="alert"]').text()).toContain('無法確認訂單是否已建立')
      await submitButton(wrapper).trigger('click')
      await flushPromises()

      expect(keys).toHaveLength(2)
      expect(keys[0]).toBeTruthy()
      expect(keys[1]).toBe(keys[0])
      // 重送拿到原本那張訂單（後端回 200）也照樣導到付款頁
      expect(router.currentRoute.value.params.id).toBe('order-7')
    })

    it('確定沒建立的失敗之後，重送換新的 key', async () => {
      serveBatch([batch('a', 100)])
      const keys: (string | null)[] = []
      let calls = 0
      server.use(
        http.post('*/orders', ({ request }) => {
          keys.push(request.headers.get('Idempotency-Key'))
          calls++
          return calls === 1
            ? problem(409, 'OUT_OF_STOCK', 'out of stock: SKU-a.')
            : HttpResponse.json(order(), { status: 201 })
        }),
      )
      const { wrapper } = await mountCheckout([{ productId: 'a', qty: 1 }])

      await submitButton(wrapper).trigger('click')
      await flushPromises()
      await submitButton(wrapper).trigger('click')
      await flushPromises()

      expect(keys).toHaveLength(2)
      expect(keys[1]).not.toBe(keys[0])
    })

    it('其他錯誤（VALIDATION_FAILED）：顯示後端的訊息', async () => {
      serveBatch([batch('a', 100)])
      server.use(
        http.post('*/orders', () =>
          problem(400, 'VALIDATION_FAILED', 'items[0].qty: must be ≤ 99'),
        ),
      )
      const { wrapper } = await mountCheckout([{ productId: 'a', qty: 1 }])

      await submitButton(wrapper).trigger('click')
      await flushPromises()

      expect(wrapper.find('[role="alert"]').text()).toContain('items[0].qty: must be ≤ 99')
    })
  })
})
