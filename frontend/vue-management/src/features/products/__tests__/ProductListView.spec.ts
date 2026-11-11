import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { problem } from '@/features/auth/__tests__/fixtures'
import { allCategories } from '@/features/categories/__tests__/fixtures'
import { routes } from '@/router'
import ProductListView from '../ProductListView.vue'
import { listItem, page } from './fixtures'

const server = setupServer()

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterAll(() => server.close())

// 每次 GET /products 收到的查詢參數
let requests: Record<string, string>[] = []

beforeEach(() => {
  requests = []
  server.use(
    http.get('*/categories', () => HttpResponse.json({ categories: allCategories })),
    http.get('*/products', ({ request }) => {
      requests.push(Object.fromEntries(new URL(request.url).searchParams))
      return HttpResponse.json(
        page(
          [
            listItem({
              id: 'p1',
              name: 'Runner',
              categoryId: 'shoes-id',
              price: 1234.5,
              status: 'ACTIVE',
            }),
            listItem({ id: 'p2', name: 'Draft Bag', categoryId: null, status: 'DRAFT' }),
          ],
          { totalElements: 45, totalPages: 3 },
        ),
      )
    }),
  )
})

afterEach(() => {
  server.resetHandlers()
  document.body.innerHTML = ''
})

async function mountAt(url: string): Promise<{ wrapper: VueWrapper; router: Router }> {
  const router = createRouter({ history: createMemoryHistory(), routes })
  await router.push(url)
  await router.isReady()
  const wrapper = mount(ProductListView, {
    global: { plugins: [router] },
    attachTo: document.body,
  })
  await flushPromises()
  return { wrapper, router }
}

// 專案的 lib 是 ES2020，沒有 Array.prototype.at
function lastRequest(): Record<string, string> | undefined {
  return requests[requests.length - 1]
}

const DEFAULT_REQUEST = { page: '0', size: '20', sortBy: 'createdAt', sortDirection: 'desc' }

describe('ProductListView 讀取', () => {
  it('沒有條件：用預設的分頁與排序', async () => {
    await mountAt('/products')

    expect(requests).toEqual([DEFAULT_REQUEST])
  })

  it('URL 的條件轉成 API 參數（page 減 1），並帶入篩選表單', async () => {
    const { wrapper } = await mountAt(
      '/products?keyword=run&status=ACTIVE&minPrice=100&maxPrice=2000&page=2&sortBy=price&sortDirection=asc',
    )

    expect(requests).toEqual([
      {
        keyword: 'run',
        status: 'ACTIVE',
        minPrice: '100',
        maxPrice: '2000',
        page: '1',
        size: '20',
        sortBy: 'price',
        sortDirection: 'asc',
      },
    ])
    expect(wrapper.find<HTMLInputElement>('[name="keyword"]').element.value).toBe('run')
    expect(wrapper.find<HTMLInputElement>('[name="minPrice"]').element.value).toBe('100')
  })

  it('列表顯示分類名稱、價格、狀態', async () => {
    const { wrapper } = await mountAt('/products')

    const rows = wrapper.findAll('tr.el-table__row')
    expect(rows).toHaveLength(2)
    expect(rows[0]?.text()).toContain('Runner')
    expect(rows[0]?.text()).toContain('Shoes')
    expect(rows[0]?.text()).toContain('1,234.5')
    expect(rows[0]?.text()).toContain('上架中')
    expect(rows[1]?.text()).toContain('草稿')
  })

  it('讀取失敗：顯示錯誤', async () => {
    server.use(http.get('*/products', () => problem(500, 'INTERNAL_ERROR', 'boom')))

    const { wrapper } = await mountAt('/products')

    expect(wrapper.find('.load-error').text()).toContain('無法讀取商品：boom')
  })
})

describe('ProductListView 條件同步到 URL', () => {
  it('搜尋：條件寫進 URL、回到第一頁，並重新讀取', async () => {
    const { wrapper, router } = await mountAt('/products?page=3')

    await wrapper.find('[name="keyword"]').setValue('  鞋  ')
    await wrapper.find('[name="minPrice"]').setValue('100')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({ keyword: '鞋', minPrice: '100' })
    expect(lastRequest()).toEqual({ ...DEFAULT_REQUEST, keyword: '鞋', minPrice: '100' })
  })

  it('最低價格大於最高價格：提示，不改 URL', async () => {
    const { wrapper, router } = await mountAt('/products')

    await wrapper.find('[name="minPrice"]').setValue('500')
    await wrapper.find('[name="maxPrice"]').setValue('100')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('.filter-error').text()).toContain('最低價格不能大於最高價格')
    expect(router.currentRoute.value.query).toEqual({})
    expect(requests).toHaveLength(1)
  })

  it('價格不是數字：提示，不改 URL', async () => {
    const { wrapper, router } = await mountAt('/products')

    await wrapper.find('[name="minPrice"]').setValue('abc')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('.filter-error').text()).toContain('價格只能是數字')
    expect(router.currentRoute.value.query).toEqual({})
  })

  it('換頁：URL 的 page 加 1，API 的 page 從 0 開始', async () => {
    const { wrapper, router } = await mountAt('/products?keyword=run')

    await wrapper.find('.el-pagination .btn-next').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({ keyword: 'run', page: '2' })
    expect(lastRequest()).toEqual({ ...DEFAULT_REQUEST, keyword: 'run', page: '1' })
  })

  it('清除條件：回到預設', async () => {
    const { wrapper, router } = await mountAt('/products?keyword=run&page=2&status=DRAFT')

    await wrapper.find('.reset-button').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({})
    expect(wrapper.find<HTMLInputElement>('[name="keyword"]').element.value).toBe('')
  })

  it('點欄位排序：寫進 URL 並回到第一頁', async () => {
    const { wrapper, router } = await mountAt('/products?page=2')

    const priceHeader = wrapper.findAll('th').find((th) => th.text().includes('售價'))
    await priceHeader?.find('.sort-caret.ascending').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({ sortBy: 'price', sortDirection: 'asc' })
    expect(lastRequest()).toEqual({ ...DEFAULT_REQUEST, sortBy: 'price', sortDirection: 'asc' })
  })

  it('上一頁：回到之前的條件並重新讀取', async () => {
    const { wrapper, router } = await mountAt('/products')

    await wrapper.find('[name="keyword"]').setValue('run')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    router.back()
    await vi.waitFor(() => expect(router.currentRoute.value.query).toEqual({}))
    await flushPromises()

    expect(lastRequest()).toEqual(DEFAULT_REQUEST)
    expect(wrapper.find<HTMLInputElement>('[name="keyword"]').element.value).toBe('')
  })

  it('按「編輯」進入商品編輯頁', async () => {
    const { wrapper, router } = await mountAt('/products')

    await wrapper.findAll('tr.el-table__row')[0]?.find('.edit-button').trigger('click')

    // 編輯頁是動態 import，導覽要等元件載入完成
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('product-edit'))
    expect(router.currentRoute.value.params.id).toBe('p1')
  })
})
