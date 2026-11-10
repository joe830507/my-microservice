<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { toApiError } from '@/api/errors'
import { useRouteQuery } from '@/composables/useRouteQuery'
import { formatDateTime } from '@/utils/format'
import * as userApi from './api'
import {
  DEFAULT_USER_QUERY,
  parseUserQuery,
  toUserApiParams,
  toUserRouteQuery,
  USER_PAGE_SIZES,
  type UserListQuery,
} from './query'
import {
  ROLES,
  USER_STATUSES,
  type Role,
  type UserListItem,
  type UserPage,
  type UserSortBy,
  type UserStatus,
} from './types'

const STATUS_LABELS: Record<UserStatus, string> = { ACTIVE: '啟用', DISABLED: '停用' }
const ROLE_LABELS: Record<Role, string> = { ADMIN: '管理員', CUSTOMER: '顧客' }

const router = useRouter()
const { state: query, update } = useRouteQuery(parseUserQuery, toUserRouteQuery)

const filters = reactive({
  keyword: '',
  status: '' as UserListQuery['status'],
  role: '' as UserListQuery['role'],
})

const result = ref<UserPage | null>(null)
const loading = ref(false)
const loadError = ref('')
let requestSeq = 0

async function load(current: UserListQuery): Promise<void> {
  const seq = ++requestSeq
  loading.value = true
  loadError.value = ''
  try {
    const page = await userApi.getUsers(toUserApiParams(current))
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
    filters.keyword = current.keyword
    filters.status = current.status
    filters.role = current.role
    void load(current)
  },
  { immediate: true },
)

function onSearch(): void {
  void update({
    ...query.value,
    keyword: filters.keyword.trim(),
    status: filters.status || '',
    role: filters.role || '',
    page: 1,
  })
}

function onReset(): void {
  void update({ ...DEFAULT_USER_QUERY, size: query.value.size })
}

function onPageChange(page: number): void {
  void update({ ...query.value, page })
}

function onSizeChange(size: number): void {
  void update({ ...query.value, size, page: 1 })
}

function isSortField(prop: string | null): prop is UserSortBy {
  return prop === 'createdAt' || prop === 'email' || prop === 'displayName'
}

// 取消排序時回到預設（最新註冊的排前面）
function onSortChange({ prop, order }: { prop: string | null; order: string | null }): void {
  const sorted = order !== null && isSortField(prop)
  void update({
    ...query.value,
    sortBy: sorted ? prop : DEFAULT_USER_QUERY.sortBy,
    sortDirection: sorted
      ? order === 'ascending'
        ? 'asc'
        : 'desc'
      : DEFAULT_USER_QUERY.sortDirection,
    page: 1,
  })
}

const defaultSort = computed(() => ({
  prop: query.value.sortBy,
  order: query.value.sortDirection === 'asc' ? ('ascending' as const) : ('descending' as const),
}))

// 註冊的帳號有 CUSTOMER；純 ADMIN 的帳號不是用來購物的
function canHaveOrders(user: UserListItem): boolean {
  return user.roles.includes('CUSTOMER')
}

// 這個使用者的訂單
function openOrders(user: UserListItem): void {
  void router.push({ name: 'orders', query: { userId: user.id } })
}

function rowOf(row: unknown): UserListItem {
  return row as UserListItem
}
</script>

<template>
  <el-card>
    <el-form class="filters" inline @submit.prevent="onSearch">
      <el-form-item label="關鍵字">
        <el-input
          v-model="filters.keyword"
          name="keyword"
          placeholder="email 或顯示名稱"
          maxlength="254"
          clearable
        />
      </el-form-item>
      <el-form-item label="狀態">
        <el-select v-model="filters.status" placeholder="全部" clearable class="filter-select">
          <el-option
            v-for="status in USER_STATUSES"
            :key="status"
            :label="STATUS_LABELS[status]"
            :value="status"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="角色">
        <el-select v-model="filters.role" placeholder="全部" clearable class="filter-select">
          <el-option v-for="role in ROLES" :key="role" :label="ROLE_LABELS[role]" :value="role" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" native-type="submit" class="search-button">搜尋</el-button>
        <el-button class="reset-button" @click="onReset">清除條件</el-button>
      </el-form-item>
    </el-form>

    <el-alert
      v-if="loadError"
      class="load-error"
      type="error"
      :title="`無法讀取使用者：${loadError}`"
      :closable="false"
      show-icon
    />

    <el-table
      :key="`${query.sortBy}-${query.sortDirection}`"
      v-loading="loading"
      :data="result?.items ?? []"
      :default-sort="defaultSort"
      row-key="id"
      empty-text="沒有符合條件的使用者"
      @sort-change="onSortChange"
    >
      <el-table-column prop="email" label="Email" min-width="200" sortable="custom" />
      <el-table-column prop="displayName" label="顯示名稱" min-width="140" sortable="custom" />
      <el-table-column label="角色" width="160">
        <template #default="{ row }">
          <el-tag
            v-for="role in rowOf(row).roles"
            :key="role"
            :type="role === 'ADMIN' ? 'danger' : 'info'"
            class="role-tag"
          >
            {{ ROLE_LABELS[role] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="狀態" width="90">
        <template #default="{ row }">
          <el-tag :type="rowOf(row).status === 'ACTIVE' ? 'success' : 'info'">
            {{ STATUS_LABELS[rowOf(row).status] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="註冊時間" width="180" sortable="custom">
        <template #default="{ row }">{{ formatDateTime(rowOf(row).createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <!-- 只有顧客會下單；純管理員帳號（例如 dev 建立的）不會有訂單 -->
          <el-button
            v-if="canHaveOrders(rowOf(row))"
            link
            type="primary"
            class="orders-button"
            @click="openOrders(rowOf(row))"
          >
            查看訂單
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
      :page-sizes="USER_PAGE_SIZES"
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
  width: 140px;
}

.load-error {
  margin-bottom: 16px;
}

.role-tag + .role-tag {
  margin-left: 4px;
}

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
