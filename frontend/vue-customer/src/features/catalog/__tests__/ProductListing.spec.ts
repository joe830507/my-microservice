import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { delay, http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { createPinia } from 'pinia'
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { routes } from '@/router'
import { last, product, productPage } from './fixtures'

const server = setupServer(http.get('*/categories/tree', () => HttpResponse.json({ roots: [] })))

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

// 記錄 GET /products 收到的查詢參數
function productsReturning(handler: (params: URLSearchParams) => Response | Promise<Response>) {
  const calls: URLSearchParams[] = []
  server.use(
    http.get('*/products', ({ request }) => {
      const params = new URL(request.url).searchParams
      calls.push(params)
      return handler(params)
    }),
  )
  return calls
}

async function mountAt(path: string): Promise<{ wrapper: VueWrapper; router: Router }> {
  const router = createRouter({ history: createMemoryHistory(), routes })
  await router.push(path)
  await router.isReady()
  const App = { template: '<RouterView />' }
  const wrapper = mount(App, { global: { plugins: [createPinia(), router] } })
  await flushPromises()
  return { wrapper, router }
}

describe('搜尋頁（ProductListing）', () => {
  it('URL 條件轉成 API 參數；列出商品與總數', async () => {
    const calls = productsReturning(() =>
      HttpResponse.json(productPage([product(1), product(2)], { totalElements: 2 })),
    )

    const { wrapper } = await mountAt(
      '/search?q=%E9%9E%8B&minPrice=100&maxPrice=900&sort=price-asc&page=2',
    )

    expect(Object.fromEntries(calls[0] ?? [])).toEqual({
      keyword: '鞋',
      minPrice: '100',
      maxPrice: '900',
      page: '1',
      size: '12',
      sortBy: 'price',
      sortDirection: 'asc',
    })
    expect(wrapper.find('h1').text()).toBe('搜尋「鞋」')
    expect(wrapper.findAll('.product-card')).toHaveLength(2)
    expect(wrapper.text()).toContain('共 2 件商品')
  })

  it('沒有關鍵字：標題是全部商品，不帶 keyword', async () => {
    const calls = productsReturning(() => HttpResponse.json(productPage([product(1)])))

    const { wrapper } = await mountAt('/search')

    expect(wrapper.find('h1').text()).toBe('全部商品')
    expect(calls[0]?.has('keyword')).toBe(false)
    expect(calls[0]?.has('categoryId')).toBe(false)
  })

  it('沒有符合的商品：顯示空結果', async () => {
    productsReturning(() => HttpResponse.json(productPage([])))

    const { wrapper } = await mountAt('/search?q=zzz')

    expect(wrapper.text()).toContain('找不到符合條件的商品')
    expect(wrapper.find('.product-card').exists()).toBe(false)
  })

  it('切換排序：寫進網址、回到第一頁並重新查詢', async () => {
    const calls = productsReturning(() =>
      HttpResponse.json(productPage([product(1)], { totalPages: 3 })),
    )
    const { wrapper, router } = await mountAt('/search?page=3')

    await wrapper.find('select').setValue('price-desc')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({ sort: 'price-desc' })
    expect(last(calls)?.get('sortBy')).toBe('price')
    expect(last(calls)?.get('sortDirection')).toBe('desc')
    expect(last(calls)?.get('page')).toBe('0')
  })

  it('價格區間：套用後寫進網址；清除後移除', async () => {
    productsReturning(() => HttpResponse.json(productPage([product(1)])))
    const { wrapper, router } = await mountAt('/search?q=a')

    await wrapper.find('input[name="minPrice"]').setValue('100')
    await wrapper.find('input[name="maxPrice"]').setValue('500')
    await wrapper.find('.listing__price').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({ q: 'a', minPrice: '100', maxPrice: '500' })

    await wrapper.find('.listing__clear').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({ q: 'a' })
  })

  it.each([
    ['最低價高於最高價', '500', '100', '最低價不能高於最高價'],
    ['不是數字', 'abc', '', '價格請輸入數字'],
  ])('價格區間不合法（%s）：顯示訊息，網址不變', async (_, min, max, message) => {
    const calls = productsReturning(() => HttpResponse.json(productPage([product(1)])))
    const { wrapper, router } = await mountAt('/search')

    await wrapper.find('input[name="minPrice"]').setValue(min)
    await wrapper.find('input[name="maxPrice"]').setValue(max)
    await wrapper.find('.listing__price').trigger('submit')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toContain(message)
    expect(router.currentRoute.value.query).toEqual({})
    expect(calls).toHaveLength(1)
  })

  it('分頁：點頁碼換頁並保留其他條件', async () => {
    const calls = productsReturning(() =>
      HttpResponse.json(productPage([product(1)], { totalPages: 3, totalElements: 30 })),
    )
    const { wrapper, router } = await mountAt('/search?q=a')

    const next = wrapper.findAll('.pagination button').find((b) => b.text() === '2')
    await next?.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({ q: 'a', page: '2' })
    expect(last(calls)?.get('page')).toBe('1')
    expect(last(calls)?.get('keyword')).toBe('a')
  })

  it('查詢失敗：顯示錯誤，按重試可以再查', async () => {
    let fail = true
    productsReturning(() =>
      fail
        ? HttpResponse.json(
            { status: 500, detail: '伺服器忙碌', code: 'INTERNAL_ERROR' },
            { status: 500 },
          )
        : HttpResponse.json(productPage([product(1)])),
    )
    const { wrapper } = await mountAt('/search')

    expect(wrapper.find('[role="alert"]').text()).toContain('伺服器忙碌')

    fail = false
    await wrapper.find('.listing__retry').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.findAll('.product-card')).toHaveLength(1)
  })

  it('快速切換條件：舊的請求晚回來也不會蓋掉新的結果', async () => {
    productsReturning(async (params) => {
      if (params.get('sortBy') === 'createdAt') {
        await delay(80)
        return HttpResponse.json(productPage([product(1, { name: '舊結果' })]))
      }
      return HttpResponse.json(productPage([product(2, { name: '新結果' })]))
    })
    const { wrapper } = await mountAt('/search')

    await wrapper.find('select').setValue('price-asc')
    await vi.waitFor(() => expect(wrapper.text()).toContain('新結果'))
    await delay(150)
    await flushPromises()

    expect(wrapper.text()).toContain('新結果')
    expect(wrapper.text()).not.toContain('舊結果')
  })
})
