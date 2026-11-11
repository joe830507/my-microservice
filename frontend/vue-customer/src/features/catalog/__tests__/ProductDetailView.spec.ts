import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { createPinia, setActivePinia } from 'pinia'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { CART_KEY, useCartStore } from '@/features/cart/store'
import { routes } from '@/router'
import { product } from './fixtures'
import type { Product, ProductImage } from '../types'

const server = setupServer(http.get('*/categories/tree', () => HttpResponse.json({ roots: [] })))

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())
beforeEach(() => localStorage.clear())

const PRODUCT_ID = '00000000-0000-4000-8000-000000000001'

function detail(overrides: Partial<Product> = {}): Product {
  const { primaryImageUrl: _ignored, ...rest } = product(1)
  return {
    ...rest,
    description: '第一行\n第二行',
    updatedAt: '2026-11-02T00:00:00Z',
    name: '運動鞋',
    brand: 'Nike',
    price: 800,
    originalPrice: 1000,
    ...overrides,
  }
}

function image(n: number): ProductImage {
  return {
    id: `img-${n}`,
    url: `http://img.example/${n}.jpg`,
    altText: null,
    sortOrder: n,
    isPrimary: n === 1,
  }
}

function problemResponse(status: number, code: string) {
  return HttpResponse.json({ status, detail: code, code }, { status })
}

interface Backend {
  product?: () => Response
  images?: () => Response
  stock?: () => Response
}

function serve(backend: Backend = {}) {
  server.use(
    http.get('*/products/:id', backend.product ?? (() => HttpResponse.json(detail()))),
    http.get(
      '*/products/:id/images',
      backend.images ??
        (() => HttpResponse.json({ productId: PRODUCT_ID, images: [image(1), image(2)] })),
    ),
    http.get(
      '*/inventory/:sku',
      backend.stock ??
        (() => HttpResponse.json({ sku: 'SKU-1', available: 10, reserved: 0, updatedAt: 'x' })),
    ),
  )
}

