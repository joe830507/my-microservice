import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { confirmDanger } from '@/composables/useConfirm'
import { problem } from '@/features/auth/__tests__/fixtures'
import { allCategories } from '@/features/categories/__tests__/fixtures'
import { routes } from '@/router'
import ProductEditView from '../ProductEditView.vue'
import type { Product, ProductStatus } from '../types'
import { product } from './fixtures'

vi.mock('@/composables/useConfirm', () => ({
  confirmDanger: vi.fn<(message: string, title?: string) => Promise<boolean>>(),
}))

const server = setupServer()

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterAll(() => server.close())

beforeEach(() => {
  vi.mocked(confirmDanger).mockReset()
  server.use(
    http.get('*/categories', () => HttpResponse.json({ categories: allCategories })),
    // 編輯頁也會讀圖片；圖片的行為另外在 ProductImages.spec.ts 測
    http.get('*/products/:id/images', ({ params }) =>
      HttpResponse.json({ productId: params.id, images: [] }),
    ),
  )
})

afterEach(() => {
  server.resetHandlers()
  ElMessage.closeAll()
  document.body.innerHTML = ''
})

function serveProduct(current: Product): void {
  server.use(http.get(`*/products/${current.id}`, () => HttpResponse.json(current)))
}

async function mountAt(url: string): Promise<{ wrapper: VueWrapper; router: Router }> {
  const router = createRouter({ history: createMemoryHistory(), routes })
  await router.push(url)
  await router.isReady()
  const wrapper = mount(ProductEditView, {
    global: { plugins: [router] },
    attachTo: document.body,
  })
  await flushPromises()
  return { wrapper, router }
}

async function setNumber(wrapper: VueWrapper, name: string, value: string): Promise<void> {
  const input = wrapper.find(`[name="${name}"]`)
  await input.setValue(value)
  await input.trigger('change')
  await input.trigger('blur')
}

function button(wrapper: VueWrapper, cls: string) {
  return wrapper.find<HTMLButtonElement>(`.${cls}`)
}

describe('ProductEditView 新增', () => {
  it('送出表單內容，成功後進入編輯頁', async () => {
    const bodies: unknown[] = []
    server.use(
      http.post('*/products', async ({ request }) => {
        bodies.push(await request.json())
        return HttpResponse.json(product('DRAFT', { id: 'new-id' }), { status: 201 })
      }),
    )
    serveProduct(product('DRAFT', { id: 'new-id' }))
    const { wrapper, router } = await mountAt('/products/new')

    expect(wrapper.find('.status-bar').exists()).toBe(false)
    await wrapper.find('[name="sku"]').setValue(' SKU-9 ')
    await wrapper.find('[name="name"]').setValue('Boot')
    await setNumber(wrapper, 'price', '880')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(bodies).toEqual([
      {
        sku: 'SKU-9',
        name: 'Boot',
        description: null,
        categoryId: null,
        brand: null,
        price: 880,
        originalPrice: null,
      },
    ])
    expect(router.currentRoute.value.name).toBe('product-edit')
    expect(router.currentRoute.value.params.id).toBe('new-id')
  })

  it('必填欄位沒填：顯示驗證訊息，不發請求', async () => {
    const { wrapper } = await mountAt('/products/new')

    await wrapper.find('form').trigger('submit')

    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('請輸入 SKU')
      expect(wrapper.text()).toContain('請輸入名稱')
      expect(wrapper.text()).toContain('請輸入售價')
    })
  })

  it('原價低於售價：顯示驗證訊息，不發請求', async () => {
    const { wrapper } = await mountAt('/products/new')

    await wrapper.find('[name="sku"]').setValue('SKU-9')
    await wrapper.find('[name="name"]').setValue('Boot')
    await setNumber(wrapper, 'price', '880')
    await setNumber(wrapper, 'originalPrice', '500')
    await wrapper.find('form').trigger('submit')

    await vi.waitFor(() => expect(wrapper.text()).toContain('原價不能低於售價'))
  })

  it('SKU 重複（DATA_CONFLICT）：顯示訊息，留在新增頁', async () => {
    server.use(http.post('*/products', () => problem(409, 'DATA_CONFLICT')))
    const { wrapper, router } = await mountAt('/products/new')

    await wrapper.find('[name="sku"]').setValue('SKU-001')
    await wrapper.find('[name="name"]').setValue('Boot')
    await setNumber(wrapper, 'price', '880')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('.form-error').text()).toContain('SKU 已被其他商品使用')
    expect(router.currentRoute.value.name).toBe('product-create')
  })
})

describe('ProductEditView 編輯', () => {
  it('帶入目前的值，送出時帶上 version，成功後用回應更新', async () => {
    serveProduct(product('DRAFT'))
    const bodies: unknown[] = []
    server.use(
      http.put('*/products/p1', async ({ request }) => {
        bodies.push(await request.json())
        return HttpResponse.json(product('DRAFT', { name: 'Runner 2', version: 3 }))
      }),
    )
    const { wrapper } = await mountAt('/products/p1')

    const nameInput = wrapper.find<HTMLInputElement>('[name="name"]')
    expect(nameInput.element.value).toBe('Runner')
    await nameInput.setValue('Runner 2')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(bodies).toEqual([
      {
        sku: 'SKU-001',
        name: 'Runner 2',
        description: null,
        categoryId: 'shoes-id',
        brand: 'ACME',
        price: 1200,
        originalPrice: 1500,
        version: 2,
      },
    ])
    await vi.waitFor(() => expect(document.body.textContent).toContain('商品已更新'))
  })

  it('已被其他人修改（VERSION_CONFLICT）：提示重新整理', async () => {
    serveProduct(product('DRAFT'))
    server.use(http.put('*/products/p1', () => problem(409, 'VERSION_CONFLICT')))
    const { wrapper } = await mountAt('/products/p1')

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('.form-error').text()).toContain('商品已被其他人修改，請重新整理後再試')
  })

  it('商品不存在：顯示錯誤，不顯示表單', async () => {
    server.use(http.get('*/products/missing', () => problem(404, 'PRODUCT_NOT_FOUND')))
    const { wrapper } = await mountAt('/products/missing')

    expect(wrapper.find('.load-error').text()).toContain('商品不存在')
    expect(wrapper.find('form').exists()).toBe(false)
  })

  it('有未儲存的修改：提醒上架檢查的是已儲存的內容', async () => {
    serveProduct(product('DRAFT'))
    const { wrapper } = await mountAt('/products/p1')

    expect(wrapper.find('.dirty-notice').exists()).toBe(false)
    await wrapper.find('[name="name"]').setValue('Changed')

    expect(wrapper.find('.dirty-notice').exists()).toBe(true)
  })
})

