<script setup lang="ts">
import { ref, watch } from 'vue'
import { toApiError } from '@/api/errors'
import PaginationBar from '@/components/PaginationBar.vue'
import { useRouteQuery } from '@/composables/useRouteQuery'
import { formatDateTime, formatPrice } from '@/utils/format'
import { getOrders } from './api'
import OrderStatusBadge from './OrderStatusBadge.vue'
import { parseOrderQuery, toOrderApiParams, toOrderRouteQuery, type OrderListQuery } from './query'
import { ORDER_STATUS_LABELS, orderNumber } from './status'
import { ORDER_STATUSES, type OrderPage } from './types'

// 我的訂單：只會拿到自己的；狀態與頁碼放在網址上
const { state, update } = useRouteQuery(parseOrderQuery, toOrderRouteQuery)

const result = ref<OrderPage | null>(null)
const loading = ref(false)
const errorMessage = ref('')

// 快速切換條件時，舊請求可能比新的晚回來：只接受最後一次發出的結果
let latestRequest = 0

async function load(): Promise<void> {
  const requestId = ++latestRequest
  loading.value = true
  errorMessage.value = ''
  try {
    const page = await getOrders(toOrderApiParams(state.value))
    if (requestId === latestRequest) result.value = page
  } catch (error) {
    if (requestId === latestRequest) {
      result.value = null
      errorMessage.value = toApiError(error).detail
    }
  } finally {
    if (requestId === latestRequest) loading.value = false
  }
}

watch(state, load, { immediate: true })

function change(patch: Partial<OrderListQuery>): void {
  // 換狀態回到第一頁；只換頁碼時保留狀態
  void update({ ...state.value, page: 1, ...patch })
}
</script>

<template>
  <section>
    <h1>我的訂單</h1>

    <div class="orders-filter" role="group" aria-label="依狀態篩選">
      <button
        type="button"
        :class="{ 'is-active': state.status === '' }"
        :aria-pressed="state.status === ''"
        @click="change({ status: '' })"
      >
        全部
      </button>
      <button
        v-for="status in ORDER_STATUSES"
        :key="status"
        type="button"
        :class="{ 'is-active': state.status === status }"
        :aria-pressed="state.status === status"
        @click="change({ status })"
      >
        {{ ORDER_STATUS_LABELS[status] }}
      </button>
    </div>

    <p v-if="errorMessage" class="form-error" role="alert">
      {{ errorMessage }}
      <button type="button" class="orders-link" @click="load">重試</button>
    </p>
    <template v-else-if="result">
      <ul v-if="result.items.length > 0" class="orders-list" :class="{ 'is-loading': loading }">
        <li v-for="order in result.items" :key="order.id" class="orders-item">
          <RouterLink :to="{ name: 'order-detail', params: { id: order.id } }" class="orders-card">
            <div class="orders-card__head">
              <span class="orders-card__number">訂單 #{{ orderNumber(order.id) }}</span>
              <OrderStatusBadge :status="order.status" />
            </div>
            <div class="orders-card__meta">
              <span>{{ formatDateTime(order.createdAt) }}</span>
              <strong>NT$ {{ formatPrice(order.totalAmount) }}</strong>
            </div>
          </RouterLink>
          <RouterLink
            v-if="order.status === 'PENDING_PAYMENT'"
            :to="{ name: 'order-pay', params: { id: order.id } }"
            class="orders-pay"
          >
            去付款
          </RouterLink>
        </li>
      </ul>
      <p v-else class="orders-empty">
        {{ state.status ? `沒有「${ORDER_STATUS_LABELS[state.status]}」的訂單` : '還沒有訂單' }}
        <RouterLink v-if="!state.status" :to="{ name: 'search' }" class="orders-link">
          去逛逛
        </RouterLink>
      </p>
      <PaginationBar
        :page="state.page"
        :total-pages="result.totalPages"
        @change="(page) => change({ page })"
      />
    </template>
    <p v-else-if="loading" class="orders-empty">載入中…</p>
  </section>
</template>

<style scoped>
.orders-filter {
  display: flex;
  gap: var(--space-2);
  margin-bottom: var(--space-4);
  overflow-x: auto;
}

.orders-filter button {
  min-height: 36px;
  padding: 0 var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: 999px;
  background: var(--color-surface);
  font: inherit;
  white-space: nowrap;
  cursor: pointer;
}

.orders-filter button.is-active {
  border-color: var(--color-primary);
  background: var(--color-primary);
  color: var(--color-on-primary);
}

.orders-list {
  display: grid;
  gap: var(--space-3);
  margin: 0;
  padding: 0;
  list-style: none;
}

.orders-list.is-loading {
  opacity: 0.5;
}

.orders-item {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2) var(--space-3);
  padding: var(--space-3) var(--space-4);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
}

.orders-card {
  flex: 1 1 240px;
}

.orders-card__head,
.orders-card__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
}

.orders-card__number {
  font-weight: 600;
}

.orders-card__meta {
  margin-top: var(--space-1);
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
}

.orders-card__meta strong {
  color: var(--color-text);
}

.orders-pay {
  padding: var(--space-1) var(--space-3);
  border-radius: var(--radius-sm);
  background: var(--color-primary);
  color: var(--color-on-primary);
}

.orders-empty {
  padding: var(--space-8) 0;
  text-align: center;
  color: var(--color-text-muted);
}

.orders-link {
  border: 0;
  background: none;
  color: var(--color-primary);
  font: inherit;
  text-decoration: underline;
  cursor: pointer;
}
</style>
