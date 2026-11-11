<script setup lang="ts">
// 數量選擇：1 到 max；按鈕、直接輸入、超出範圍都會被限制回範圍內
const props = withDefaults(defineProps<{ max?: number; min?: number }>(), { max: 99, min: 1 })
const model = defineModel<number>({ required: true })

function clamp(value: number): number {
  if (!Number.isFinite(value)) return props.min
  return Math.min(props.max, Math.max(props.min, Math.floor(value)))
}

function set(value: number): void {
  model.value = clamp(value)
}

function onInput(event: Event): void {
  const input = event.target as HTMLInputElement
  const next = clamp(Number(input.value))
  // 輸入框顯示的字串要同步：輸入 0 或 999 時，v-model 的值沒變也要改回範圍內的數字
  input.value = String(next)
  model.value = next
}
</script>

<template>
  <div class="quantity">
    <button type="button" aria-label="減少數量" :disabled="model <= min" @click="set(model - 1)">
      −
    </button>
    <input
      type="number"
      inputmode="numeric"
      aria-label="數量"
      :min="min"
      :max="max"
      :value="model"
      @change="onInput"
    />
    <button type="button" aria-label="增加數量" :disabled="model >= max" @click="set(model + 1)">
      +
    </button>
  </div>
</template>

<style scoped>
.quantity {
  display: inline-flex;
  align-items: stretch;
}

.quantity button,
.quantity input {
  height: 44px;
  border: 1px solid var(--color-border);
  background: var(--color-surface);
  font: inherit;
}

.quantity button {
  width: 44px;
  cursor: pointer;
}

.quantity button:first-child {
  border-radius: var(--radius-sm) 0 0 var(--radius-sm);
}

.quantity button:last-child {
  border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
}

.quantity button:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.quantity input {
  width: 64px;
  border-right: 0;
  border-left: 0;
  text-align: center;
  appearance: textfield;
}
</style>
