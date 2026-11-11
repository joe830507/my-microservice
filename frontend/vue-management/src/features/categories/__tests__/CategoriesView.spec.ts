import { flushPromises, mount, type DOMWrapper, type VueWrapper } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { confirmDanger } from '@/composables/useConfirm'
import { problem } from '@/features/auth/__tests__/fixtures'
import CategoriesView from '../CategoriesView.vue'
import { allCategories, boots } from './fixtures'

// 確認視窗直接 mock，不操作 ElMessageBox 的 DOM
vi.mock('@/composables/useConfirm', () => ({
  confirmDanger: vi.fn<(message: string, title?: string) => Promise<boolean>>(),
}))

const server = setupServer()

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterAll(() => server.close())

let listCalls = 0

beforeEach(() => {
  listCalls = 0
  vi.mocked(confirmDanger).mockReset()
  server.use(
    http.get('*/categories', () => {
      listCalls++
      return HttpResponse.json({ categories: allCategories })
    }),
  )
})

afterEach(() => {
  server.resetHandlers()
  ElMessage.closeAll()
  document.body.innerHTML = ''
})

async function mountView(): Promise<VueWrapper> {
  const wrapper = mount(CategoriesView, { attachTo: document.body })
  await flushPromises()
  return wrapper
}

function row(wrapper: VueWrapper, name: string): DOMWrapper<Element> {
  const found = wrapper.findAll('tr.el-table__row').find((tr) => tr.text().includes(name))
  if (!found) {
    throw new Error(`找不到 ${name} 這一列`)
  }
  return found
}

// 記錄送出的 body
function recordRequests(method: 'post' | 'put' | 'delete', path: string, response: () => Response) {
  const bodies: unknown[] = []
  server.use(
    http[method](path, async ({ request }) => {
      bodies.push(method === 'delete' ? null : await request.json())
      return response()
    }),
  )
  return bodies
}

async function fill(wrapper: VueWrapper, name: string, value: string): Promise<void> {
  await wrapper.find(`.el-dialog [name="${name}"]`).setValue(value)
}

async function submitDialog(wrapper: VueWrapper): Promise<void> {
  await wrapper.find('.el-dialog .submit-button').trigger('click')
  await flushPromises()
}

describe('CategoriesView 列表', () => {
  it('以樹狀顯示所有分類，包含停用的', async () => {
    const wrapper = await mountView()

    const rows = wrapper.findAll('tr.el-table__row')
    expect(rows.map((tr) => tr.find('td').text())).toEqual(['Bags', 'Shoes', 'Boots'])
    expect(row(wrapper, 'Bags').text()).toContain('停用')
    expect(row(wrapper, 'Shoes').text()).toContain('啟用')
  })

  it('讀取失敗：顯示錯誤', async () => {
    server.use(http.get('*/categories', () => problem(403, 'FORBIDDEN', 'forbidden')))

    const wrapper = await mountView()

    expect(wrapper.find('.load-error').text()).toContain('無法讀取分類：forbidden')
  })
})

