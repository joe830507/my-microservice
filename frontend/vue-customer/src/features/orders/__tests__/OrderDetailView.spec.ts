import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { delay, http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { createPinia, setActivePinia } from 'pinia'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { me } from '@/features/auth/__tests__/fixtures'
import { useAuthStore } from '@/features/auth/store'
import { installAuthGuard, routes } from '@/router'
import { minutesAgo, order } from './fixtures'
import type { Order } from '../types'

const server = setupServer(http.get('*/categories/tree', () => HttpResponse.json({ roots: [] })))

const mounted: VueWrapper[] = []

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  mounted.splice(0).forEach((wrapper) => wrapper.unmount())
})
afterAll(() => server.close())
beforeEach(() => localStorage.clear())

const ID = '00000000-0000-4000-8000-0000000000a1'

const problem = (status: number, code: string) =>
  HttpResponse.json({ status, code, detail: code }, { status })

async function mountDetail(id = ID): Promise<VueWrapper> {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.accessToken = 'access-1'
  auth.user = me()
  const router = createRouter({ history: createMemoryHistory(), routes })
  installAuthGuard(router)
  await router.push(`/orders/${id}`)
  await router.isReady()
  const wrapper = mount({ template: '<RouterView />' }, { global: { plugins: [pinia, router] } })
  await flushPromises()
  mounted.push(wrapper)
  return wrapper
}

function serveOrder(get: () => Response, cancel?: () => Response | Promise<Response>) {
  const calls = { cancel: 0, get: 0 }
  server.use(
    http.get('*/orders/:id', () => {
      calls.get++
      return get()
    }),
    http.post('*/orders/:id/cancel', async () => {
      calls.cancel++
      return cancel ? cancel() : problem(500, 'UNEXPECTED')
    }),
  )
  return calls
}

