import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { problem } from '@/features/auth/__tests__/fixtures'
import ProductInventory from '../ProductInventory.vue'
import type { Stock } from '../api'

const server = setupServer()

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterAll(() => server.close())

// 伺服器上目前的庫存；null 代表還沒建立（GET 回 404 STOCK_NOT_FOUND）
let stored: Stock | null = null
let getCalls: string[] = []
let putBodies: unknown[] = []

function stock(available: number, reserved = 0): Stock {
  return { sku: 'SKU-001', available, reserved, updatedAt: '2026-11-01T08:00:00Z' }
}

beforeEach(() => {
  stored = stock(10, 2)
  getCalls = []
  putBodies = []
  server.use(
    http.get('*/inventory/:sku', ({ params }) => {
      getCalls.push(String(params.sku))
      return stored ? HttpResponse.json(stored) : problem(404, 'STOCK_NOT_FOUND')
    }),
    http.put('*/inventory/:sku', async ({ request }) => {
      const body = (await request.json()) as { available: number }
      putBodies.push(body)
      const created = stored === null
      stored = stock(body.available, stored?.reserved ?? 0)
      return HttpResponse.json(stored, { status: created ? 201 : 200 })
    }),
  )
})

afterEach(() => {
  server.resetHandlers()
  ElMessage.closeAll()
  document.body.innerHTML = ''
})

async function mountInventory(sku = 'SKU-001', readOnly = false): Promise<VueWrapper> {
  const wrapper = mount(ProductInventory, { props: { sku, readOnly }, attachTo: document.body })
  await flushPromises()
  return wrapper
}

async function setAvailable(wrapper: VueWrapper, value: string): Promise<void> {
  const input = wrapper.find('[name="available"]')
  await input.setValue(value)
  await input.trigger('change')
  await input.trigger('blur')
}

async function save(wrapper: VueWrapper): Promise<void> {
  await wrapper.find('.inventory-form').trigger('submit')
  await flushPromises()
}

describe('ProductInventory', () => {
  it('顯示可賣數量與已預扣數量，表單帶入目前的可賣數量', async () => {
    const wrapper = await mountInventory()

    expect(wrapper.find('.stock-available').text()).toBe('10')
    expect(wrapper.find('.stock-reserved').text()).toBe('2')
    expect(wrapper.find<HTMLInputElement>('[name="available"]').element.value).toBe('10')
    expect(wrapper.find('.stock-save').text()).toBe('更新庫存')
  })

  it('調整成功後重新讀取', async () => {
    const wrapper = await mountInventory()

    await setAvailable(wrapper, '25')
    await save(wrapper)

    expect(putBodies).toEqual([{ available: 25 }])
    expect(getCalls).toHaveLength(2)
    expect(wrapper.find('.stock-available').text()).toBe('25')
  })

  it('SKU 還沒建立庫存：顯示提示，設定後建立', async () => {
    stored = null
    const wrapper = await mountInventory()

    expect(wrapper.find('.stock-missing').text()).toContain('還沒有建立庫存')
    expect(wrapper.find('.stock-save').text()).toBe('建立庫存')

    await setAvailable(wrapper, '5')
    await save(wrapper)

    expect(putBodies).toEqual([{ available: 5 }])
    expect(wrapper.find('.stock-missing').exists()).toBe(false)
    expect(wrapper.find('.stock-available').text()).toBe('5')
  })

  it('沒有填數量：提示，不發請求', async () => {
    const wrapper = await mountInventory()

    await setAvailable(wrapper, '')
    await save(wrapper)

    expect(wrapper.find('.inventory-error').text()).toContain('可賣數量要是 0 以上的整數')
    expect(putBodies).toEqual([])
  })

  it('儲存失敗：顯示原因', async () => {
    server.use(http.put('*/inventory/:sku', () => problem(403, 'FORBIDDEN', 'forbidden')))
    const wrapper = await mountInventory()

    await save(wrapper)

    expect(wrapper.find('.inventory-error').text()).toContain('無法儲存庫存：forbidden')
  })

  it('讀取失敗（不是 404）：顯示錯誤', async () => {
    server.use(http.get('*/inventory/:sku', () => problem(503, 'SERVICE_UNAVAILABLE', 'down')))
    const wrapper = await mountInventory()

    expect(wrapper.find('.inventory-error').text()).toContain('無法讀取庫存：down')
    expect(wrapper.find('.inventory-form').exists()).toBe(false)
  })

  it('SKU 放在路徑上會編碼', async () => {
    let rawPath = ''
    server.use(
      http.get('*/inventory/*', ({ request }) => {
        rawPath = new URL(request.url).pathname
        return HttpResponse.json(stock(1))
      }),
    )

    await mountInventory('A/B 1')

    expect(rawPath.endsWith('/inventory/A%2FB%201')).toBe(true)
  })

  it('SKU 改了：讀新的 SKU', async () => {
    const wrapper = await mountInventory('SKU-001')

    await wrapper.setProps({ sku: 'SKU-002' })
    await flushPromises()

    expect(getCalls).toEqual(['SKU-001', 'SKU-002'])
  })

  it('唯讀（商品已封存）：只顯示，不能調整', async () => {
    const wrapper = await mountInventory('SKU-001', true)

    expect(wrapper.find('.stock-available').text()).toBe('10')
    expect(wrapper.find('.inventory-form').exists()).toBe(false)
  })
})
