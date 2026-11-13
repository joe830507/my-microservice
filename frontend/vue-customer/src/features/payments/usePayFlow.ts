import { computed, onScopeDispose, ref } from 'vue'
import { toApiError } from '@/api/errors'
import { getOrder } from '@/features/orders/api'
import type { Order } from '@/features/orders/types'
import { createPayment, getPayment, mockCallback } from './api'
import type { MockPaymentResult, Payment } from './types'

// 建單後的付款期限（order-service 的 payment-timeout），超過未付款訂單會被自動取消
// 這裡用訂單的 createdAt 推算剩餘時間；用的是使用者電腦的時鐘，有誤差，只用來提示與停用按鈕，
// 真正的期限以後端為準
export const PAYMENT_TIMEOUT_MS = 30 * 60 * 1000

// 付款成功後輪詢訂單到 PAID：付款通知 order-service 是非同步重試，可能晚幾秒
// 做成可修改的物件，測試才不用真的等 10 秒
export const pollConfig = { intervalMs: 1000, maxAttempts: 10 }

export type PayPhase =
  // 載入訂單、建立付款
  | 'loading'
  // 等待顧客選擇付款成功或失敗
  | 'ready'
  // 回呼已送出，等回應
  | 'processing'
  // 付款已成功，輪詢訂單直到 PAID
  | 'confirming'
  | 'paid'
  | 'failed'
  | 'cancelled'
  // 輪詢到上限仍不是 PAID：付款已成功，只是訂單還沒更新
  | 'delayed'
  | 'not-found'
  | 'error'

const PAID_STATUSES = ['PAID', 'SHIPPED', 'COMPLETED']

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

