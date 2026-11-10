<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { confirmDanger } from '@/composables/useConfirm'
import { formatDateTime, formatPrice } from '@/utils/format'
import * as orderApi from './api'
import type { OrderAction } from './api'
import {
  availableAction,
  ORDER_ACTION_CONFIRMS,
  ORDER_ACTION_DONE,
  ORDER_ACTION_LABELS,
  ORDER_STATUS_LABELS,
  ORDER_STATUS_TAG_TYPES,
  orderErrorMessage,
} from './status'
import type { Order } from './types'

const route = useRoute()
const router = useRouter()

const order = ref<Order | null>(null)
const loading = ref(false)
const loadError = ref('')
const acting = ref(false)
const actionError = ref('')

const orderId = computed(() => String(route.params.id ?? ''))
const action = computed(() => (order.value ? availableAction(order.value.status) : null))

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  actionError.value = ''
  try {
    order.value = await orderApi.getOrder(orderId.value)
  } catch (error) {
    order.value = null
    loadError.value = orderErrorMessage(error)
  } finally {
    loading.value = false
  }
}

watch(orderId, load, { immediate: true })

async function onAction(current: OrderAction): Promise<void> {
  if (!order.value || !(await confirmDanger(ORDER_ACTION_CONFIRMS[current]))) {
    return
  }
  acting.value = true
  actionError.value = ''
  try {
    order.value = await orderApi.changeOrderStatus(order.value.id, current)
    ElMessage.success(ORDER_ACTION_DONE[current])
  } catch (error) {
    actionError.value = orderErrorMessage(error)
  } finally {
    acting.value = false
  }
}

// 各階段的時間：沒有發生過的不顯示
const timeline = computed(() => {
  const current = order.value
  if (!current) {
    return []
  }
  return [
    { label: '建立', time: current.createdAt },
    { label: '付款', time: current.paidAt },
    { label: '出貨', time: current.shippedAt },
    { label: '完成', time: current.completedAt },
    { label: '取消', time: current.cancelledAt },
  ].filter((entry): entry is { label: string; time: string } => entry.time !== null)
})
</script>

<template>
  <el-card v-loading="loading">
    <template #header>
      <div class="header">
        <h2 class="title">訂單詳情</h2>
        <div class="header-actions">
          <el-button
            v-if="action"
            :type="action === 'cancel' ? 'danger' : 'primary'"
            :loading="acting"
            class="order-action"
            :class="`${action}-button`"
            @click="onAction(action)"
          >
            {{ ORDER_ACTION_LABELS[action] }}
          </el-button>
          <el-button @click="load">重新整理</el-button>
          <el-button @click="router.push({ name: 'orders' })">回列表</el-button>
        </div>
      </div>
    </template>

    <el-alert
      v-if="loadError"
      class="load-error"
      type="error"
      :title="`無法讀取訂單：${loadError}`"
      :closable="false"
      show-icon
    />
    <el-alert
      v-if="actionError"
      class="action-error"
      type="error"
      :title="actionError"
      :closable="false"
      show-icon
    />

    <template v-if="order">
      <el-descriptions :column="2" border class="order-summary">
        <el-descriptions-item label="訂單編號">
          <span class="mono">{{ order.id }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="狀態">
          <el-tag class="status-tag" :type="ORDER_STATUS_TAG_TYPES[order.status]">
            {{ ORDER_STATUS_LABELS[order.status] }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="使用者 ID">
          <span class="mono">{{ order.userId }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="金額">
          <span class="total-amount">{{ formatPrice(order.totalAmount) }}</span>
        </el-descriptions-item>
      </el-descriptions>

      <h3 class="section-title">各階段時間</h3>
      <el-timeline class="order-timeline">
        <el-timeline-item
          v-for="entry in timeline"
          :key="entry.label"
          :timestamp="formatDateTime(entry.time)"
          placement="top"
        >
          {{ entry.label }}
        </el-timeline-item>
      </el-timeline>

      <h3 class="section-title">商品（下單當下的名稱與單價）</h3>
      <el-table :data="order.items" row-key="sku" class="order-items">
        <el-table-column prop="sku" label="SKU" min-width="120" />
        <el-table-column prop="productName" label="商品名稱" min-width="180" />
        <el-table-column label="單價" width="120" align="right">
          <template #default="{ row }">{{ formatPrice(row.unitPrice) }}</template>
        </el-table-column>
        <el-table-column prop="qty" label="數量" width="80" align="right" />
        <el-table-column label="小計" width="120" align="right">
          <template #default="{ row }">{{ formatPrice(row.subtotal) }}</template>
        </el-table-column>
      </el-table>
    </template>
  </el-card>
</template>

<style scoped>
.header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.header-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.header-actions .el-button + .el-button {
  margin-left: 0;
}

.title {
  margin: 0;
  font-size: 18px;
}

.load-error,
.action-error,
.order-summary {
  margin-bottom: 16px;
}

.section-title {
  margin: 24px 0 12px;
  font-size: 16px;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
}
</style>
