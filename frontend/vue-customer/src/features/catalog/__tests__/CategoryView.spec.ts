import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { createPinia } from 'pinia'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { routes } from '@/router'
import { categoryTree, product, productPage } from './fixtures'

const server = setupServer(
  http.get('*/categories/tree', () => HttpResponse.json({ roots: categoryTree })),
)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

const shoes = {
  id: 'cat-shoes',
  parentId: null,
  name: '鞋子',
  slug: 'shoes',
  description: '各式鞋款',
  sortOrder: 1,
}

async function mountAt(path: string): Promise<VueWrapper> {
  const router = createRouter({ history: createMemoryHistory(), routes })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(
    { template: '<RouterView />' },
    { global: { plugins: [createPinia(), router] } },
  )
  await flushPromises()
  return wrapper
}

describe('分類頁 /c/:slug', () => {
  it('用 slug 找分類，列出該分類與子分類的商品（includeSubcategories=true）', async () => {
    server.use(
      http.get('*/categories/slug/shoes', () => HttpResponse.json(shoes)),
      http.get('*/products', ({ request }) => {
        const params = new URL(request.url).searchParams
        expect(params.get('categoryId')).toBe('cat-shoes')
        expect(params.get('includeSubcategories')).toBe('true')
        return HttpResponse.json(productPage([product(1), product(2)]))
      }),
    )

    const wrapper = await mountAt('/c/shoes')

    expect(wrapper.find('h1').text()).toBe('鞋子')
    expect(wrapper.text()).toContain('各式鞋款')
    expect(wrapper.findAll('.product-card')).toHaveLength(2)
  })

  it('有子分類：顯示子分類連結', async () => {
    server.use(
      http.get('*/categories/slug/shoes', () => HttpResponse.json(shoes)),
      http.get('*/products', () => HttpResponse.json(productPage([product(1)]))),
    )

    const wrapper = await mountAt('/c/shoes')

    const links = wrapper.findAll('.subcategories a').map((a) => [a.text(), a.attributes('href')])
    expect(links).toEqual([['靴子', '/c/boots']])
  })

  it('分類不存在（或已停用）：顯示 404，不查商品', async () => {
    // 沒有註冊 /products 的 handler：發出請求會因 onUnhandledRequest: 'error' 失敗
    server.use(
      http.get('*/categories/slug/nope', () =>
        HttpResponse.json(
          { status: 404, detail: 'category not found', code: 'CATEGORY_NOT_FOUND' },
          { status: 404 },
        ),
      ),
    )

    const wrapper = await mountAt('/c/nope')

    expect(wrapper.find('h1').text()).toBe('404')
  })

  it('其他錯誤：顯示錯誤訊息，按重試可以再查', async () => {
    let fail = true
    server.use(
      http.get('*/categories/slug/shoes', () =>
        fail
          ? HttpResponse.json({ status: 500, detail: '伺服器忙碌', code: 'X' }, { status: 500 })
          : HttpResponse.json(shoes),
      ),
      http.get('*/products', () => HttpResponse.json(productPage([product(1)]))),
    )
    const wrapper = await mountAt('/c/shoes')

    expect(wrapper.find('[role="alert"]').text()).toContain('伺服器忙碌')

    fail = false
    await wrapper.find('[role="alert"] button').trigger('click')
    await flushPromises()

    expect(wrapper.find('h1').text()).toBe('鞋子')
  })
})