describe('ProductEditView 狀態按鈕', () => {
  it.each<[ProductStatus, boolean, boolean, boolean]>([
    // 狀態, 上架, 下架, 封存（true = 可以按）
    ['DRAFT', true, false, true],
    ['ACTIVE', false, true, true],
    ['INACTIVE', true, false, true],
    ['ARCHIVED', false, false, false],
  ])('%s：依目前狀態啟用或停用', async (status, publish, unpublish, archive) => {
    serveProduct(product(status))
    const { wrapper } = await mountAt('/products/p1')

    expect(button(wrapper, 'publish-button').element.disabled).toBe(!publish)
    expect(button(wrapper, 'unpublish-button').element.disabled).toBe(!unpublish)
    expect(button(wrapper, 'archive-button').element.disabled).toBe(!archive)
  })

  it('封存的商品不能修改：表單停用、沒有儲存按鈕', async () => {
    serveProduct(product('ARCHIVED'))
    const { wrapper } = await mountAt('/products/p1')

    expect(wrapper.find('.archived-notice').text()).toContain('商品已封存，不能修改')
    expect(wrapper.find<HTMLInputElement>('[name="name"]').element.disabled).toBe(true)
    expect(wrapper.find('.save-button').exists()).toBe(false)
  })

  it('上架成功：狀態與 version 更新，下一個操作帶新的 version', async () => {
    serveProduct(product('DRAFT'))
    const versions: unknown[] = []
    server.use(
      http.post('*/products/p1/publish', async ({ request }) => {
        versions.push(await request.json())
        return HttpResponse.json({ id: 'p1', status: 'ACTIVE', updatedAt: 'x', version: 3 })
      }),
      http.post('*/products/p1/unpublish', async ({ request }) => {
        versions.push(await request.json())
        return HttpResponse.json({ id: 'p1', status: 'INACTIVE', updatedAt: 'y', version: 4 })
      }),
    )
    const { wrapper } = await mountAt('/products/p1')

    await button(wrapper, 'publish-button').trigger('click')
    await flushPromises()
    expect(wrapper.find('.status-tag').text()).toBe('上架中')
    expect(button(wrapper, 'publish-button').element.disabled).toBe(true)

    await button(wrapper, 'unpublish-button').trigger('click')
    await flushPromises()

    expect(versions).toEqual([{ version: 2 }, { version: 3 }])
    expect(wrapper.find('.status-tag').text()).toBe('已下架')
  })

  it('上架失敗（沒有主圖）：依錯誤代碼顯示訊息，狀態不變', async () => {
    serveProduct(product('DRAFT'))
    server.use(
      http.post('*/products/p1/publish', () => problem(400, 'PRODUCT_PRIMARY_IMAGE_REQUIRED')),
    )
    const { wrapper } = await mountAt('/products/p1')

    await button(wrapper, 'publish-button').trigger('click')
    await flushPromises()

    expect(wrapper.find('.form-error').text()).toContain('上架前要先設定主圖')
    expect(wrapper.find('.status-tag').text()).toBe('草稿')
  })

  it('上架失敗（沒有啟用中的分類）：依錯誤代碼顯示訊息', async () => {
    serveProduct(product('DRAFT', { categoryId: null }))
    server.use(
      http.post('*/products/p1/publish', () => problem(400, 'PRODUCT_ACTIVE_CATEGORY_REQUIRED')),
    )
    const { wrapper } = await mountAt('/products/p1')

    await button(wrapper, 'publish-button').trigger('click')
    await flushPromises()

    expect(wrapper.find('.form-error').text()).toContain('上架中的商品必須設定啟用中的分類')
  })

  it('封存：取消確認就不發請求', async () => {
    vi.mocked(confirmDanger).mockResolvedValue(false)
    serveProduct(product('INACTIVE'))
    const { wrapper } = await mountAt('/products/p1')

    await button(wrapper, 'archive-button').trigger('click')
    await flushPromises()

    expect(confirmDanger).toHaveBeenCalledWith(expect.stringContaining('Runner'))
    expect(wrapper.find('.status-tag').text()).toBe('已下架')
  })

  it('封存：確認後表單變成唯讀', async () => {
    vi.mocked(confirmDanger).mockResolvedValue(true)
    serveProduct(product('INACTIVE'))
    server.use(
      http.post('*/products/p1/archive', () =>
        HttpResponse.json({ id: 'p1', status: 'ARCHIVED', updatedAt: 'z', version: 3 }),
      ),
    )
    const { wrapper } = await mountAt('/products/p1')

    await button(wrapper, 'archive-button').trigger('click')
    await flushPromises()

    expect(wrapper.find('.status-tag').text()).toBe('已封存')
    expect(wrapper.find('.archived-notice').exists()).toBe(true)
    expect(wrapper.find('.save-button').exists()).toBe(false)
  })
})
