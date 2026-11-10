<!--
  後台首頁（參考 docs/pictures/dashboard_01.png 的版面）
  後端沒有統計 API，數字都來自各列表 API 的 totalElements（只查 1 筆），待辦清單取最新的 5 筆
  每一塊各自讀取，一塊失敗不影響其他塊
-->
<script setup lang="ts">
import { onMounted, reactive } from 'vue'
import { useRouter, type RouteLocationRaw } from 'vue-router'
import { useAuthStore } from '@/features/auth/store'
import { getOrders } from '@/features/orders/api'
import type { OrderListItem, OrderStatus } from '@/features/orders/types'
import { getProducts } from '@/features/products/api'
import type { ProductListItem, ProductStatus } from '@/features/products/types'
import { getUsers } from '@/features/users/api'
import { formatDateTime, formatPrice } from '@/utils/format'

const auth = useAuthStore()
const router = useRouter()

// null：讀取中；undefined：讀取失敗
type Count = number | null | undefined

interface Kpi {
  key: string
  label: string
  hint: string
  gradient: string
  to: RouteLocationRaw
  count: Count
}

const kpis = reactive<Kpi[]>([
  {
    key: 'orders-paid',
    label: '待出貨訂單',
    hint: '已付款，等待出貨',
    gradient: 'var(--app-gradient-violet)',
    to: { name: 'orders', query: { status: 'PAID' } },
    count: null,
  },
  {
    key: 'orders-pending',
    label: '待付款訂單',
    hint: '逾時未付款會自動取消',
    gradient: 'var(--app-gradient-cyan)',
    to: { name: 'orders', query: { status: 'PENDING_PAYMENT' } },
    count: null,
  },
  {
    key: 'products-active',
    label: '上架中商品',
    hint: '前台看得到的商品',
    gradient: 'var(--app-gradient-pink)',
    to: { name: 'products', query: { status: 'ACTIVE' } },
    count: null,
  },
  {
    key: 'users',
    label: '使用者',
    hint: '所有註冊的帳號',
    gradient: 'var(--app-gradient-teal)',
    to: { name: 'users' },
    count: null,
  },
])

interface Stat {
  key: string
  label: string
  to: RouteLocationRaw
  count: Count
}

const stats = reactive<Stat[]>([
  {
    key: 'products-draft',
    label: '草稿商品',
    to: { name: 'products', query: { status: 'DRAFT' } },
    count: null,
  },
  {
    key: 'products-inactive',
    label: '已下架商品',
    to: { name: 'products', query: { status: 'INACTIVE' } },
    count: null,
  },
  {
    key: 'orders-shipped',
    label: '已出貨、待完成',
    to: { name: 'orders', query: { status: 'SHIPPED' } },
    count: null,
  },
])

// 待辦清單
const toShip = reactive({ items: [] as OrderListItem[], loading: true, error: false })
const drafts = reactive({ items: [] as ProductListItem[], loading: true, error: false })

const TODO_SIZE = 5

function orderCount(status: OrderStatus): Promise<number> {
  return getOrders({ status, page: 0, size: 1 }).then((page) => page.totalElements)
}

function productCount(status: ProductStatus): Promise<number> {
  return getProducts({
    status,
    page: 0,
    size: 1,
    sortBy: 'createdAt',
    sortDirection: 'desc',
  }).then((page) => page.totalElements)
}

const loaders: Record<string, () => Promise<number>> = {
  'orders-paid': () => orderCount('PAID'),
  'orders-pending': () => orderCount('PENDING_PAYMENT'),
  'orders-shipped': () => orderCount('SHIPPED'),
  'products-active': () => productCount('ACTIVE'),
  'products-draft': () => productCount('DRAFT'),
  'products-inactive': () => productCount('INACTIVE'),
  users: () =>
    getUsers({ page: 0, size: 1, sortBy: 'createdAt', sortDirection: 'desc' }).then(
      (page) => page.totalElements,
    ),
}

async function loadCount(target: { key: string; count: Count }): Promise<void> {
  const loader = loaders[target.key]
  if (!loader) {
    return
  }
  try {
    target.count = await loader()
  } catch {
    target.count = undefined
  }
}

async function loadToShip(): Promise<void> {
  try {
    toShip.items = (await getOrders({ status: 'PAID', page: 0, size: TODO_SIZE })).items
  } catch {
    toShip.error = true
  } finally {
    toShip.loading = false
  }
}

async function loadDrafts(): Promise<void> {
  try {
    drafts.items = (
      await getProducts({
        status: 'DRAFT',
        page: 0,
        size: TODO_SIZE,
        sortBy: 'createdAt',
        sortDirection: 'desc',
      })
    ).items
  } catch {
    drafts.error = true
  } finally {
    drafts.loading = false
  }
}

onMounted(() => {
  void Promise.all([...kpis.map(loadCount), ...stats.map(loadCount), loadToShip(), loadDrafts()])
})

function display(count: Count): string {
  if (count === null) {
    return '…'
  }
  return count === undefined ? '—' : count.toLocaleString('zh-TW')
}

function shortId(id: string): string {
  return id.slice(-8)
}
</script>

