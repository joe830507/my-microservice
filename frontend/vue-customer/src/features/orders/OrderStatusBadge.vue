<script setup lang="ts">
import { ORDER_STATUS_LABELS } from './status'
import type { OrderStatus } from './types'

defineProps<{ status: OrderStatus }>()
</script>

<template>
  <span class="status-badge" :class="`status-badge--${status.toLowerCase()}`">
    {{ ORDER_STATUS_LABELS[status] }}
  </span>
</template>

<style scoped>
.status-badge {
  display: inline-block;
  padding: 0 var(--space-2);
  border-radius: var(--radius-sm);
  background: var(--color-bg);
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
  font-weight: 600;
  white-space: nowrap;
}

.status-badge--pending_payment {
  background: #fff4e5;
  color: #b25e09;
}

.status-badge--paid,
.status-badge--shipped {
  background: #e8f1fd;
  color: #1a56b0;
}

.status-badge--completed {
  background: #e6f6ef;
  color: var(--color-success);
}

.status-badge--cancelled {
  background: #fdecea;
  color: var(--color-danger);
}
</style>
