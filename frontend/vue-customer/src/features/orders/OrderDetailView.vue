<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import { toApiError } from '@/api/errors'
import { PAYMENT_TIMEOUT_MS } from '@/features/payments/usePayFlow'
import NotFoundView from '@/views/NotFoundView.vue'
import { formatCountdown, formatDateTime, formatPrice } from '@/utils/format'
import { cancelOrder, getOrder } from './api'
import OrderStatusBadge from './OrderStatusBadge.vue'
import { cancelErrorMessage, orderNumber } from './status'
import type { Order } from './types'

const props = defineProps<{ id: string }>()

const order = ref<Order | null>(null)
const notFound = ref(false)
const errorMessage = ref('')
const loading = ref(false)

// 取消訂單：先要求確認，不用瀏覽器的 confirm 對話框
const confirmingCancel = ref(false)
const cancelling = ref(false)
const actionMessage = ref('')
const actionIsError = ref(false)

// 剩餘付款時間：用使用者電腦的時鐘推算，只用來提示與隱藏「繼續付款」，真正的期限以後端為準
const now = ref(Date.now())
const timer = setInterval(() => (now.value = Date.now()), 1000)
onScopeDispose(() => clearInterval(timer))

let latestRequest = 0

async function load(): Promise<void> {
  const requestId = ++latestRequest
  loading.value = true
  notFound.value = false
  errorMessage.value = ''
  order.value = null
  confirmingCancel.value = false
  actionMessage.value = ''
  try {
    const found = await getOrder(props.id)
    if (requestId === latestRequest) order.value = found
  } catch (error) {
    if (requestId !== latestRequest) return
    const apiError = toApiError(error)
    // 不存在、別人的訂單（後端不透露存在與否）、網址的 id 不是合法 UUID（400）都視為找不到
    if (apiError.code === 'ORDER_NOT_FOUND' || apiError.status === 400) {
      notFound.value = true
    } else {
      errorMessage.value = apiError.detail
    }
  } finally {
    if (requestId === latestRequest) loading.value = false
  }
}

watch(() => props.id, load, { immediate: true })

const remainingMs = computed(() => {
  if (!order.value) return 0
  return Math.max(0, new Date(order.value.createdAt).getTime() + PAYMENT_TIMEOUT_MS - now.value)
})
const isPending = computed(() => order.value?.status === 'PENDING_PAYMENT')

const timeline = computed(() => {
  const o = order.value
  if (!o) return []
  return [
    { label: '建立訂單', time: o.createdAt },
    { label: '付款完成', time: o.paidAt },
    { label: '出貨', time: o.shippedAt },
    { label: '完成', time: o.completedAt },
    { label: '取消', time: o.cancelledAt },
  ].filter((entry) => entry.time)
})

async function cancel(): Promise<void> {
  // 處理中不再送第二次
  if (cancelling.value || !order.value) return
  cancelling.value = true
  actionMessage.value = ''
  try {
    order.value = await cancelOrder(order.value.id)
    actionIsError.value = false
    actionMessage.value = '訂單已取消，保留的庫存已釋出'
  } catch (error) {
    actionIsError.value = true
    actionMessage.value = cancelErrorMessage(error)
    // 狀態可能剛變了（剛付款）、或其實已經取消只是釋放庫存失敗：重新查，以後端的狀態為準
    try {
      order.value = await getOrder(props.id)
    } catch {
      // 查不到就維持畫面上的資料
    }
  } finally {
    confirmingCancel.value = false
    cancelling.value = false
  }
}
</script>