async function mountAt(path = `/p/${PRODUCT_ID}`): Promise<VueWrapper> {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({ history: createMemoryHistory(), routes })
  await router.push(path)
  await router.isReady()
  const wrapper = mount({ template: '<RouterView />' }, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return wrapper
}

describe('商品詳情', () => {
  it('顯示名稱、品牌、價格與折扣、說明、圖片', async () => {
    serve()

    const wrapper = await mountAt()

    expect(wrapper.find('h1').text()).toBe('運動鞋')
    expect(wrapper.find('.detail__brand').text()).toBe('Nike')
    expect(wrapper.find('.detail__price').text()).toContain('NT$ 800')
    expect(wrapper.find('.detail__price').text()).toContain('-20%')
    expect(wrapper.find('.detail__description').text()).toContain('第一行')
    expect(wrapper.find('.gallery__main img').attributes('src')).toBe('http://img.example/1.jpg')
    expect(wrapper.findAll('.gallery__thumbs button')).toHaveLength(2)
  })

  it('說明裡的 HTML 當成文字顯示，不會被執行', async () => {
    serve({
      product: () => HttpResponse.json(detail({ description: '<img src=x onerror=alert(1)>' })),
    })

    const wrapper = await mountAt()

    expect(wrapper.find('.detail__description').text()).toContain('<img src=x onerror=alert(1)>')
    expect(wrapper.find('.detail__description img').exists()).toBe(false)
  })

  describe('找不到', () => {
    it.each([
      ['商品不存在或已下架（404）', () => problemResponse(404, 'PRODUCT_NOT_FOUND')],
      ['id 不是合法的 UUID（400）', () => problemResponse(400, 'VALIDATION_FAILED')],
    ])('%s：顯示找不到，不查圖片與庫存', async (_, product) => {
      // 沒有註冊圖片、庫存的 handler：發出請求會因 onUnhandledRequest: 'error' 失敗
      server.use(http.get('*/products/:id', product))

      const wrapper = await mountAt('/p/whatever')

      expect(wrapper.find('h1').text()).toBe('404')
    })

    it('其他錯誤：顯示錯誤訊息，按重試可以再查', async () => {
      let fail = true
      serve({
        product: () =>
          fail ? problemResponse(500, 'INTERNAL_ERROR') : HttpResponse.json(detail()),
      })
      const wrapper = await mountAt()

      expect(wrapper.find('[role="alert"]').text()).toContain('INTERNAL_ERROR')

      fail = false
      await wrapper.find('[role="alert"] button').trigger('click')
      await flushPromises()

      expect(wrapper.find('h1').text()).toBe('運動鞋')
    })
  })

  describe('庫存', () => {
    it('有庫存：顯示尚有庫存，可以加入購物車', async () => {
      serve()

      const wrapper = await mountAt()

      expect(wrapper.find('.detail__stock').text()).toBe('尚有庫存')
      expect(wrapper.find('.detail__add').attributes('disabled')).toBeUndefined()
    })

    it('庫存少：顯示僅剩 N 件', async () => {
      serve({
        stock: () => HttpResponse.json({ sku: 'SKU-1', available: 3, reserved: 0, updatedAt: 'x' }),
      })

      const wrapper = await mountAt()

      expect(wrapper.find('.detail__stock').text()).toBe('僅剩 3 件')
    })

    it.each([
      [
        '可賣數量是 0',
        () => HttpResponse.json({ sku: 'SKU-1', available: 0, reserved: 5, updatedAt: 'x' }),
      ],
      ['還沒有庫存資料（STOCK_NOT_FOUND）', () => problemResponse(404, 'STOCK_NOT_FOUND')],
    ])('%s：顯示缺貨，不能加入購物車', async (_, stock) => {
      serve({ stock })

      const wrapper = await mountAt()

      expect(wrapper.find('.detail__stock').text()).toBe('缺貨')
      expect(wrapper.find('.detail__add').attributes('disabled')).toBeDefined()
      await wrapper.find('.detail__add').trigger('click')
      expect(useCartStore().count).toBe(0)
    })

    it('庫存查詢失敗：商品資訊仍顯示，不能加入購物車，重試後恢復', async () => {
      let fail = true
      serve({
        stock: () =>
          fail
            ? problemResponse(503, 'SERVICE_UNAVAILABLE')
            : HttpResponse.json({ sku: 'SKU-1', available: 10, reserved: 0, updatedAt: 'x' }),
      })
      const wrapper = await mountAt()

      expect(wrapper.find('h1').text()).toBe('運動鞋')
      expect(wrapper.find('[role="alert"]').text()).toContain('庫存查詢失敗')
      expect(wrapper.find('.detail__add').attributes('disabled')).toBeDefined()

      fail = false
      await wrapper.find('[role="alert"] button').trigger('click')
      await flushPromises()

      expect(wrapper.find('.detail__stock').text()).toBe('尚有庫存')
      expect(wrapper.find('.detail__add').attributes('disabled')).toBeUndefined()
    })
  })

  it('圖片載入失敗：顯示預設圖，商品資訊照常', async () => {
    serve({ images: () => problemResponse(500, 'INTERNAL_ERROR') })

    const wrapper = await mountAt()

    expect(wrapper.find('h1').text()).toBe('運動鞋')
    expect(wrapper.find('[aria-label="尚無商品圖片"]').exists()).toBe(true)
  })

  describe('加入購物車', () => {
    it('選擇數量後加入：購物車增加、存進 localStorage、顯示確認', async () => {
      serve()
      const wrapper = await mountAt()

      await wrapper.find('[aria-label="增加數量"]').trigger('click')
      await wrapper.find('[aria-label="增加數量"]').trigger('click')
      await wrapper.find('.detail__add').trigger('click')

      const cart = useCartStore()
      expect(cart.items).toEqual([{ productId: PRODUCT_ID, qty: 3 }])
      expect(cart.count).toBe(3)
      expect(JSON.parse(localStorage.getItem(CART_KEY) ?? '[]')).toEqual(cart.items)
      expect(wrapper.find('[role="status"]').text()).toContain('已加入 3 件')
    })

    it('再加入同一商品：數量合併', async () => {
      serve()
      const wrapper = await mountAt()

      await wrapper.find('.detail__add').trigger('click')
      await wrapper.find('.detail__add').trigger('click')

      expect(useCartStore().items).toEqual([{ productId: PRODUCT_ID, qty: 2 }])
    })

    it('數量不能超過可賣數量', async () => {
      serve({
        stock: () => HttpResponse.json({ sku: 'SKU-1', available: 3, reserved: 0, updatedAt: 'x' }),
      })
      const wrapper = await mountAt()

      const input = wrapper.find('input[aria-label="數量"]')
      ;(input.element as HTMLInputElement).value = '50'
      await input.trigger('change')
      await wrapper.find('.detail__add').trigger('click')

      expect(useCartStore().items).toEqual([{ productId: PRODUCT_ID, qty: 3 }])
      expect(wrapper.find('[aria-label="增加數量"]').attributes('disabled')).toBeDefined()
    })

    it('可賣數量超過 99：上限仍是 99', async () => {
      serve({
        stock: () =>
          HttpResponse.json({ sku: 'SKU-1', available: 500, reserved: 0, updatedAt: 'x' }),
      })
      const wrapper = await mountAt()

      const input = wrapper.find('input[aria-label="數量"]')
      ;(input.element as HTMLInputElement).value = '500'
      await input.trigger('change')
      await wrapper.find('.detail__add').trigger('click')

      expect(useCartStore().items).toEqual([{ productId: PRODUCT_ID, qty: 99 }])
    })
  })
})
