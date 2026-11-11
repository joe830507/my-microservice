import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { createPinia, setActivePinia } from 'pinia'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { installAuthGuard, routes } from '@/router'
import type { BatchProduct } from '../api'
import { CART_KEY, useCartStore } from '../store'

const server = setupServer(http.get('*/categories/tree', () => HttpResponse.json({ roots: [] })))

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())
beforeEach(() => localStorage.clear())

function batch(id: string, price: number, status = 'ACTIVE'): BatchProduct {
  return { id, sku: `SKU-${id}`, name: `商品 ${id}`, price, status }
}

// 記錄 /products/batch 的查詢字串
function serveBatch(products: BatchProduct[]) {
  const urls: URL[] = []
  server.use(
    http.get('*/products/batch', ({ request }) => {
      urls.push(new URL(request.url))
      return HttpResponse.json({ products })
    }),
  )
  return urls
}

async function mountCart(
  items: { productId: string; qty: number }[],
): Promise<{ wrapper: VueWrapper; router: Router }> {
  localStorage.setItem(CART_KEY, JSON.stringify(items))
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({ history: createMemoryHistory(), routes })
  installAuthGuard(router)
  await router.push('/cart')
  await router.isReady()
  const wrapper = mount({ template: '<RouterView />' }, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return { wrapper, router }
}

const lineTexts = (wrapper: VueWrapper) => wrapper.findAll('.cart-line').map((line) => line.text())

describe('購物車頁', () => {
  it('空的購物車：顯示提示，不查商品', async () => {
    // 沒有註冊 /products/batch 的 handler：發出請求會因 onUnhandledRequest: 'error' 失敗
    const { wrapper } = await mountCart([])

    expect(wrapper.text()).toContain('購物車是空的')
    expect(wrapper.find('.cart-line').exists()).toBe(false)
    expect(wrapper.find('a.btn').attributes('href')).toBe('/search')
  })

  it('用 batch 查最新的名稱與價格；ids 是重複的 key（ids=a&ids=b）', async () => {
    const urls = serveBatch([batch('a', 100), batch('b', 250.5)])

    const { wrapper } = await mountCart([
      { productId: 'a', qty: 2 },
      { productId: 'b', qty: 1 },
    ])

    expect(urls[0]?.search).toBe('?ids=a&ids=b')
    const [first, second] = lineTexts(wrapper)
    expect(first).toContain('商品 a')
    expect(first).toContain('NT$ 100')
    expect(first).toContain('NT$ 200') // 小計
    expect(second).toContain('NT$ 250.5')
    expect(wrapper.find('.cart-summary__total').text()).toContain('NT$ 450.5')
  })

  it('修改數量：小計與總計即時更新，重新整理後還在', async () => {
    serveBatch([batch('a', 100)])
    const { wrapper } = await mountCart([{ productId: 'a', qty: 1 }])

    await wrapper.find('[aria-label="增加數量"]').trigger('click')
    await wrapper.find('[aria-label="增加數量"]').trigger('click')

    expect(wrapper.find('.cart-line__subtotal').text()).toBe('NT$ 300')
    expect(wrapper.find('.cart-summary__total').text()).toContain('NT$ 300')
    expect(useCartStore().items).toEqual([{ productId: 'a', qty: 3 }])
    expect(JSON.parse(localStorage.getItem(CART_KEY) ?? '[]')).toEqual([{ productId: 'a', qty: 3 }])
  })

  it('移除商品：從列表、總計與 localStorage 拿掉；全部移除後顯示空的購物車', async () => {
    serveBatch([batch('a', 100), batch('b', 50)])
    const { wrapper } = await mountCart([
      { productId: 'a', qty: 1 },
      { productId: 'b', qty: 1 },
    ])

    await wrapper.find('[aria-label="移除 商品 a"]').trigger('click')

    expect(lineTexts(wrapper)).toHaveLength(1)
    expect(wrapper.find('.cart-summary__total').text()).toContain('NT$ 50')
    expect(JSON.parse(localStorage.getItem(CART_KEY) ?? '[]')).toEqual([{ productId: 'b', qty: 1 }])

    await wrapper.find('[aria-label="移除 商品 b"]').trigger('click')

    expect(wrapper.text()).toContain('購物車是空的')
  })

  describe('無法購買的商品', () => {
    const items = [
      { productId: 'ok', qty: 2 },
      { productId: 'off', qty: 5 },
      { productId: 'gone', qty: 1 },
    ]

    it('已下架與查不到的標成無法購買，不計入總計，沒有數量選擇', async () => {
      serveBatch([batch('ok', 100), batch('off', 999, 'INACTIVE')])

      const { wrapper } = await mountCart(items)

      const lines = wrapper.findAll('.cart-line')
      expect(lines[0]?.classes()).not.toContain('is-unavailable')
      expect(lines[1]?.classes()).toContain('is-unavailable')
      expect(lines[1]?.text()).toContain('無法購買')
      expect(lines[1]?.find('input').exists()).toBe(false)
      expect(lines[2]?.text()).toContain('商品已不存在')
      expect(wrapper.find('.cart-summary__total').text()).toContain('NT$ 200')
      expect(wrapper.text()).toContain('有 2 項商品已無法購買')
    })

    it('後端對非上架商品只回 id 與 status（沒有名稱、價格）：顯示「此商品已下架」', async () => {
      serveBatch([
        batch('ok', 100),
        { id: 'off', sku: null, name: null, price: null, status: 'DRAFT' },
      ])

      const { wrapper } = await mountCart([
        { productId: 'ok', qty: 1 },
        { productId: 'off', qty: 3 },
      ])

      const line = wrapper.findAll('.cart-line')[1]
      expect(line?.text()).toContain('此商品已下架')
      expect(line?.text()).toContain('無法購買')
      expect(line?.find('[aria-label="移除 此商品已下架"]').exists()).toBe(true)
      expect(wrapper.find('.cart-summary__total').text()).toContain('NT$ 100')
    })

    it('可以單獨移除，或一次全部移除', async () => {
      serveBatch([batch('ok', 100), batch('off', 999, 'INACTIVE')])
      const { wrapper } = await mountCart(items)

      await wrapper.find('[role="alert"] button').trigger('click')

      expect(useCartStore().items).toEqual([{ productId: 'ok', qty: 2 }])
      expect(wrapper.find('.is-unavailable').exists()).toBe(false)
      expect(wrapper.text()).not.toContain('已無法購買')
    })

    it('全部都無法購買：不能前往結帳', async () => {
      serveBatch([batch('off', 999, 'ARCHIVED')])

      const { wrapper } = await mountCart([{ productId: 'off', qty: 1 }])

      expect(wrapper.find('.cart-summary .btn').attributes('disabled')).toBeDefined()
      expect(wrapper.find('.cart-summary__total').text()).toContain('NT$ 0')
    })
  })

  it('查詢失敗：顯示錯誤，購物車內容保留，重試後顯示', async () => {
    let fail = true
    server.use(
      http.get('*/products/batch', () =>
        fail
          ? HttpResponse.json({ status: 503, detail: '商品服務忙碌', code: 'X' }, { status: 503 })
          : HttpResponse.json({ products: [batch('a', 100)] }),
      ),
    )
    const { wrapper } = await mountCart([{ productId: 'a', qty: 1 }])

    expect(wrapper.find('[role="alert"]').text()).toContain('商品服務忙碌')
    expect(wrapper.find('.cart-line').exists()).toBe(false)
    expect(useCartStore().items).toHaveLength(1)

    fail = false
    await wrapper.find('[role="alert"] button').trigger('click')
    await flushPromises()

    expect(lineTexts(wrapper)).toHaveLength(1)
  })

  it('前往結帳：未登入導到登入頁，登入後回到結帳頁', async () => {
    serveBatch([batch('a', 100)])
    const { wrapper, router } = await mountCart([{ productId: 'a', qty: 1 }])

    await wrapper.find('.cart-summary .btn').trigger('click')

    // 登入頁是動態 import，導覽要等載入完才結束
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('login'))
    expect(router.currentRoute.value.query.redirect).toBe('/checkout')
  })
})
