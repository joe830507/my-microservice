import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { createPinia } from 'pinia'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { categoryTree, product, productPage } from '@/features/catalog/__tests__/fixtures'
import { routes } from '@/router'
import HomeView from '../HomeView.vue'

const server = setupServer()

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

async function mountHome() {
  const router = createRouter({ history: createMemoryHistory(), routes })
  await router.push('/')
  await router.isReady()
  const wrapper = mount(HomeView, { global: { plugins: [createPinia(), router] } })
  await flushPromises()
  return wrapper
}

describe('HomeView', () => {
  it('顯示分類入口與最新上架的 8 件商品', async () => {
    let params: URLSearchParams | undefined
    server.use(
      http.get('*/categories/tree', () => HttpResponse.json({ roots: categoryTree })),
      http.get('*/products', ({ request }) => {
        params = new URL(request.url).searchParams
        return HttpResponse.json(productPage([product(1), product(2)]))
      }),
    )

    const wrapper = await mountHome()

    expect(wrapper.findAll('.home-categories a').map((a) => a.text())).toEqual(['鞋子', '包包'])
    expect(wrapper.findAll('.product-card')).toHaveLength(2)
    expect(params?.get('size')).toBe('8')
    expect(params?.get('sortBy')).toBe('createdAt')
    expect(params?.get('sortDirection')).toBe('desc')
    expect(wrapper.find('.home-more a').attributes('href')).toBe('/search')
  })

  it('還沒有商品：顯示提示', async () => {
    server.use(
      http.get('*/categories/tree', () => HttpResponse.json({ roots: [] })),
      http.get('*/products', () => HttpResponse.json(productPage([]))),
    )

    const wrapper = await mountHome()

    expect(wrapper.text()).toContain('商品即將上架')
    expect(wrapper.find('.home-categories').exists()).toBe(false)
  })

  it('分類載入失敗：分類入口不出現，商品照常顯示', async () => {
    server.use(
      http.get('*/categories/tree', () => HttpResponse.error()),
      http.get('*/products', () => HttpResponse.json(productPage([product(1)]))),
    )

    const wrapper = await mountHome()

    expect(wrapper.find('.home-categories').exists()).toBe(false)
    expect(wrapper.findAll('.product-card')).toHaveLength(1)
  })

  it('商品載入失敗：顯示錯誤，按重試可以再查', async () => {
    let fail = true
    server.use(
      http.get('*/categories/tree', () => HttpResponse.json({ roots: [] })),
      http.get('*/products', () =>
        fail
          ? HttpResponse.json({ status: 500, detail: '伺服器忙碌', code: 'X' }, { status: 500 })
          : HttpResponse.json(productPage([product(1)])),
      ),
    )
    const wrapper = await mountHome()

    expect(wrapper.find('[role="alert"]').text()).toContain('伺服器忙碌')

    fail = false
    await wrapper.find('[role="alert"] button').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.product-card')).toHaveLength(1)
  })
})
