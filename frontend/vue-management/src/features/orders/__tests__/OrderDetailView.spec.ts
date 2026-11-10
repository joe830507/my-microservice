import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { confirmDanger } from '@/composables/useConfirm'
import { problem } from '@/features/auth/__tests__/fixtures'
import { routes } from '@/router'
import OrderDetailView from '../OrderDetailView.vue'
import type { OrderStatus } from '../types'
import { ORDER_ID, order } from './fixtures'

vi.mock('@/composables/useConfirm', () => ({
  confirmDanger: vi.fn<(message: string, title?: string) => Promise<boolean>>(),
}))

const server = setupServer()

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterAll(() => server.close())

beforeEach(() => {
  vi.mocked(confirmDanger).mockReset()
})

afterEach(() => {
  server.resetHandlers()
  ElMessage.closeAll()
  document.body.innerHTML = ''
})

async function mountOrder(status: OrderStatus): Promise<VueWrapper> {
  server.use(http.get(`*/orders/${ORDER_ID}`, () => HttpResponse.json(order(status))))
  const router = createRouter({ history: createMemoryHistory(), routes })
  await router.push(`/orders/${ORDER_ID}`)
  await router.isReady()
  const wrapper = mount(OrderDetailView, { global: { plugins: [router] }, attachTo: document.body })
  await flushPromises()
  return wrapper
}

describe('OrderDetailView 顯示', () => {
  it('訂單資訊、商品快照與小計', async () => {
    const wrapper = await mountOrder('PAID')

    expect(wrapper.find('.status-tag').text()).toBe('已付款')
    expect(wrapper.find('.total-amount').text()).toBe('2,650')
    const rows = wrapper.findAll('.order-items tr.el-table__row')
    expect(rows).toHaveLength(2)
    expect(rows[0]?.text()).toContain('Runner（下單時的名稱）')
    expect(rows[0]?.text()).toContain('1,200')
    expect(rows[0]?.text()).toContain('2,400')
  })

  it('只顯示發生過的階段時間', async () => {
    const wrapper = await mountOrder('SHIPPED')

    const labels = wrapper.findAll('.el-timeline-item').map((item) => item.text())
    expect(labels.map((text) => text.replace(/[\d/:\s]+/g, '').trim())).toEqual([
      '建立',
      '付款',
      '出貨',
    ])
  })

  it('訂單不存在：顯示錯誤', async () => {
    server.use(http.get('*/orders/missing', () => problem(404, 'ORDER_NOT_FOUND')))
    const router = createRouter({ history: createMemoryHistory(), routes })
    await router.push('/orders/missing')
    await router.isReady()
    const wrapper = mount(OrderDetailView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.find('.load-error').text()).toContain('訂單不存在')
    expect(wrapper.find('.order-summary').exists()).toBe(false)
  })
})

describe('OrderDetailView 按鈕依狀態顯示', () => {
  it.each<[OrderStatus, string[]]>([
    ['PENDING_PAYMENT', ['取消訂單']],
    ['PAID', ['出貨']],
    ['SHIPPED', ['完成訂單']],
    ['COMPLETED', []],
    ['CANCELLED', []],
  ])('%s：%j', async (status, labels) => {
    const wrapper = await mountOrder(status)

    expect(wrapper.findAll('.order-action').map((button) => button.text())).toEqual(labels)
  })
})

describe('OrderDetailView 操作', () => {
  it.each<[OrderStatus, string, OrderStatus, string]>([
    ['PENDING_PAYMENT', 'cancel', 'CANCELLED', '已取消'],
    ['PAID', 'ship', 'SHIPPED', '已出貨'],
    ['SHIPPED', 'complete', 'COMPLETED', '已完成'],
  ])('%s → %s：確認後呼叫 API，用回應更新', async (from, action, to, label) => {
    vi.mocked(confirmDanger).mockResolvedValue(true)
    let called = false
    server.use(
      http.post(`*/orders/${ORDER_ID}/${action}`, () => {
        called = true
        return HttpResponse.json(order(to))
      }),
    )
    const wrapper = await mountOrder(from)

    await wrapper.find('.order-action').trigger('click')
    await flushPromises()

    expect(called).toBe(true)
    expect(wrapper.find('.status-tag').text()).toBe(label)
  })

  it('取消確認視窗提醒會釋放庫存；按取消就不發請求', async () => {
    vi.mocked(confirmDanger).mockResolvedValue(false)
    // 沒有註冊 POST 的 handler：發出請求會因 onUnhandledRequest: 'error' 失敗
    const wrapper = await mountOrder('PENDING_PAYMENT')

    await wrapper.find('.order-action').trigger('click')
    await flushPromises()

    expect(confirmDanger).toHaveBeenCalledWith(expect.stringContaining('釋放預扣的庫存'))
    expect(wrapper.find('.status-tag').text()).toBe('待付款')
  })

  it('取消失敗（使用者剛付款，ORDER_NOT_CANCELLABLE）：顯示原因', async () => {
    vi.mocked(confirmDanger).mockResolvedValue(true)
    server.use(
      http.post(`*/orders/${ORDER_ID}/cancel`, () => problem(409, 'ORDER_NOT_CANCELLABLE')),
    )
    const wrapper = await mountOrder('PENDING_PAYMENT')

    await wrapper.find('.order-action').trigger('click')
    await flushPromises()

    expect(wrapper.find('.action-error').text()).toContain('只有待付款的訂單可以取消')
  })

  it('出貨失敗（ORDER_NOT_SHIPPABLE）：顯示原因', async () => {
    vi.mocked(confirmDanger).mockResolvedValue(true)
    server.use(http.post(`*/orders/${ORDER_ID}/ship`, () => problem(409, 'ORDER_NOT_SHIPPABLE')))
    const wrapper = await mountOrder('PAID')

    await wrapper.find('.order-action').trigger('click')
    await flushPromises()

    expect(wrapper.find('.action-error').text()).toContain('只有已付款的訂單可以出貨')
  })
})
