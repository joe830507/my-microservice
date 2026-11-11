<script setup lang="ts">
import type { ProductListItem } from '@/features/catalog/types'
import PriceTag from './PriceTag.vue'

defineProps<{ product: ProductListItem }>()
</script>

<template>
  <!-- 商品詳情頁在 Step 5；路由 name 先用 product-detail -->
  <RouterLink :to="{ name: 'product-detail', params: { id: product.id } }" class="product-card">
    <div class="product-card__media">
      <img
        v-if="product.primaryImageUrl"
        :src="product.primaryImageUrl"
        :alt="product.name"
        loading="lazy"
      />
      <!-- 沒有主圖的預設圖 -->
      <div v-else class="product-card__placeholder" role="img" aria-label="尚無商品圖片">
        尚無圖片
      </div>
    </div>
    <div class="product-card__body">
      <p v-if="product.brand" class="product-card__brand">{{ product.brand }}</p>
      <h3 class="product-card__name">{{ product.name }}</h3>
      <PriceTag :price="product.price" :original-price="product.originalPrice" />
    </div>
  </RouterLink>
</template>

<style scoped>
.product-card {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
}

.product-card:hover {
  border-color: var(--color-primary);
}

.product-card__media {
  aspect-ratio: 1 / 1;
  background: var(--color-bg);
}

.product-card__media img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.product-card__placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
}

.product-card__body {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  padding: var(--space-3);
}

.product-card__brand {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
}

/* 名稱最多兩行 */
.product-card__name {
  display: -webkit-box;
  margin: 0;
  overflow: hidden;
  font-size: var(--font-size-base);
  font-weight: 500;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
}
</style>
