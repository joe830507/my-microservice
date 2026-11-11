<script setup lang="ts">
import { onMounted } from 'vue'
import NotFoundView from '@/views/NotFoundView.vue'
import { usePayFlow } from '@/features/payments/usePayFlow'
import { formatCountdown, formatPrice } from '@/utils/format'

const props = defineProps<{ id: string }>()

const flow = usePayFlow(() => props.id)
const { phase, order, payment, message, remainingMs, expired } = flow

onMounted(flow.load)
</script>

<template>
  <NotFoundView v-if="phase === 'not-found'" />
  <section v-else class="pay">
    <h1>付款</h1>

    <p v-if="phase === 'loading'" class="pay__hint">載入中…</p>

    <p v-else-if="phase === 'error'" class="form-error" role="alert">
      {{ message }}
      <button type="button" class="pay__link" @click="flow.load">重試</button>
    </p>

    <template v-else-if="order">
      <div class="pay__order">
        <p class="pay__amount">
          應付金額 <strong>NT$ {{ formatPrice(order.totalAmount) }}</strong>
        </p>
        <ul class="pay__items">
          <li v-for="item in order.items" :key="item.productId">
            {{ item.productName }} × {{ item.qty }}
          </li>
        </ul>
      </div>

      <!-- 等待付款 -->
      <template v-if="phase === 'ready' || phase === 'processing'">
        <p v-if="expired" class="form-error" role="alert">
          付款期限已到，訂單即將被取消，無法再付款。
          <RouterLink :to="{ name: 'orders' }" class="pay__link">我的訂單</RouterLink>
        </p>
        <p v-else class="pay__deadline">
          請在 <strong>{{ formatCountdown(remainingMs) }}</strong> 內完成付款，逾時訂單會自動取消
        </p>

        <p v-if="message" class="form-error" role="alert">{{ message }}</p>

        <p class="pay__mock">模擬金流：請選擇付款結果</p>
        <div class="pay__actions">
          <button
            type="button"
            class="btn"
            :disabled="phase === 'processing' || expired"
            @click="flow.pay('SUCCESS')"
          >
            付款成功
          </button>
          <button
            type="button"
            class="btn pay__fail"
            :disabled="phase === 'processing' || expired"
            @click="flow.pay('FAILED')"
          >
            付款失敗
          </button>
        </div>
        <p v-if="phase === 'processing'" class="pay__hint" role="status">處理中…</p>
      </template>

      <!-- 付款成功，等訂單更新 -->
      <p v-else-if="phase === 'confirming'" class="pay__hint" role="status">
        付款成功，正在確認訂單…
      </p>

      <div v-else-if="phase === 'paid'" class="pay__result pay__result--ok" role="status">
        <h2>付款完成</h2>
        <p>謝謝你的購買，我們會盡快為你出貨。</p>
        <RouterLink :to="{ name: 'orders' }" class="btn">查看我的訂單</RouterLink>
      </div>

      <div v-else-if="phase === 'failed'" class="pay__result" role="alert">
        <h2>付款失敗</h2>
        <p v-if="payment">這次付款沒有成功，訂單還在，可以重新付款。</p>
        <button type="button" class="btn" @click="flow.startPayment">重新付款</button>
      </div>

      <div v-else-if="phase === 'cancelled'" class="pay__result" role="alert">
        <h2>訂單已取消</h2>
        <p v-if="flow.cancelledAfterPaid.value">
          付款完成時訂單已經被取消（付款逾時），已付的款項會退回。
        </p>
        <p v-else>這張訂單已經取消，無法再付款，保留的庫存已釋出。</p>
        <RouterLink :to="{ name: 'search' }" class="btn">繼續逛逛</RouterLink>
      </div>

      <div v-else-if="phase === 'delayed'" class="pay__result" role="status">
        <h2>付款處理中</h2>
        <p>付款已成功，訂單狀態還在更新，請稍後到「我的訂單」查看。</p>
        <div class="pay__actions">
          <button type="button" class="btn" @click="flow.confirm">再查一次</button>
          <RouterLink :to="{ name: 'orders' }" class="pay__link">我的訂單</RouterLink>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.pay {
  max-width: 520px;
  margin: 0 auto;
}

.pay__hint {
  padding: var(--space-4) 0;
  text-align: center;
  color: var(--color-text-muted);
}

.pay__order {
  padding: var(--space-4);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
}

.pay__amount {
  margin: 0 0 var(--space-2);
  font-size: var(--font-size-lg);
}

.pay__amount strong {
  color: var(--color-primary);
}

.pay__items {
  margin: 0;
  padding-left: var(--space-4);
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
}

.pay__deadline {
  margin: var(--space-4) 0;
  text-align: center;
}

.pay__deadline strong {
  color: var(--color-danger);
  font-variant-numeric: tabular-nums;
}

.pay__mock {
  margin: var(--space-4) 0 var(--space-2);
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
}

.pay__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3);
}

.pay__actions .btn {
  flex: 1 1 140px;
  min-height: 44px;
}

.pay__fail {
  background: var(--color-danger);
}

.pay__result {
  margin-top: var(--space-4);
  padding: var(--space-4);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  text-align: center;
}

.pay__result--ok h2 {
  color: var(--color-success);
}

.pay__link {
  border: 0;
  background: none;
  color: var(--color-primary);
  font: inherit;
  text-decoration: underline;
  cursor: pointer;
}
</style>