describe('CategoriesView 新增', () => {
  it('新增頂層分類：送出表單內容，成功後關閉對話框並重新讀取', async () => {
    const bodies = recordRequests('post', '*/categories', () =>
      HttpResponse.json(boots, { status: 201 }),
    )
    const wrapper = await mountView()

    await wrapper.find('.create-button').trigger('click')
    await fill(wrapper, 'name', '  Hats ')
    await fill(wrapper, 'slug', 'hats')
    await submitDialog(wrapper)

    expect(bodies).toEqual([
      { parentId: null, name: 'Hats', slug: 'hats', description: null, sortOrder: 0, active: true },
    ])
    expect(wrapper.find('.el-dialog').exists()).toBe(false)
    expect(listCalls).toBe(2)
  })

  it('從某一列新增子分類：預設上層分類是那一列', async () => {
    const bodies = recordRequests('post', '*/categories', () =>
      HttpResponse.json(boots, { status: 201 }),
    )
    const wrapper = await mountView()

    await row(wrapper, 'Shoes').find('.create-child-button').trigger('click')
    await fill(wrapper, 'name', 'Sandals')
    await fill(wrapper, 'slug', 'sandals')
    await submitDialog(wrapper)

    expect(bodies).toEqual([expect.objectContaining({ parentId: 'shoes-id', name: 'Sandals' })])
  })

  it('必填欄位沒填：顯示驗證訊息，不發請求', async () => {
    // 沒有註冊 POST 的 handler：發出請求會因 onUnhandledRequest: 'error' 失敗
    const wrapper = await mountView()

    await wrapper.find('.create-button').trigger('click')
    await submitDialog(wrapper)

    // 驗證訊息在下一次渲染才出現
    await vi.waitFor(() => {
      const dialogText = wrapper.find('.el-dialog').text()
      expect(dialogText).toContain('請輸入名稱')
      expect(dialogText).toContain('請輸入 slug')
    })
  })

  it('名稱只有空白：視為沒填', async () => {
    const wrapper = await mountView()

    await wrapper.find('.create-button').trigger('click')
    await fill(wrapper, 'name', '   ')
    await fill(wrapper, 'slug', 'hats')
    await submitDialog(wrapper)

    await vi.waitFor(() => expect(wrapper.find('.el-dialog').text()).toContain('請輸入名稱'))
  })

  it.each(['Mens Shoes', 'mens--shoes', '-shoes', 'shoes-', 'Shoes'])(
    'slug 格式不對（%s）：顯示規則，不發請求',
    async (slug) => {
      const wrapper = await mountView()

      await wrapper.find('.create-button').trigger('click')
      await fill(wrapper, 'name', 'Shoes')
      await fill(wrapper, 'slug', slug)
      await submitDialog(wrapper)

      await vi.waitFor(() =>
        expect(wrapper.find('.el-dialog').text()).toContain(
          '只能用小寫英文、數字，以單一連字號分隔',
        ),
      )
    },
  )

  it('slug 重複：在對話框顯示訊息，對話框不關閉', async () => {
    server.use(http.post('*/categories', () => problem(409, 'CATEGORY_SLUG_DUPLICATED')))
    const wrapper = await mountView()

    await wrapper.find('.create-button').trigger('click')
    await fill(wrapper, 'name', 'Shoes 2')
    await fill(wrapper, 'slug', 'shoes')
    await submitDialog(wrapper)

    expect(wrapper.find('.el-dialog .form-error').text()).toContain('這個 slug 已被其他分類使用')
    expect(listCalls).toBe(1)
  })
})

describe('CategoriesView 編輯', () => {
  it('帶入目前的值，送出時帶上 version', async () => {
    const bodies = recordRequests('put', '*/categories/boots-id', () => HttpResponse.json(boots))
    const wrapper = await mountView()

    await row(wrapper, 'Boots').find('.edit-button').trigger('click')
    const nameInput = wrapper.find<HTMLInputElement>('.el-dialog [name="name"]')
    expect(nameInput.element.value).toBe('Boots')
    await nameInput.setValue('Winter Boots')
    await submitDialog(wrapper)

    expect(bodies).toEqual([
      {
        parentId: 'shoes-id',
        name: 'Winter Boots',
        slug: 'boots',
        description: null,
        sortOrder: 0,
        active: true,
        version: 3,
      },
    ])
    expect(listCalls).toBe(2)
  })

  it('已被其他人修改（VERSION_CONFLICT）：提示重新整理', async () => {
    server.use(http.put('*/categories/boots-id', () => problem(409, 'VERSION_CONFLICT')))
    const wrapper = await mountView()

    await row(wrapper, 'Boots').find('.edit-button').trigger('click')
    await submitDialog(wrapper)

    expect(wrapper.find('.el-dialog .form-error').text()).toContain(
      '分類已被其他人修改，請重新整理後再試',
    )
  })
})

describe('CategoriesView 刪除', () => {
  it('確認後刪除並重新讀取', async () => {
    vi.mocked(confirmDanger).mockResolvedValue(true)
    const bodies = recordRequests('delete', '*/categories/bags-id', () => new HttpResponse(null))
    const wrapper = await mountView()

    await row(wrapper, 'Bags').find('.delete-button').trigger('click')
    await flushPromises()

    expect(confirmDanger).toHaveBeenCalledWith(expect.stringContaining('Bags'))
    expect(bodies).toHaveLength(1)
    expect(listCalls).toBe(2)
    await vi.waitFor(() => expect(document.body.textContent).toContain('分類已刪除'))
  })

  it('取消：不發請求', async () => {
    vi.mocked(confirmDanger).mockResolvedValue(false)
    // 沒有註冊 DELETE 的 handler：發出請求會因 onUnhandledRequest: 'error' 失敗
    const wrapper = await mountView()

    await row(wrapper, 'Bags').find('.delete-button').trigger('click')
    await flushPromises()

    expect(listCalls).toBe(1)
  })

  it('底下還有子分類或商品（DATA_CONFLICT）：顯示無法刪除的原因', async () => {
    vi.mocked(confirmDanger).mockResolvedValue(true)
    server.use(http.delete('*/categories/shoes-id', () => problem(409, 'DATA_CONFLICT')))
    const wrapper = await mountView()

    await row(wrapper, 'Shoes').find('.delete-button').trigger('click')
    await flushPromises()

    await vi.waitFor(() =>
      expect(document.body.textContent).toContain('這個分類底下還有子分類或商品，無法刪除'),
    )
  })
})