<template>
  <NotFoundView v-if="notFound" />
  <section v-else-if="order" class="od">
    <RouterLink :to="{ name: 'orders' }" class="od__back">← 我的訂單</RouterLink>
    <header class="od__head">
      <h1>訂單 #{{ orderNumber(order.id) }}</h1>
      <OrderStatusBadge :status="order.status" />
    </header>

    <p
      v-if="actionMessage"
      :class="actionIsError ? 'form-error' : 'od__done'"
      :role="actionIsError ? 'alert' : 'status'"
    >
      {{ actionMessage }}
    </p>

    <!-- 待付款：剩餘時間與操作 -->
    <div v-if="isPending" class="od__pending">
      <p v-if="remainingMs > 0">
        請在 <strong class="od__countdown">{{ formatCountdown(remainingMs) }}</strong>
        內完成付款，逾時訂單會自動取消
      </p>
      <p v-else class="od__expired">付款期限已到，訂單即將被取消，無法再付款。</p>

      <div v-if="confirmingCancel" class="od__confirm" role="alertdialog" aria-label="確認取消訂單">
        <p>確定要取消這張訂單嗎？取消後會釋放保留的庫存，無法復原。</p>
        <button type="button" class="btn od__danger" :disabled="cancelling" @click="cancel">
          {{ cancelling ? '取消中…' : '確定取消' }}
        </button>
        <button
          type="button"
          class="od__link"
          :disabled="cancelling"
          @click="confirmingCancel = false"
        >
          返回
        </button>
      </div>
      <div v-else class="od__actions">
        <RouterLink
          v-if="remainingMs > 0"
          :to="{ name: 'order-pay', params: { id: order.id } }"
          class="btn"
        >
          繼續付款
        </RouterLink>
        <button type="button" class="od__link" @click="confirmingCancel = true">取消訂單</button>
      </div>
    </div>

    <h2>商品</h2>
    <ul class="od__items">
      <li v-for="item in order.items" :key="item.productId" class="od__item">
        <span class="od__item-name">{{ item.productName }}</span>
        <span class="od__item-qty">NT$ {{ formatPrice(item.unitPrice) }} × {{ item.qty }}</span>
        <span class="od__item-subtotal">NT$ {{ formatPrice(item.subtotal) }}</span>
      </li>
    </ul>
    <p class="od__total">
      總計 <strong>NT$ {{ formatPrice(order.totalAmount) }}</strong>
    </p>

    <h2>訂單進度</h2>
    <ul class="od__timeline">
      <li v-for="entry in timeline" :key="entry.label">
        <span>{{ entry.label }}</span>
        <time>{{ formatDateTime(entry.time) }}</time>
      </li>
    </ul>
  </section>
  <p v-else-if="errorMessage" class="form-error" role="alert">
    {{ errorMessage }}
    <button type="button" class="od__link" @click="load">重試</button>
  </p>
  <p v-else-if="loading" class="od__hint">載入中…</p>
</template>

<style scoped>
.od {
  max-width: 720px;
  margin: 0 auto;
}

.od__back {
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
}

.od__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3);
}

.od__pending {
  margin: var(--space-4) 0;
  padding: var(--space-4);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
}

.od__countdown {
  color: var(--color-danger);
  font-variant-numeric: tabular-nums;
}

.od__expired {
  color: var(--color-danger);
}

.od__actions,
.od__confirm {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3);
}

.od__confirm p {
  flex: 1 1 100%;
  margin: 0;
}

.od__danger {
  background: var(--color-danger);
}

.od__link {
  border: 0;
  background: none;
  color: var(--color-primary);
  font: inherit;
  text-decoration: underline;
  cursor: pointer;
}

.od__done {
  color: var(--color-success);
}

.od__items,
.od__timeline {
  margin: 0;
  padding: 0;
  list-style: none;
}

.od__item {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: var(--space-1) var(--space-4);
  padding: var(--space-3) 0;
  border-bottom: 1px solid var(--color-border);
}

.od__item-name {
  grid-column: 1 / -1;
  font-weight: 600;
}

.od__item-qty {
  color: var(--color-text-muted);
}

.od__item-subtotal {
  font-weight: 700;
  text-align: right;
}

.od__total {
  margin: var(--space-3) 0;
  font-size: var(--font-size-lg);
  text-align: right;
}

.od__total strong {
  color: var(--color-primary);
}

.od__timeline li {
  display: flex;
  justify-content: space-between;
  gap: var(--space-3);
  padding: var(--space-2) 0;
  border-bottom: 1px solid var(--color-border);
  color: var(--color-text-muted);
}

.od__hint {
  padding: var(--space-8) 0;
  text-align: center;
  color: var(--color-text-muted);
}

@media (min-width: 768px) {
  .od__item {
    grid-template-columns: 1fr auto 140px;
  }

  .od__item-name {
    grid-column: auto;
  }
}
</style>
