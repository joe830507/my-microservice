<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import QuantityInput from '@/components/QuantityInput.vue'
import { formatPrice } from '@/utils/format'
import { lineSubtotal } from './lines'
import { useCartStore } from './store'
import { useCartLines } from './useCartLines'

const cart = useCartStore()
const router = useRouter()

// 價格、名稱、狀態每次進來都重新查，不用存在瀏覽器的舊資料
const { loaded, errorMessage, load, lines, purchasableLines, unavailableLines, total } =
  useCartLines()

onMounted(load)

function removeUnavailable(): void {
  cart.removeMany(unavailableLines.value.map((line) => line.productId))
}

function checkout(): void {
  void router.push({ name: 'checkout' })
}
</script>

<template>
  <section>
    <h1>購物車</h1>

    <div v-if="cart.items.length === 0" class="cart-empty">
      <p>購物車是空的</p>
      <RouterLink :to="{ name: 'search' }" class="btn">去逛逛</RouterLink>
    </div>

    <p v-else-if="errorMessage" class="form-error" role="alert">
      {{ errorMessage }}
      <button type="button" class="cart-link" @click="load">重試</button>
    </p>

    <p v-else-if="!loaded" class="cart-hint">載入中…</p>

    <template v-else>
      <p v-if="unavailableLines.length > 0" class="form-error" role="alert">
        有 {{ unavailableLines.length }} 項商品已無法購買，不會計入總計，也不能結帳。
        <button type="button" class="cart-link" @click="removeUnavailable">全部移除</button>
      </p>

      <ul class="cart-lines">
        <li
          v-for="line in lines"
          :key="line.productId"
          class="cart-line"
          :class="{ 'is-unavailable': !line.purchasable }"
        >
          <div class="cart-line__info">
            <RouterLink
              v-if="line.product && line.purchasable"
              :to="{ name: 'product-detail', params: { id: line.productId } }"
              class="cart-line__name"
            >
              {{ line.product.name }}
            </RouterLink>
            <span v-else class="cart-line__name">{{ line.product?.name ?? '商品已不存在' }}</span>
            <p v-if="line.purchasable && line.product" class="cart-line__price">
              NT$ {{ formatPrice(line.product.price) }}
            </p>
            <p v-else class="cart-line__unavailable">無法購買</p>
          </div>

          <QuantityInput
            v-if="line.purchasable"
            :model-value="line.qty"
            @update:model-value="(qty) => cart.setQty(line.productId, qty)"
          />
          <p v-if="line.purchasable" class="cart-line__subtotal">
            NT$ {{ formatPrice(lineSubtotal(line)) }}
          </p>

          <button
            type="button"
            class="cart-link cart-line__remove"
            :aria-label="`移除 ${line.product?.name ?? '商品'}`"
            @click="cart.remove(line.productId)"
          >
            移除
          </button>
        </li>
      </ul>

      <div class="cart-summary">
        <p class="cart-summary__total">
          總計 <strong>NT$ {{ formatPrice(total) }}</strong>
        </p>
        <button
          type="button"
          class="btn btn-block"
          :disabled="purchasableLines.length === 0"
          @click="checkout"
        >
          前往結帳
        </button>
      </div>
    </template>
  </section>
</template>

<style scoped>
.cart-empty,
.cart-hint {
  padding: var(--space-8) 0;
  text-align: center;
  color: var(--color-text-muted);
}

.cart-link {
  border: 0;
  background: none;
  color: var(--color-primary);
  font: inherit;
  text-decoration: underline;
  cursor: pointer;
}

.cart-lines {
  margin: 0 0 var(--space-4);
  padding: 0;
  list-style: none;
}

/* 手機：資訊一列、數量與小計一列；寬螢幕排成一排 */
.cart-line {
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: center;
  gap: var(--space-2) var(--space-4);
  padding: var(--space-4) 0;
  border-bottom: 1px solid var(--color-border);
}

.cart-line__info {
  grid-column: 1 / -1;
}

.cart-line__name {
  font-weight: 600;
}

.cart-line__price {
  margin: var(--space-1) 0 0;
  color: var(--color-text-muted);
}

.cart-line__unavailable {
  margin: var(--space-1) 0 0;
  color: var(--color-danger);
  font-weight: 600;
}

.cart-line__subtotal {
  margin: 0;
  font-weight: 700;
  text-align: right;
}

.cart-line.is-unavailable .cart-line__name {
  color: var(--color-text-muted);
  text-decoration: line-through;
}

.cart-line__remove {
  justify-self: end;
}

.cart-summary {
  padding: var(--space-4);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
}

.cart-summary__total {
  margin: 0 0 var(--space-3);
  font-size: var(--font-size-lg);
  text-align: right;
}

.cart-summary__total strong {
  color: var(--color-primary);
}

@media (min-width: 768px) {
  .cart-line {
    grid-template-columns: 1fr auto 120px auto;
  }

  .cart-line__info {
    grid-column: auto;
  }

  .cart-summary {
    max-width: 360px;
    margin-left: auto;
  }
}
</style>
