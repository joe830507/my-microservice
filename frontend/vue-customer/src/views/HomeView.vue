<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { toApiError } from '@/api/errors'
import ProductCard from '@/components/ProductCard.vue'
import { getProducts } from '@/features/catalog/api'
import { useCategoryStore } from '@/features/catalog/categoryStore'
import type { ProductListItem } from '@/features/catalog/types'

const LATEST_COUNT = 8

const categories = useCategoryStore()
const latest = ref<ProductListItem[]>([])
const loading = ref(true)
const errorMessage = ref('')

async function loadLatest(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const page = await getProducts({
      page: 0,
      size: LATEST_COUNT,
      sortBy: 'createdAt',
      sortDirection: 'desc',
    })
    latest.value = page.items
  } catch (error) {
    errorMessage.value = toApiError(error).detail
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void categories.load()
  void loadLatest()
})
</script>

<template>
  <section>
    <h1>歡迎光臨</h1>

    <nav v-if="categories.tree.length > 0" class="home-categories" aria-label="商品分類">
      <RouterLink
        v-for="category in categories.tree"
        :key="category.id"
        :to="{ name: 'category', params: { slug: category.slug } }"
        class="home-categories__item"
      >
        {{ category.name }}
      </RouterLink>
    </nav>

    <h2>最新上架</h2>
    <p v-if="errorMessage" class="form-error" role="alert">
      {{ errorMessage }}
      <button type="button" @click="loadLatest">重試</button>
    </p>
    <p v-else-if="loading" class="home-hint">載入中…</p>
    <p v-else-if="latest.length === 0" class="home-hint">商品即將上架。</p>
    <template v-else>
      <ul class="home-grid">
        <li v-for="product in latest" :key="product.id">
          <ProductCard :product="product" />
        </li>
      </ul>
      <p class="home-more"><RouterLink :to="{ name: 'search' }">查看全部商品 →</RouterLink></p>
    </template>
  </section>
</template>

<style scoped>
.home-categories {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--space-3);
  margin-bottom: var(--space-6);
}

.home-categories__item {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 72px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  font-weight: 600;
}

.home-categories__item:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.home-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--space-3);
  margin: 0;
  padding: 0;
  list-style: none;
}

.home-hint {
  padding: var(--space-8) 0;
  text-align: center;
  color: var(--color-text-muted);
}

.home-more {
  text-align: center;
}

.home-more a {
  color: var(--color-primary);
}

@media (min-width: 768px) {
  .home-categories {
    grid-template-columns: repeat(4, 1fr);
  }

  .home-grid {
    grid-template-columns: repeat(4, 1fr);
    gap: var(--space-4);
  }
}
</style>
