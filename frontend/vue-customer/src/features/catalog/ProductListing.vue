<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { toApiError } from '@/api/errors'
import PaginationBar from '@/components/PaginationBar.vue'
import ProductCard from '@/components/ProductCard.vue'
import { useRouteQuery } from '@/composables/useRouteQuery'
import { getProducts } from './api'
import {
  parseProductQuery,
  priceRangeError,
  SORT_OPTIONS,
  toApiParams,
  toRouteQuery,
  type ProductListQuery,
  type ProductSort,
} from './query'
import type { ProductPage } from './types'

// 商品列表：條件（關鍵字、價格區間、排序、分頁）都在網址上；分類頁傳入 categoryId，搜尋頁不傳
const props = defineProps<{ categoryId?: string }>()

const { state, update } = useRouteQuery(parseProductQuery, toRouteQuery)

const result = ref<ProductPage | null>(null)
const loading = ref(false)
const errorMessage = ref('')

// 價格欄位是草稿，按「套用」才寫進網址；網址變動（上一頁、清除）時跟著同步
const draft = reactive({ minPrice: state.value.minPrice, maxPrice: state.value.maxPrice })
const priceError = ref('')
watch(
  () => [state.value.minPrice, state.value.maxPrice],
  ([minPrice, maxPrice]) => {
    draft.minPrice = minPrice ?? ''
    draft.maxPrice = maxPrice ?? ''
    priceError.value = ''
  },
)

// 快速切換條件時，舊的請求可能比新的晚回來：只接受最後一次發出的結果
let latestRequest = 0

async function load(): Promise<void> {
  const requestId = ++latestRequest
  loading.value = true
  errorMessage.value = ''
  try {
    const page = await getProducts(toApiParams(state.value, props.categoryId))
    if (requestId === latestRequest) {
      result.value = page
    }
  } catch (error) {
    if (requestId === latestRequest) {
      result.value = null
      errorMessage.value = toApiError(error).detail
    }
  } finally {
    if (requestId === latestRequest) {
      loading.value = false
    }
  }
}

watch([state, () => props.categoryId], load, { immediate: true })

function change(patch: Partial<ProductListQuery>): void {
  // 換條件就回到第一頁；只換頁碼時保留其他條件
  void update({ ...state.value, page: 1, ...patch })
}

function applyPrice(): void {
  const next = { minPrice: draft.minPrice.trim(), maxPrice: draft.maxPrice.trim() }
  const invalid = [next.minPrice, next.maxPrice].some(
    (v) => v !== '' && !/^\d+(\.\d{1,2})?$/.test(v),
  )
  priceError.value = invalid ? '價格請輸入數字（最多兩位小數）' : priceRangeError(next)
  if (!priceError.value) {
    change(next)
  }
}

function clearPrice(): void {
  priceError.value = ''
  change({ minPrice: '', maxPrice: '' })
}

function onSortChange(event: Event): void {
  change({ sort: (event.target as HTMLSelectElement).value as ProductSort })
}
</script>

<template>
  <div class="listing">
    <div class="listing__filters">
      <form class="listing__price" novalidate @submit.prevent="applyPrice">
        <label for="min-price">價格</label>
        <input
          id="min-price"
          v-model="draft.minPrice"
          name="minPrice"
          inputmode="decimal"
          placeholder="最低"
          aria-label="最低價"
        />
        <span aria-hidden="true">–</span>
        <input
          v-model="draft.maxPrice"
          name="maxPrice"
          inputmode="decimal"
          placeholder="最高"
          aria-label="最高價"
        />
        <button type="submit" class="btn">套用</button>
        <button
          v-if="state.minPrice || state.maxPrice"
          type="button"
          class="listing__clear"
          @click="clearPrice"
        >
          清除
        </button>
      </form>
      <div class="listing__sort">
        <label for="sort">排序</label>
        <select id="sort" :value="state.sort" @change="onSortChange">
          <option v-for="option in SORT_OPTIONS" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>
      </div>
    </div>
    <p v-if="priceError" class="field-error" role="alert">{{ priceError }}</p>

    <p v-if="errorMessage" class="form-error" role="alert">
      {{ errorMessage }}
      <button type="button" class="listing__retry" @click="load">重試</button>
    </p>
    <template v-else-if="result">
      <p class="listing__count" :aria-busy="loading">共 {{ result.totalElements }} 件商品</p>
      <ul v-if="result.items.length > 0" class="listing__grid" :class="{ 'is-loading': loading }">
        <li v-for="product in result.items" :key="product.id">
          <ProductCard :product="product" />
        </li>
      </ul>
      <p v-else class="listing__empty">找不到符合條件的商品</p>
      <PaginationBar
        :page="state.page"
        :total-pages="result.totalPages"
        @change="(page) => change({ page })"
      />
    </template>
    <p v-else-if="loading" class="listing__loading">載入中…</p>
  </div>
</template>

<style scoped>
.listing__filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
}

.listing__price,
.listing__sort {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.listing__price input {
  width: 6em;
  height: 40px;
  padding: 0 var(--space-2);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font: inherit;
}

.listing__sort select {
  height: 40px;
  padding: 0 var(--space-2);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  font: inherit;
}

.listing__clear,
.listing__retry {
  border: 0;
  background: none;
  color: var(--color-primary);
  font: inherit;
  text-decoration: underline;
  cursor: pointer;
}

.listing__count {
  margin: 0 0 var(--space-3);
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
}

/* 手機兩欄，越寬越多欄 */
.listing__grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--space-3);
  margin: 0;
  padding: 0;
  list-style: none;
}

.listing__grid.is-loading {
  opacity: 0.5;
}

.listing__empty,
.listing__loading {
  padding: var(--space-8) 0;
  text-align: center;
  color: var(--color-text-muted);
}

@media (min-width: 768px) {
  .listing__grid {
    grid-template-columns: repeat(3, 1fr);
    gap: var(--space-4);
  }
}

@media (min-width: 1024px) {
  .listing__grid {
    grid-template-columns: repeat(4, 1fr);
  }
}
</style>
