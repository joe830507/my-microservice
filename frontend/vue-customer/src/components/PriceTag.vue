<script setup lang="ts">
import { computed } from 'vue'
import { discountPercent, formatPrice } from '@/utils/format'

// 價格；有原價（而且比售價高）時顯示刪除線的原價與折扣百分比
const props = defineProps<{ price: number; originalPrice: number | null }>()

const discount = computed(() => discountPercent(props.price, props.originalPrice))
</script>

<template>
  <div class="price-tag">
    <span class="price-tag__price">NT$ {{ formatPrice(price) }}</span>
    <template v-if="discount !== null && originalPrice !== null">
      <del class="price-tag__original">NT$ {{ formatPrice(originalPrice) }}</del>
      <span class="price-tag__discount">-{{ discount }}%</span>
    </template>
  </div>
</template>

<style scoped>
.price-tag {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--space-1) var(--space-2);
}

.price-tag__price {
  color: var(--color-primary);
  font-weight: 700;
}

.price-tag__original {
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
}

.price-tag__discount {
  padding: 0 var(--space-2);
  border-radius: var(--radius-sm);
  background: var(--color-primary);
  color: var(--color-on-primary);
  font-size: var(--font-size-sm);
}
</style>