export function usePayFlow(orderId: () => string) {
  const phase = ref<PayPhase>('loading')
  const order = ref<Order | null>(null)
  const payment = ref<Payment | null>(null)
  // 載入失敗（phase = error）的原因，或付款操作失敗時留在 ready 的提示
  const message = ref('')
  // 訂單在付款成功後才被取消（例如剛好付款逾時）：款項需要退回
  const cancelledAfterPaid = ref(false)

  const now = ref(Date.now())
  const timer = setInterval(() => (now.value = Date.now()), 1000)
  let disposed = false
  onScopeDispose(() => {
    disposed = true
    clearInterval(timer)
  })

  const remainingMs = computed(() => {
    if (!order.value) return 0
    return Math.max(0, new Date(order.value.createdAt).getTime() + PAYMENT_TIMEOUT_MS - now.value)
  })
  // 付款期限已到，但後端還沒把訂單取消：停用付款按鈕
  const expired = computed(() => phase.value === 'ready' && remainingMs.value <= 0)

  // 期限到了就重新查一次訂單，看是不是已經被取消（只查一次，之後的狀態由使用者重新整理）
  let checkedAtExpiry = false
  const expiryTimer = setInterval(() => {
    if (phase.value === 'ready' && remainingMs.value <= 0 && !checkedAtExpiry) {
      checkedAtExpiry = true
      void refreshOrder()
    }
  }, 1000)
  onScopeDispose(() => clearInterval(expiryTimer))

  async function refreshOrder(): Promise<void> {
    try {
      const latest = await getOrder(orderId())
      if (disposed) return
      order.value = latest
      if (phase.value === 'ready') routeByStatus(latest)
    } catch {
      // 查不到不影響畫面，按鈕本來就已經停用
    }
  }

  // 依訂單狀態決定要顯示什麼；回傳 true 代表還在待付款，要繼續建立付款
  function routeByStatus(current: Order): boolean {
    if (PAID_STATUSES.includes(current.status)) {
      phase.value = 'paid'
    } else if (current.status === 'CANCELLED') {
      phase.value = 'cancelled'
    } else {
      return true
    }
    return false
  }

  async function load(): Promise<void> {
    phase.value = 'loading'
    message.value = ''
    cancelledAfterPaid.value = false
    checkedAtExpiry = false
    try {
      order.value = await getOrder(orderId())
    } catch (error) {
      if (disposed) return
      const apiError = toApiError(error)
      if (apiError.code === 'ORDER_NOT_FOUND' || apiError.status === 400) {
        phase.value = 'not-found'
      } else {
        phase.value = 'error'
        message.value = apiError.detail
      }
      return
    }
    if (disposed) return
    if (routeByStatus(order.value)) {
      await startPayment()
    }
  }

  // 建立付款：重新整理頁面會拿到同一筆進行中的付款；付款失敗後重新付款會建立新的
  async function startPayment(): Promise<void> {
    phase.value = 'loading'
    message.value = ''
    try {
      payment.value = await createPayment(orderId())
      if (!disposed) phase.value = payment.value.status === 'CANCELLED' ? 'cancelled' : 'ready'
    } catch (error) {
      if (disposed) return
      const apiError = toApiError(error)
      switch (apiError.code) {
        // 已經有成功的付款（上次付完重新整理）：等訂單更新成 PAID，不能再付
        case 'ORDER_ALREADY_PAID':
          await confirm()
          return
        // 訂單狀態已經變了（已取消等）：重新查訂單決定顯示什麼
        case 'ORDER_NOT_PAYABLE':
          await refreshAndRoute()
          return
        case 'ORDER_NOT_FOUND':
          phase.value = 'not-found'
          return
        default:
          phase.value = 'error'
          message.value = apiError.detail
      }
    }
  }

  async function refreshAndRoute(): Promise<void> {
    try {
      order.value = await getOrder(orderId())
      if (!disposed && routeByStatus(order.value)) {
        phase.value = 'error'
        message.value = '目前無法付款，請稍後再試'
      }
    } catch (error) {
      if (!disposed) {
        phase.value = 'error'
        message.value = toApiError(error).detail
      }
    }
  }

  // 顧客按下「付款成功」或「付款失敗」（模擬金流的回呼）
  async function pay(result: MockPaymentResult): Promise<void> {
    // 處理中不再送第二次
    if (phase.value !== 'ready' || expired.value || !payment.value) return
    const current = payment.value
    phase.value = 'processing'
    message.value = ''

    let settled: Payment | null = null
    try {
      settled = await mockCallback(current.id, result)
    } catch (error) {
      // 回呼失敗不代表付款沒有結果：沒收到回應、或回 409（已經是另一個結果）時，
      // 以後端記錄的付款狀態為準，不要自己猜
      try {
        settled = await getPayment(current.id)
      } catch {
        // 連付款狀態都查不到
      }
      if (!settled || settled.status === 'PENDING') {
        if (!disposed) {
          phase.value = 'ready'
          message.value = toApiError(error).detail
        }
        return
      }
    }
    if (disposed) return
    payment.value = settled
    if (settled.status === 'SUCCEEDED') {
      await confirm()
    } else if (settled.status === 'CANCELLED') {
      phase.value = 'cancelled'
    } else if (settled.status === 'FAILED') {
      phase.value = 'failed'
    } else {
      phase.value = 'ready'
    }
  }

  // 付款成功後等訂單變成 PAID；到上限還沒有就提示稍後到我的訂單查看
  async function confirm(): Promise<void> {
    phase.value = 'confirming'
    for (let attempt = 0; attempt < pollConfig.maxAttempts; attempt++) {
      try {
        const latest = await getOrder(orderId())
        if (disposed) return
        order.value = latest
        if (PAID_STATUSES.includes(latest.status)) {
          phase.value = 'paid'
          return
        }
        if (latest.status === 'CANCELLED') {
          // 付款成功時訂單已經被取消（付款逾時）：錢收了但訂單不成立
          cancelledAfterPaid.value = true
          phase.value = 'cancelled'
          return
        }
      } catch {
        // 查詢失敗算這一次沒結果，繼續等
      }
      if (attempt < pollConfig.maxAttempts - 1) {
        await sleep(pollConfig.intervalMs)
        if (disposed) return
      }
    }
    phase.value = 'delayed'
  }

  return {
    phase,
    order,
    payment,
    message,
    cancelledAfterPaid,
    remainingMs,
    expired,
    load,
    startPayment,
    pay,
    confirm,
  }
}
