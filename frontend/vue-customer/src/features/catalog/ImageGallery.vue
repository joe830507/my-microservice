<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ProductImage } from './types'

// 主圖在前（沒有主圖就照後端的順序），點縮圖切換大圖；沒有圖片顯示預設圖
const props = defineProps<{ images: ProductImage[]; productName: string }>()

const ordered = computed(() => {
  const primary = props.images.filter((image) => image.isPrimary)
  const others = props.images.filter((image) => !image.isPrimary)
  return [...primary, ...others]
})

const selected = ref(0)
// 換商品、圖片變少時回到第一張，不要停在不存在的索引
watch(ordered, () => (selected.value = 0))

const current = computed(() => ordered.value[selected.value])
</script>

<template>
  <div class="gallery">
    <div class="gallery__main">
      <img v-if="current" :src="current.url" :alt="current.altText || productName" />
      <div v-else class="gallery__placeholder" role="img" aria-label="尚無商品圖片">尚無圖片</div>
    </div>
    <ul v-if="ordered.length > 1" class="gallery__thumbs">
      <li v-for="(image, index) in ordered" :key="image.id">
        <button
          type="button"
          :class="{ 'is-selected': index === selected }"
          :aria-label="`第 ${index + 1} 張圖片`"
          :aria-pressed="index === selected"
          @click="selected = index"
        >
          <img :src="image.url" alt="" loading="lazy" />
        </button>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.gallery__main {
  aspect-ratio: 1 / 1;
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
}

.gallery__main img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.gallery__placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--color-text-muted);
}

.gallery__thumbs {
  display: flex;
  gap: var(--space-2);
  margin: var(--space-3) 0 0;
  padding: 0;
  overflow-x: auto;
  list-style: none;
}

.gallery__thumbs button {
  width: 64px;
  height: 64px;
  padding: 0;
  overflow: hidden;
  border: 2px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  cursor: pointer;
}

.gallery__thumbs button.is-selected {
  border-color: var(--color-primary);
}

.gallery__thumbs img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}
</style>
