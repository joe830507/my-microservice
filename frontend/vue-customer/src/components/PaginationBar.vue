<script setup lang="ts">
import { computed } from 'vue'

// page 從 1 開始；頁數多時只顯示目前頁附近的頁碼，加上第一頁與最後一頁
const props = defineProps<{ page: number; totalPages: number }>()
const emit = defineEmits<{ change: [page: number] }>()

const WINDOW = 2

const pages = computed<(number | '…')[]>(() => {
  const total = props.totalPages
  const result: (number | '…')[] = []
  let previous = 0
  for (let p = 1; p <= total; p++) {
    if (p === 1 || p === total || Math.abs(p - props.page) <= WINDOW) {
      if (p - previous > 1) result.push('…')
      result.push(p)
      previous = p
    }
  }
  return result
})
</script>

<template>
  <nav v-if="totalPages > 1" class="pagination" aria-label="分頁">
    <button type="button" :disabled="page <= 1" @click="emit('change', page - 1)">上一頁</button>
    <template v-for="(item, index) in pages" :key="index">
      <span v-if="item === '…'" class="pagination__gap">…</span>
      <button
        v-else
        type="button"
        :class="{ 'is-current': item === page }"
        :aria-current="item === page ? 'page' : undefined"
        @click="item !== page && emit('change', item)"
      >
        {{ item }}
      </button>
    </template>
    <button type="button" :disabled="page >= totalPages" @click="emit('change', page + 1)">
      下一頁
    </button>
  </nav>
</template>

<style scoped>
.pagination {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--space-2);
  margin-top: var(--space-6);
}

.pagination button {
  min-width: 40px;
  min-height: 40px;
  padding: 0 var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  font: inherit;
  cursor: pointer;
}

.pagination button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.pagination button.is-current {
  border-color: var(--color-primary);
  background: var(--color-primary);
  color: var(--color-on-primary);
}

.pagination__gap {
  align-self: center;
  color: var(--color-text-muted);
}
</style>
