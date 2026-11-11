<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { toApiError } from '@/api/errors'
import { useRouteQuery } from '@/composables/useRouteQuery'
import { getAllCategories } from '@/features/categories/api'
import { buildTree } from '@/features/categories/tree'
import type { Category } from '@/features/categories/types'
import { formatDateTime, formatPrice } from '@/utils/format'
import * as productApi from './api'
import {
  DEFAULT_PRODUCT_QUERY,
  PAGE_SIZES,
  parseProductQuery,
  toApiParams,
  toRouteQuery,
  type ProductListQuery,
} from './query'
import { STATUS_LABELS, STATUS_TAG_TYPES } from './status'
import {
  PRODUCT_STATUSES,
  type ProductListItem,
  type ProductPage,
  type ProductSortBy,
} from './types'

const router = useRouter()
const { state: query, update } = useRouteQuery(parseProductQuery, toRouteQuery)

// 篩選表單：使用者按「搜尋」才寫進 URL
const filters = reactive({
  keyword: '',
  categoryId: '',
  status: '' as ProductListQuery['status'],
  minPrice: '',
  maxPrice: '',
})
const filterError = ref('')

const result = ref<ProductPage | null>(null)
const loading = ref(false)
const loadError = ref('')

// 分類：篩選用的樹，以及列表顯示分類名稱
const categories = ref<Category[]>([])
const categoryTree = computed(() => buildTree(categories.value))
const categoryNames = computed(
  () => new Map(categories.value.map((category) => [category.id, category.name])),
)

// 依序編號：條件快速切換時，只採用最後一次請求的結果
let requestSeq = 0

async function load(current: ProductListQuery): Promise<void> {
  const seq = ++requestSeq
  loading.value = true
  loadError.value = ''
  try {
    const page = await productApi.getProducts(toApiParams(current))
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

// URL 變了（搜尋、換頁、上一頁）：同步篩選表單並重新讀取
watch(
  query,
  (current) => {
    filters.keyword = current.keyword
    filters.categoryId = current.categoryId
    filters.status = current.status
    filters.minPrice = current.minPrice
    filters.maxPrice = current.maxPrice
    void load(current)
  },
  { immediate: true },
)

onMounted(async () => {
  try {
    categories.value = await getAllCategories()
  } catch {
    // 分類讀不到只影響分類名稱與篩選，列表本身照常顯示
  }
})

const PRICE_PATTERN = /^\d+(\.\d{1,2})?$/

function onSearch(): void {
  const minPrice = filters.minPrice.trim()
  const maxPrice = filters.maxPrice.trim()
  if ((minPrice && !PRICE_PATTERN.test(minPrice)) || (maxPrice && !PRICE_PATTERN.test(maxPrice))) {
    filterError.value = '價格只能是數字，最多兩位小數'
    return
  }
  if (minPrice && maxPrice && Number(minPrice) > Number(maxPrice)) {
    filterError.value = '最低價格不能大於最高價格'
    return
  }
  filterError.value = ''
  // 條件變了就回第一頁
  void update({
    ...query.value,
    keyword: filters.keyword.trim(),
    categoryId: filters.categoryId || '',
    status: filters.status || '',
    minPrice,
    maxPrice,
    page: 1,
  })
}

function onReset(): void {
  filterError.value = ''
  void update({ ...DEFAULT_PRODUCT_QUERY, size: query.value.size })
}

function onPageChange(page: number): void {
  void update({ ...query.value, page })
}

function onSizeChange(size: number): void {
  void update({ ...query.value, size, page: 1 })
}

// 表格欄位排序：取消排序時回到預設（最新的排前面）
function onSortChange({ prop, order }: { prop: string | null; order: string | null }): void {
  if (!order || !isSortField(prop)) {
    void update({
      ...query.value,
      sortBy: DEFAULT_PRODUCT_QUERY.sortBy,
      sortDirection: DEFAULT_PRODUCT_QUERY.sortDirection,
      page: 1,
    })
    return
  }
  void update({
    ...query.value,
    sortBy: prop,
    sortDirection: order === 'ascending' ? 'asc' : 'desc',
    page: 1,
  })
}

function isSortField(prop: string | null): prop is ProductSortBy {
  return prop === 'createdAt' || prop === 'price' || prop === 'name'
}

const defaultSort = computed(() => ({
  prop: query.value.sortBy,
  order: query.value.sortDirection === 'asc' ? ('ascending' as const) : ('descending' as const),
}))

function openEdit(product: ProductListItem): void {
  void router.push({ name: 'product-edit', params: { id: product.id } })
}
</script>

<template>
  <el-card>
    <el-form class="filters" inline @submit.prevent="onSearch">
      <el-form-item label="名稱">
        <el-input v-model="filters.keyword" name="keyword" placeholder="商品名稱" clearable />
      </el-form-item>
      <el-form-item label="分類">
        <el-tree-select
          v-model="filters.categoryId"
          :data="categoryTree"
          :props="{ label: 'name', children: 'children' }"
          node-key="id"
          check-strictly
          clearable
          placeholder="全部"
          class="filter-select"
        />
      </el-form-item>
      <el-form-item label="狀態">
        <el-select v-model="filters.status" placeholder="全部" clearable class="filter-select">
          <el-option
            v-for="status in PRODUCT_STATUSES"
            :key="status"
            :label="STATUS_LABELS[status]"
            :value="status"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="價格">
        <el-input
          v-model="filters.minPrice"
          name="minPrice"
          placeholder="最低"
          class="price-input"
        />
        <span class="price-separator">–</span>
        <el-input
          v-model="filters.maxPrice"
          name="maxPrice"
          placeholder="最高"
          class="price-input"
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
      :title="`無法讀取商品：${loadError}`"
      :closable="false"
      show-icon
    />

    <div class="toolbar">
      <el-button
        type="primary"
        class="create-button"
        @click="router.push({ name: 'product-create' })"
      >
        新增商品
      </el-button>
    </div>

    <el-table
      :key="`${query.sortBy}-${query.sortDirection}`"
      v-loading="loading"
      :data="result?.items ?? []"
      :default-sort="defaultSort"
      row-key="id"
      empty-text="沒有符合條件的商品"
      @sort-change="onSortChange"
    >
      <el-table-column prop="sku" label="SKU" min-width="120" />
      <el-table-column prop="name" label="名稱" min-width="180" sortable="custom" />
      <el-table-column label="分類" min-width="120">
        <template #default="{ row }">
          {{ row.categoryId ? (categoryNames.get(row.categoryId) ?? '（未知分類）') : '—' }}
        </template>
      </el-table-column>
      <el-table-column prop="price" label="售價" width="120" align="right" sortable="custom">
        <template #default="{ row }">{{ formatPrice(row.price) }}</template>
      </el-table-column>
      <el-table-column label="原價" width="120" align="right">
        <template #default="{ row }">
          {{ row.originalPrice === null ? '—' : formatPrice(row.originalPrice) }}
        </template>
      </el-table-column>
      <el-table-column label="狀態" width="100">
        <template #default="{ row }">
          <el-tag :type="STATUS_TAG_TYPES[row.status as ProductListItem['status']]">
            {{ STATUS_LABELS[row.status as ProductListItem['status']] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="建立時間" width="180" sortable="custom">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            class="edit-button"
            @click="openEdit(row as ProductListItem)"
          >
            編輯
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
      :page-sizes="PAGE_SIZES"
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
  width: 180px;
}

.price-input {
  width: 100px;
}

.price-separator {
  margin: 0 8px;
}

.filter-error,
.load-error {
  margin-bottom: 16px;
}

.toolbar {
  margin-bottom: 16px;
}

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
