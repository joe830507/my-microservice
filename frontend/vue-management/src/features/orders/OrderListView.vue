<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { toApiError } from '@/api/errors'
import { useRouteQuery } from '@/composables/useRouteQuery'
import { formatDateTime, formatPrice } from '@/utils/format'
import * as orderApi from './api'
import {
  DEFAULT_ORDER_QUERY,
  ORDER_PAGE_SIZES,
  parseOrderQuery,
  toOrderApiParams,
  toOrderRouteQuery,
  UUID_PATTERN,
  type OrderListQuery,
} from './query'
import { ORDER_STATUS_LABELS, ORDER_STATUS_TAG_TYPES } from './status'
import { ORDER_STATUSES, type OrderListItem, type OrderPage, type OrderStatus } from './types'

const router = useRouter()
const { state: query, update } = useRouteQuery(parseOrderQuery, toOrderRouteQuery)

const filters = reactive({
  status: '' as OrderListQuery['status'],
  userId: '',
})
const filterError = ref('')

const result = ref<OrderPage | null>(null)
const loading = ref(false)
const loadError = ref('')
let requestSeq = 0

async function load(current: OrderListQuery): Promise<void> {
  const seq = ++requestSeq
  loading.value = true
  loadError.value = ''
  try {
    const page = await orderApi.getOrders(toOrderApiParams(current))
    if (seq === requestSeq) {
      result.value = page
    }
  } catch (error) {
    if (seq === requestSeq) {
      loadError.value = toApiError(error).detail
    }
  } finally {
    if (seq === requestSeq) {
      loading.value = false
    }
  }
}

watch(
  query,
  (current) => {
    filters.status = current.status
    filters.userId = current.userId
    void load(current)
  },
  { immediate: true },
)

function onSearch(): void {
  const userId = filters.userId.trim()
  if (userId && !UUID_PATTERN.test(userId)) {
    filterError.value = '使用者 ID 格式不正確'
    return
  }
  filterError.value = ''
  void update({ ...query.value, status: filters.status || '', userId, page: 1 })
}

function onReset(): void {
  filterError.value = ''
  void update({ ...DEFAULT_ORDER_QUERY, size: query.value.size })
}

// 點使用者 ID：只看這個使用者的訂單
function filterByUser(userId: string): void {
  void update({ ...query.value, userId, page: 1 })
}

function onPageChange(page: number): void {
  void update({ ...query.value, page })
}

function onSizeChange(size: number): void {
  void update({ ...query.value, size, page: 1 })
}

function openDetail(order: OrderListItem): void {
  void router.push({ name: 'order-detail', params: { id: order.id } })
}

function statusOf(row: unknown): OrderStatus {
  return (row as OrderListItem).status
}
</script>

<template>
  <el-card>
    <el-form class="filters" inline @submit.prevent="onSearch">
      <el-form-item label="狀態">
        <el-select v-model="filters.status" placeholder="全部" clearable class="filter-select">
          <el-option
            v-for="status in ORDER_STATUSES"
            :key="status"
            :label="ORDER_STATUS_LABELS[status]"
            :value="status"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="使用者 ID">
        <el-input
          v-model="filters.userId"
          name="userId"
          placeholder="完整的使用者 ID"
          clearable
          class="user-input"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" native-type="submit" class="search-button">搜尋</el-button>
        <el-button class="reset-button" @click="onReset">清除條件</el-button>
      </el-form-item>
    </el-form>

    <el-alert
      v-if="filterError"
      class="filter-error"
      type="warning"
      :title="filterError"
      :closable="false"
      show-icon
    />
    <el-alert
      v-if="loadError"
      class="load-error"
      type="error"
      :title="`無法讀取訂單：${loadError}`"
      :closable="false"
      show-icon
    />

    <el-table
      v-loading="loading"
      :data="result?.items ?? []"
      row-key="id"
      empty-text="沒有符合條件的訂單"
    >
      <el-table-column label="訂單編號" min-width="290">
        <template #default="{ row }">
          <span class="order-id">{{ row.id }}</span>
        </template>
      </el-table-column>
      <el-table-column label="使用者 ID" min-width="290">
        <template #default="{ row }">
          <el-button link type="primary" class="user-filter" @click="filterByUser(row.userId)">
            {{ row.userId }}
          </el-button>
        </template>
      </el-table-column>
      <el-table-column label="狀態" width="100">
        <template #default="{ row }">
          <el-tag :type="ORDER_STATUS_TAG_TYPES[statusOf(row)]">
            {{ ORDER_STATUS_LABELS[statusOf(row)] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="金額" width="120" align="right">
        <template #default="{ row }">{{ formatPrice(row.totalAmount) }}</template>
      </el-table-column>
      <el-table-column label="建立時間" width="180">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            class="detail-button"
            @click="openDetail(row as OrderListItem)"
          >
            查看
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-if="result"
      class="pagination"
      background
      layout="total, sizes, prev, pager, next"
      :total="result.totalElements"
      :current-page="query.page"
      :page-size="query.size"
      :page-sizes="ORDER_PAGE_SIZES"
      @current-change="onPageChange"
      @size-change="onSizeChange"
    />
  </el-card>
</template>

<style scoped>
.filters {
  margin-bottom: 8px;
}

.filter-select {
  width: 160px;
}

.user-input {
  width: 320px;
  max-width: 100%;
}

.filter-error,
.load-error {
  margin-bottom: 16px;
}

.order-id {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
}

.user-filter {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
}

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
