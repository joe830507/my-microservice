import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { problem } from '@/features/auth/__tests__/fixtures'
import { routes } from '@/router'
import { DEFAULT_USER_QUERY, parseUserQuery, toUserApiParams, toUserRouteQuery } from '../query'
import type { UserListItem } from '../types'
import UserListView from '../UserListView.vue'

const server = setupServer()

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterAll(() => server.close())

const ADMIN: UserListItem = {
  id: '0193a1b2-0000-7000-8000-000000000001',
  email: 'admin@example.com',
  displayName: '管理員',
  status: 'ACTIVE',
  roles: ['ADMIN', 'CUSTOMER'],
  createdAt: '2026-11-01T00:00:00Z',
}
const DISABLED: UserListItem = {
  id: '0193a1b2-0000-7000-8000-000000000002',
  email: 'bob@example.com',
  displayName: 'Bob',
  status: 'DISABLED',
  roles: ['CUSTOMER'],
  createdAt: '2026-11-02T00:00:00Z',
}

let requests: Record<string, string>[] = []

function lastRequest(): Record<string, string> | undefined {
  return requests[requests.length - 1]
}

const DEFAULT_REQUEST = { page: '0', size: '20', sortBy: 'createdAt', sortDirection: 'desc' }

beforeEach(() => {
  requests = []
  server.use(
    http.get('*/users', ({ request }) => {
      requests.push(Object.fromEntries(new URL(request.url).searchParams))
      return HttpResponse.json({
        items: [ADMIN, DISABLED],
        page: 0,
        size: 20,
        totalElements: 45,
        totalPages: 3,
      })
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
  const wrapper = mount(UserListView, { global: { plugins: [router] }, attachTo: document.body })
  await flushPromises()
  return { wrapper, router }
}

describe('使用者列表的 URL 條件', () => {
  it('預設值、合法值、不合法值', () => {
    expect(parseUserQuery({})).toEqual(DEFAULT_USER_QUERY)
    expect(
      parseUserQuery({ keyword: ' bob ', status: 'DISABLED', role: 'ADMIN', sortBy: 'email' }),
    ).toEqual({
      ...DEFAULT_USER_QUERY,
      keyword: 'bob',
      status: 'DISABLED',
      role: 'ADMIN',
      sortBy: 'email',
    })
    expect(
      parseUserQuery({ status: 'BANNED', role: 'ROOT', page: '0', sortBy: 'password' }),
    ).toEqual(DEFAULT_USER_QUERY)
  })

  it('URL 省略預設值；API 的 page 從 0 開始', () => {
    const query = { ...DEFAULT_USER_QUERY, role: 'ADMIN' as const, page: 2 }

    expect(toUserRouteQuery(query)).toEqual({ role: 'ADMIN', page: '2' })
    expect(toUserApiParams(query)).toEqual({
      keyword: undefined,
      status: undefined,
      role: 'ADMIN',
      page: 1,
      size: 20,
      sortBy: 'createdAt',
      sortDirection: 'desc',
    })
  })
})

describe('UserListView', () => {
  it('沒有條件：預設的分頁與排序', async () => {
    await mountAt('/users')

    expect(requests).toEqual([DEFAULT_REQUEST])
  })

  it('URL 的條件轉成 API 參數，並帶入篩選表單', async () => {
    const { wrapper } = await mountAt('/users?keyword=bob&status=DISABLED&page=2')

    expect(requests).toEqual([
      { ...DEFAULT_REQUEST, keyword: 'bob', status: 'DISABLED', page: '1' },
    ])
    expect(wrapper.find<HTMLInputElement>('[name="keyword"]').element.value).toBe('bob')
  })

  it('列表顯示角色與狀態', async () => {
    const { wrapper } = await mountAt('/users')

    const rows = wrapper.findAll('tr.el-table__row')
    expect(rows[0]?.text()).toContain('admin@example.com')
    expect(rows[0]?.findAll('.role-tag').map((tag) => tag.text())).toEqual(['管理員', '顧客'])
    expect(rows[0]?.text()).toContain('啟用')
    expect(rows[1]?.text()).toContain('停用')
  })

  it('搜尋：條件寫進 URL 並回到第一頁', async () => {
    const { wrapper, router } = await mountAt('/users?page=3')

    await wrapper.find('[name="keyword"]').setValue('  Bob  ')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({ keyword: 'Bob' })
    expect(lastRequest()).toEqual({ ...DEFAULT_REQUEST, keyword: 'Bob' })
  })

  it('換頁', async () => {
    const { wrapper, router } = await mountAt('/users?role=CUSTOMER')

    await wrapper.find('.el-pagination .btn-next').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({ role: 'CUSTOMER', page: '2' })
    expect(lastRequest()).toEqual({ ...DEFAULT_REQUEST, role: 'CUSTOMER', page: '1' })
  })

  it('點 Email 欄位排序', async () => {
    const { wrapper, router } = await mountAt('/users')

    const header = wrapper.findAll('th').find((th) => th.text().includes('Email'))
    await header?.find('.sort-caret.ascending').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({ sortBy: 'email', sortDirection: 'asc' })
    expect(lastRequest()).toEqual({ ...DEFAULT_REQUEST, sortBy: 'email', sortDirection: 'asc' })
  })

  it('清除條件', async () => {
    const { wrapper, router } = await mountAt('/users?keyword=bob&role=ADMIN')

    await wrapper.find('.reset-button').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({})
  })

  it('讀取失敗：顯示錯誤', async () => {
    server.use(http.get('*/users', () => problem(403, 'FORBIDDEN', 'forbidden')))

    const { wrapper } = await mountAt('/users')

    expect(wrapper.find('.load-error').text()).toContain('無法讀取使用者：forbidden')
  })

  it('查看訂單：到訂單列表並篩選這個使用者', async () => {
    const { wrapper, router } = await mountAt('/users')

    await wrapper.findAll('tr.el-table__row')[1]?.find('.orders-button').trigger('click')

    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('orders'))
    expect(router.currentRoute.value.query).toEqual({ userId: DISABLED.id })
  })
})