<template>
  <div class="dashboard">
    <header class="dashboard-header">
      <h2 class="dashboard-title">歡迎回來{{ auth.user ? `，${auth.user.displayName}` : '' }}</h2>
      <p class="dashboard-subtitle">今天需要處理的事項</p>
    </header>

    <section class="kpi-grid" aria-label="重點數字">
      <button
        v-for="kpi in kpis"
        :key="kpi.key"
        type="button"
        class="kpi-card"
        :class="`kpi-${kpi.key}`"
        :style="{ background: kpi.gradient }"
        @click="router.push(kpi.to)"
      >
        <span class="kpi-label">{{ kpi.label }}</span>
        <span class="kpi-value">{{ display(kpi.count) }}</span>
        <span class="kpi-hint">{{ kpi.hint }}</span>
      </button>
    </section>

    <section class="stat-row" aria-label="其他狀態">
      <button
        v-for="stat in stats"
        :key="stat.key"
        type="button"
        class="stat-chip"
        :class="`stat-${stat.key}`"
        @click="router.push(stat.to)"
      >
        <span class="stat-label">{{ stat.label }}</span>
        <span class="stat-value">{{ display(stat.count) }}</span>
      </button>
    </section>

    <section class="todo-grid">
      <el-card class="todo-card todo-to-ship">
        <template #header>
          <div class="todo-header">
            <span>待出貨訂單</span>
            <el-button
              link
              type="primary"
              @click="router.push({ name: 'orders', query: { status: 'PAID' } })"
            >
              全部
            </el-button>
          </div>
        </template>
        <p v-if="toShip.loading" class="todo-empty">讀取中…</p>
        <p v-else-if="toShip.error" class="todo-empty todo-error">無法讀取訂單</p>
        <p v-else-if="toShip.items.length === 0" class="todo-empty">沒有待出貨的訂單</p>
        <ul v-else class="todo-list">
          <li v-for="order in toShip.items" :key="order.id">
            <button
              type="button"
              class="todo-item"
              @click="router.push({ name: 'order-detail', params: { id: order.id } })"
            >
              <span class="todo-main mono">#{{ shortId(order.id) }}</span>
              <span class="todo-meta">{{ formatDateTime(order.createdAt) }}</span>
              <span class="todo-amount">{{ formatPrice(order.totalAmount) }}</span>
            </button>
          </li>
        </ul>
      </el-card>

      <el-card class="todo-card todo-drafts">
        <template #header>
          <div class="todo-header">
            <span>草稿商品（還沒上架）</span>
            <el-button
              link
              type="primary"
              @click="router.push({ name: 'products', query: { status: 'DRAFT' } })"
            >
              全部
            </el-button>
          </div>
        </template>
        <p v-if="drafts.loading" class="todo-empty">讀取中…</p>
        <p v-else-if="drafts.error" class="todo-empty todo-error">無法讀取商品</p>
        <p v-else-if="drafts.items.length === 0" class="todo-empty">沒有草稿商品</p>
        <ul v-else class="todo-list">
          <li v-for="product in drafts.items" :key="product.id">
            <button
              type="button"
              class="todo-item"
              @click="router.push({ name: 'product-edit', params: { id: product.id } })"
            >
              <span class="todo-main">{{ product.name }}</span>
              <span class="todo-meta mono">{{ product.sku }}</span>
              <span class="todo-amount">{{ formatPrice(product.price) }}</span>
            </button>
          </li>
        </ul>
      </el-card>
    </section>
  </div>
</template>

<style scoped>
.dashboard {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.dashboard-title {
  margin: 0;
  font-size: 22px;
}

.dashboard-subtitle {
  margin: 6px 0 0;
  color: var(--el-text-color-secondary);
}

/* 漸層數字卡片 */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 20px;
}

.kpi-card {
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  padding: 22px 24px;
  border: none;
  border-radius: 16px;
  color: #fff;
  text-align: left;
  cursor: pointer;
  box-shadow: 0 12px 30px rgb(0 0 0 / 25%);
  transition: transform 0.15s ease;
}

/* 右下角的半透明圓，參考圖卡片上的裝飾 */
.kpi-card::after {
  position: absolute;
  right: -30px;
  bottom: -40px;
  width: 120px;
  height: 120px;
  border-radius: 50%;
  background: rgb(255 255 255 / 12%);
  content: '';
}

.kpi-card:hover,
.kpi-card:focus-visible {
  transform: translateY(-2px);
}

.kpi-label {
  font-size: 14px;
  opacity: 0.95;
}

.kpi-value {
  font-size: 40px;
  font-weight: 700;
  line-height: 1.1;
}

.kpi-hint {
  font-size: 12px;
  opacity: 0.8;
}

/* 其他狀態的小卡片 */
.stat-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 16px;
}

.stat-chip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 20px;
  border: none;
  border-radius: 14px;
  color: var(--el-text-color-primary);
  background: var(--el-bg-color);
  cursor: pointer;
}

.stat-chip:hover,
.stat-chip:focus-visible {
  background: var(--el-fill-color);
}

.stat-label {
  color: var(--el-text-color-regular);
}

.stat-value {
  min-width: 40px;
  padding: 2px 10px;
  border-radius: 6px;
  font-weight: 700;
  text-align: center;
  color: var(--app-accent-cyan);
  background: rgb(34 211 238 / 12%);
}

/* 待辦清單 */
.todo-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 20px;
}

.todo-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}

.todo-card :deep(.el-card__header) {
  border-bottom: none;
  padding-bottom: 0;
}

.todo-empty {
  margin: 0;
  color: var(--el-text-color-secondary);
}

.todo-error {
  color: var(--el-color-danger);
}

.todo-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.todo-item {
  display: grid;
  grid-template-columns: 1fr auto auto;
  align-items: center;
  gap: 16px;
  width: 100%;
  padding: 12px 8px;
  border: none;
  border-bottom: 1px solid var(--el-border-color-lighter);
  color: var(--el-text-color-primary);
  text-align: left;
  background: transparent;
  cursor: pointer;
}

.todo-list li:last-child .todo-item {
  border-bottom: none;
}

.todo-item:hover,
.todo-item:focus-visible {
  background: var(--el-fill-color-light);
}

.todo-main {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.todo-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.todo-amount {
  font-weight: 600;
  color: var(--app-accent-cyan);
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
</style>
