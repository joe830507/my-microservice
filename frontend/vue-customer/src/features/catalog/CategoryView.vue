<script setup lang="ts">
import { ref, watch } from 'vue'
import { toApiError } from '@/api/errors'
import NotFoundView from '@/views/NotFoundView.vue'
import { getCategoryBySlug } from './api'
import { useCategoryStore } from './categoryStore'
import ProductListing from './ProductListing.vue'
import type { CategoryBySlug, CategoryTreeNode } from './types'

// /c/:slug：用 slug 找分類，列出該分類與所有子分類的商品；分類不存在（或已停用）顯示 404
const props = defineProps<{ slug: string }>()

const categories = useCategoryStore()
void categories.load()

const category = ref<CategoryBySlug | null>(null)
const notFound = ref(false)
const errorMessage = ref('')
const loading = ref(false)

let latestRequest = 0

async function load(): Promise<void> {
  const requestId = ++latestRequest
  loading.value = true
  notFound.value = false
  errorMessage.value = ''
  category.value = null
  try {
    const found = await getCategoryBySlug(props.slug)
    if (requestId === latestRequest) category.value = found
  } catch (error) {
    if (requestId !== latestRequest) return
    const apiError = toApiError(error)
    if (apiError.code === 'CATEGORY_NOT_FOUND') {
      notFound.value = true
    } else {
      errorMessage.value = apiError.detail
    }
  } finally {
    if (requestId === latestRequest) loading.value = false
  }
}

watch(() => props.slug, load, { immediate: true })

function subcategories(): CategoryTreeNode[] {
  return categories.findBySlug(props.slug)?.children ?? []
}
</script>

<template>
  <NotFoundView v-if="notFound" />
  <section v-else-if="category">
    <h1>{{ category.name }}</h1>
    <p v-if="category.description" class="category-description">{{ category.description }}</p>
    <ul v-if="subcategories().length > 0" class="subcategories" aria-label="子分類">
      <li v-for="child in subcategories()" :key="child.id">
        <RouterLink :to="{ name: 'category', params: { slug: child.slug } }">
          {{ child.name }}
        </RouterLink>
      </li>
    </ul>
    <!-- 換分類時整個重建，避免沿用上一個分類的結果 -->
    <ProductListing :key="category.id" :category-id="category.id" />
  </section>
  <p v-else-if="errorMessage" class="form-error" role="alert">
    {{ errorMessage }}
    <button type="button" @click="load">重試</button>
  </p>
  <p v-else-if="loading" class="category-loading">載入中…</p>
</template>

<style scoped>
.category-description {
  color: var(--color-text-muted);
}

.subcategories {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin: 0 0 var(--space-4);
  padding: 0;
  list-style: none;
}

.subcategories a {
  display: inline-block;
  padding: var(--space-1) var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: 999px;
  background: var(--color-surface);
  font-size: var(--font-size-sm);
}

.subcategories a:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.category-loading {
  padding: var(--space-8) 0;
  text-align: center;
  color: var(--color-text-muted);
}
</style>
