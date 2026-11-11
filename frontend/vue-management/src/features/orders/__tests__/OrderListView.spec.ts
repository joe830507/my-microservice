import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { problem } from '@/features/auth/__tests__/fixtures'
import { routes } from '@/router'
import OrderListView from '../OrderListView.vue'
import { listItem, OTHER_USER_ID, page, USER_ID } from './fixtures'

const server = setupServer()

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterAll(() => server.close())

let requests: Record<string, string>[] = []

function lastRequest(): Record<string, string> | undefined {
  return requests[requests.length - 1]
}

beforeEach(() => {
  requests = []
  server.use(
    http.get('*/orders', ({ request }) => {
      requests.push(Object.fromEntries(new URL(request.url).searchParams))
      return HttpResponse.json(
        page([listItem('o1', 'PAID'), listItem('o2', 'CANCELLED', OTHER_USER_ID)], {
          totalElements: 45,
          totalPages: 3,
        }),
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
  const wrapper = mount(OrderListView, { global: { plugins: [router] }, attachTo: document.body })
  await flushPromises()
  return { wrapper, router }
}

describe('OrderListView', () => {
  it('沒有條件：第一頁、每頁 20 筆', async () => {
    await mountAt('/orders')

    expect(requests).toEqual([{ page: '0', size: '20' }])
  })

  it('URL 的條件轉成 API 參數，並帶入篩選表單', async () => {
    const { wrapper } = await mountAt(`/orders?status=PAID&userId=${USER_ID}&page=2`)

    expect(requests).toEqual([{ status: 'PAID', userId: USER_ID, page: '1', size: '20' }])
    expect(wrapper.find<HTMLInputElement>('[name="userId"]').element.value).toBe(USER_ID)
  })

  it('列表顯示狀態與金額', async () => {
    const { wrapper } = await mountAt('/orders')

    const rows = wrapper.findAll('tr.el-table__row')
    expect(rows[0]?.text()).toContain('已付款')
    expect(rows[0]?.text()).toContain('2,400')
    expect(rows[1]?.text()).toContain('已取消')
  })

  it('搜尋使用者：寫進 URL 並回到第一頁', async () => {
    const { wrapper, router } = await mountAt('/orders?page=3')

    await wrapper.find('[name="userId"]').setValue(`  ${USER_ID}  `)
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({ userId: USER_ID })
    expect(lastRequest()).toEqual({ userId: USER_ID, page: '0', size: '20' })
  })

  it('使用者 ID 格式不對：提示，不改 URL', async () => {
    const { wrapper, router } = await mountAt('/orders')

    await wrapper.find('[name="userId"]').setValue('not-a-uuid')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('.filter-error').text()).toContain('使用者 ID 格式不正確')
    expect(router.currentRoute.value.query).toEqual({})
  })

  it('點列表上的使用者 ID：只看這個使用者的訂單', async () => {
    const { wrapper, router } = await mountAt('/orders')

    await wrapper.findAll('tr.el-table__row')[1]?.find('.user-filter').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({ userId: OTHER_USER_ID })
  })

  it('換頁', async () => {
    const { wrapper, router } = await mountAt('/orders?status=PAID')

    await wrapper.find('.el-pagination .btn-next').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({ status: 'PAID', page: '2' })
    expect(lastRequest()).toEqual({ status: 'PAID', page: '1', size: '20' })
  })

  it('清除條件', async () => {
    const { wrapper, router } = await mountAt(`/orders?status=PAID&userId=${USER_ID}`)

    await wrapper.find('.reset-button').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({})
  })

  it('讀取失敗：顯示錯誤', async () => {
    server.use(http.get('*/orders', () => problem(500, 'INTERNAL_ERROR', 'boom')))

    const { wrapper } = await mountAt('/orders')

    expect(wrapper.find('.load-error').text()).toContain('無法讀取訂單：boom')
  })

  it('按「查看」進入訂單詳情', async () => {
    const { wrapper, router } = await mountAt('/orders')

    await wrapper.findAll('tr.el-table__row')[0]?.find('.detail-button').trigger('click')

    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('order-detail'))
    expect(router.currentRoute.value.params.id).toBe('o1')
  })
})
