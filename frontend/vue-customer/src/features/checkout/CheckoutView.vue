<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { lineSubtotal } from '@/features/cart/lines'
import { useCartStore } from '@/features/cart/store'
import { useCartLines } from '@/features/cart/useCartLines'
import { createOrder } from '@/features/orders/api'
import { formatPrice } from '@/utils/format'
import { createOrderFailure, type CreateOrderFailure } from './messages'

const cart = useCartStore()
const router = useRouter()

// 結帳前重新查價格與狀態，畫面上的金額只是預覽，實際金額以建單時的快照為準
const { loaded, errorMessage, load, purchasableLines, unavailableLines, total } = useCartLines()

onMounted(load)

const submitting = ref(false)
const failure = ref<CreateOrderFailure | null>(null)

async function submit(): Promise<void> {
  // 送出中不再送第二次：沒有冪等 key，重送會建立兩張訂單、各自預扣庫存
  if (submitting.value || purchasableLines.value.length === 0) {
    return
  }
  submitting.value = true
  failure.value = null
  // 送出當下的商品清單：失敗訊息要用它把 SKU、id 換成名稱，成功後只移除這些
  const ordered = purchasableLines.value
  try {
    const order = await createOrder({
      items: ordered.map((line) => ({ productId: line.productId, qty: line.qty })),
    })
    // 只移除這次下單的商品，無法購買而沒有送出的留在購物車
    cart.removeMany(ordered.map((line) => line.productId))
    // replace：按上一頁不會回到可以再送出的結帳頁
    await router.replace({ name: 'order-pay', params: { id: order.id } })
  } catch (error) {
    failure.value = createOrderFailure(error, ordered)
    // 購物車不變；商品狀態可能已經變了，重新查
    if (failure.value.refresh) {
      void load()
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section>
    <h1>結帳</h1>

    <div v-if="cart.items.length === 0" class="checkout-empty">
      <p>購物車是空的</p>
      <RouterLink :to="{ name: 'search' }" class="btn">去逛逛</RouterLink>
    </div>

    <p v-else-if="errorMessage" class="form-error" role="alert">
      {{ errorMessage }}
      <button type="button" class="checkout-link" @click="load">重試</button>
    </p>

    <p v-else-if="!loaded" class="checkout-hint">載入中…</p>

    <template v-else>
      <p v-if="unavailableLines.length > 0" class="form-error">
        有 {{ unavailableLines.length }} 項商品已無法購買，不會送出。
        <RouterLink :to="{ name: 'cart' }" class="checkout-link">回購物車處理</RouterLink>
      </p>

      <p v-if="purchasableLines.length === 0" class="checkout-hint">
        沒有可以結帳的商品，
        <RouterLink :to="{ name: 'cart' }" class="checkout-link">回購物車</RouterLink>
      </p>

      <template v-else>
        <ul class="checkout-lines">
          <li v-for="line in purchasableLines" :key="line.productId" class="checkout-line">
            <span class="checkout-line__name">{{ line.product?.name }}</span>
            <span class="checkout-line__qty">
              NT$ {{ formatPrice(line.product?.price ?? 0) }} × {{ line.qty }}
            </span>
            <span class="checkout-line__subtotal">NT$ {{ formatPrice(lineSubtotal(line)) }}</span>
          </li>
        </ul>

        <div class="checkout-summary">
          <p class="checkout-summary__total">
            總計 <strong>NT$ {{ formatPrice(total) }}</strong>
          </p>
          <p class="checkout-summary__note">
            送出訂單後庫存會先為你保留，請在 30 分鐘內完成付款，逾時訂單會自動取消。
          </p>

          <p v-if="failure" class="form-error" role="alert">
            {{ failure.message }}
            <RouterLink v-if="failure.uncertain" :to="{ name: 'orders' }" class="checkout-link">
              我的訂單
            </RouterLink>
            <RouterLink v-else-if="!failure.refresh" :to="{ name: 'cart' }" class="checkout-link">
              回購物車
            </RouterLink>
          </p>

          <button type="button" class="btn btn-block" :disabled="submitting" @click="submit">
            {{ submitting ? '送出中…' : '送出訂單' }}
          </button>
        </div>
      </template>
    </template>
  </section>
</template>

<style scoped>
.checkout-empty,
.checkout-hint {
  padding: var(--space-8) 0;
  text-align: center;
  color: var(--color-text-muted);
}

.checkout-link {
  border: 0;
  background: none;
  color: var(--color-primary);
  font: inherit;
  text-decoration: underline;
  cursor: pointer;
}

.checkout-lines {
  margin: 0 0 var(--space-4);
  padding: 0;
  list-style: none;
}

.checkout-line {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: var(--space-1) var(--space-4);
  padding: var(--space-3) 0;
  border-bottom: 1px solid var(--color-border);
}

.checkout-line__name {
  grid-column: 1 / -1;
  font-weight: 600;
}

.checkout-line__qty {
  color: var(--color-text-muted);
}

.checkout-line__subtotal {
  font-weight: 700;
  text-align: right;
}

.checkout-summary {
  padding: var(--space-4);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
}

.checkout-summary__total {
  margin: 0 0 var(--space-2);
  font-size: var(--font-size-lg);
  text-align: right;
}

.checkout-summary__total strong {
  color: var(--color-primary);
}

.checkout-summary__note {
  margin: 0 0 var(--space-3);
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
}

@media (min-width: 768px) {
  .checkout-line {
    grid-template-columns: 1fr auto 140px;
  }

  .checkout-line__name {
    grid-column: auto;
  }

  .checkout-summary {
    max-width: 420px;
    margin-left: auto;
  }
}
</style>