describe('訂單詳情', () => {
  it('顯示商品快照（下單當下的名稱與單價）、小計、總計與各階段時間', async () => {
    serveOrder(() =>
      HttpResponse.json(
        order('SHIPPED', {
          paidAt: '2026-11-10T01:00:00Z',
          shippedAt: '2026-11-10T02:00:00Z',
        }),
      ),
    )

    const wrapper = await mountDetail()

    expect(wrapper.find('h1').text()).toBe('訂單 #000000A1')
    expect(wrapper.find('.status-badge').text()).toBe('已出貨')
    const items = wrapper.findAll('.od__item').map((i) => i.text())
    expect(items[0]).toContain('運動鞋')
    expect(items[0]).toContain('NT$ 600 × 2')
    expect(items[0]).toContain('NT$ 1,200')
    expect(items[1]).toContain('NT$ 34.5 × 1')
    expect(wrapper.find('.od__total').text()).toContain('NT$ 1,234.5')
    // 只列出已發生的階段
    expect(wrapper.findAll('.od__timeline li').map((li) => li.find('span').text())).toEqual([
      '建立訂單',
      '付款完成',
      '出貨',
    ])
  })

  it('別人的訂單、不存在、id 不合法：顯示找不到', async () => {
    serveOrder(() => problem(404, 'ORDER_NOT_FOUND'))
    expect((await mountDetail()).find('h1').text()).toBe('404')

    serveOrder(() => problem(400, 'VALIDATION_FAILED'))
    expect((await mountDetail('not-a-uuid')).find('h1').text()).toBe('404')
  })

  it('查詢失敗：顯示錯誤，重試後顯示', async () => {
    let fail = true
    serveOrder(() => (fail ? problem(503, 'ORDER_DOWN') : HttpResponse.json(order('PAID'))))
    const wrapper = await mountDetail()

    expect(wrapper.find('[role="alert"]').text()).toContain('ORDER_DOWN')

    fail = false
    await wrapper.find('[role="alert"] button').trigger('click')
    await flushPromises()

    expect(wrapper.find('h1').text()).toContain('訂單 #')
  })

  describe('按鈕依狀態顯示', () => {
    it('待付款：剩餘時間、繼續付款（連到付款頁）、取消訂單', async () => {
      serveOrder(() => HttpResponse.json(order('PENDING_PAYMENT')))

      const wrapper = await mountDetail()

      expect(wrapper.find('.od__countdown').text()).toMatch(/^(19:5\d|20:0[01])$/)
      expect(wrapper.find('a.btn').text()).toBe('繼續付款')
      expect(wrapper.find('a.btn').attributes('href')).toBe(`/orders/${ID}/pay`)
      expect(wrapper.text()).toContain('取消訂單')
    })

    it('待付款但期限已到：不能繼續付款，說明即將被取消，仍可取消', async () => {
      serveOrder(() => HttpResponse.json(order('PENDING_PAYMENT', { createdAt: minutesAgo(31) })))

      const wrapper = await mountDetail()

      expect(wrapper.find('.od__expired').text()).toContain('付款期限已到')
      expect(wrapper.find('a.btn').exists()).toBe(false)
      expect(wrapper.text()).toContain('取消訂單')
    })

    it.each(['PAID', 'SHIPPED', 'COMPLETED', 'CANCELLED'] as const)(
      '%s：沒有繼續付款、沒有取消',
      async (status) => {
        serveOrder(() => HttpResponse.json(order(status)))

        const wrapper = await mountDetail()

        expect(wrapper.find('.od__pending').exists()).toBe(false)
        expect(wrapper.text()).not.toContain('繼續付款')
        expect(wrapper.text()).not.toContain('取消訂單')
      },
    )
  })

  describe('取消訂單', () => {
    const open = async (wrapper: VueWrapper) => {
      await wrapper.find('.od__actions .od__link').trigger('click')
    }

    it('先要求確認；返回不送出請求', async () => {
      const calls = serveOrder(() => HttpResponse.json(order()))
      const wrapper = await mountDetail()

      await open(wrapper)
      expect(wrapper.find('[role="alertdialog"]').text()).toContain('確定要取消這張訂單嗎')

      await wrapper.find('[role="alertdialog"] .od__link').trigger('click')

      expect(wrapper.find('[role="alertdialog"]').exists()).toBe(false)
      expect(calls.cancel).toBe(0)
    })

    it('確定取消：更新為已取消，顯示成功訊息，付款相關按鈕消失', async () => {
      const calls = serveOrder(
        () => HttpResponse.json(order()),
        () => HttpResponse.json(order('CANCELLED', { cancelledAt: '2026-11-10T03:00:00Z' })),
      )
      const wrapper = await mountDetail()

      await open(wrapper)
      await wrapper.find('[role="alertdialog"] .btn').trigger('click')
      await flushPromises()

      expect(calls.cancel).toBe(1)
      expect(wrapper.find('.status-badge').text()).toBe('已取消')
      expect(wrapper.find('[role="status"]').text()).toContain('訂單已取消')
      expect(wrapper.find('.od__pending').exists()).toBe(false)
      expect(wrapper.text()).toContain('取消')
    })

    it('連點確定：只送出一次', async () => {
      const calls = serveOrder(
        () => HttpResponse.json(order()),
        async () => {
          await delay(30)
          return HttpResponse.json(order('CANCELLED'))
        },
      )
      const wrapper = await mountDetail()

      await open(wrapper)
      const confirm = wrapper.find('[role="alertdialog"] .btn')
      await confirm.trigger('click')
      await confirm.trigger('click')
      await confirm.trigger('click')

      expect(confirm.attributes('disabled')).toBeDefined()
      await flushPromises()
      expect(calls.cancel).toBe(1)
    })

    it('ORDER_NOT_CANCELLABLE（剛好付款了）：說明原因，並更新成已付款的最新狀態', async () => {
      let status: 'PENDING_PAYMENT' | 'PAID' = 'PENDING_PAYMENT'
      serveOrder(
        () => HttpResponse.json(order(status)),
        () => {
          status = 'PAID' // 在按下確定之前剛付完款
          return problem(409, 'ORDER_NOT_CANCELLABLE')
        },
      )
      const wrapper = await mountDetail()

      await open(wrapper)
      await wrapper.find('[role="alertdialog"] .btn').trigger('click')
      await flushPromises()

      expect(wrapper.find('[role="alert"]').text()).toContain('目前無法取消')
      expect(wrapper.find('.status-badge').text()).toBe('已付款')
      expect(wrapper.find('.od__pending').exists()).toBe(false)
    })

    it('庫存服務忙碌（訂單其實已取消、只是釋放庫存失敗）：重新查詢後顯示已取消', async () => {
      let status: 'PENDING_PAYMENT' | 'CANCELLED' = 'PENDING_PAYMENT'
      serveOrder(
        () => HttpResponse.json(order(status)),
        () => {
          status = 'CANCELLED'
          return problem(503, 'INVENTORY_SERVICE_UNAVAILABLE')
        },
      )
      const wrapper = await mountDetail()

      await open(wrapper)
      await wrapper.find('[role="alertdialog"] .btn').trigger('click')
      await flushPromises()

      expect(wrapper.find('[role="alert"]').text()).toContain('請確認訂單是否已取消')
      expect(wrapper.find('.status-badge').text()).toBe('已取消')
    })

    it('其他錯誤：顯示後端訊息，訂單維持待付款，可以再試', async () => {
      serveOrder(
        () => HttpResponse.json(order()),
        () => problem(500, 'BOOM'),
      )
      const wrapper = await mountDetail()

      await open(wrapper)
      await wrapper.find('[role="alertdialog"] .btn').trigger('click')
      await flushPromises()

      expect(wrapper.find('[role="alert"]').text()).toContain('BOOM')
      expect(wrapper.find('.status-badge').text()).toBe('待付款')
      expect(wrapper.find('.od__actions').exists()).toBe(true)
    })
  })

  it('取消時訂單已經不存在（404）：顯示找不到訊息', async () => {
    let gone = false
    serveOrder(
      () => (gone ? problem(404, 'ORDER_NOT_FOUND') : HttpResponse.json(order() as Order)),
      () => {
        gone = true
        return problem(404, 'ORDER_NOT_FOUND')
      },
    )
    const wrapper = await mountDetail()

    await wrapper.find('.od__actions .od__link').trigger('click')
    await wrapper.find('[role="alertdialog"] .btn').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toContain('找不到這張訂單')
  })
})
