<script setup lang="ts">
import { ref, watch } from 'vue'
import { toApiError } from '@/api/errors'
import { formatDateTime } from '@/utils/format'
import * as inventoryApi from './api'
import type { Stock } from './api'

const props = defineProps<{
  // 已儲存的 SKU（不是表單上還沒儲存的值）
  sku: string
  // 封存的商品不調整庫存
  readOnly?: boolean
}>()

const stock = ref<Stock | null>(null)
// 還沒讀到資料之前不知道有沒有建立庫存
const loaded = ref(false)
const loading = ref(false)
const saving = ref(false)
const errorMessage = ref('')
const available = ref<number | null>(null)

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    stock.value = await inventoryApi.getStock(props.sku)
    available.value = stock.value?.available ?? 0
    loaded.value = true
  } catch (error) {
    errorMessage.value = `無法讀取庫存：${toApiError(error).detail}`
  } finally {
    loading.value = false
  }
}

// SKU 改了（商品儲存了新的 SKU）就讀新的
watch(() => props.sku, load, { immediate: true })

async function onSave(): Promise<void> {
  const value = available.value
  if (value === null || !Number.isInteger(value) || value < 0) {
    errorMessage.value = '可賣數量要是 0 以上的整數'
    return
  }

  saving.value = true
  errorMessage.value = ''
  try {
    await inventoryApi.setStock(props.sku, value)
    ElMessage.success(stock.value ? '庫存已更新' : '庫存已建立')
    // 重新讀取：預扣數量可能同時有變化，以後端為準
    await load()
  } catch (error) {
    errorMessage.value = `無法儲存庫存：${toApiError(error).detail}`
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section v-loading="loading" class="product-inventory">
    <h3 class="inventory-title">庫存</h3>

    <el-alert
      v-if="errorMessage"
      class="inventory-error"
      type="error"
      :title="errorMessage"
      :closable="false"
      show-icon
    />

    <template v-if="loaded">
      <el-descriptions v-if="stock" :column="3" border class="inventory-summary">
        <el-descriptions-item label="可賣數量">
          <span class="stock-available">{{ stock.available }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="已預扣（待付款）">
          <span class="stock-reserved">{{ stock.reserved }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="更新時間">
          {{ formatDateTime(stock.updatedAt) }}
        </el-descriptions-item>
      </el-descriptions>
      <el-alert
        v-else
        class="stock-missing"
        type="info"
        :title="`SKU ${sku} 還沒有建立庫存，設定可賣數量後就會建立`"
        :closable="false"
        show-icon
      />

      <el-form v-if="!readOnly" inline class="inventory-form" @submit.prevent="onSave">
        <el-form-item label="可賣數量">
          <el-input-number
            v-model="available"
            name="available"
            :min="0"
            :step="1"
            step-strictly
            :value-on-clear="null"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" native-type="submit" :loading="saving" class="stock-save">
            {{ stock ? '更新庫存' : '建立庫存' }}
          </el-button>
        </el-form-item>
      </el-form>
      <p class="inventory-hint">
        庫存以 SKU 對應；直接設定盤點後的可賣數量，已預扣的數量由下單流程管理。修改商品的 SKU 後，舊
        SKU 的庫存不會跟著搬過去，要重新設定。
      </p>
    </template>
  </section>
</template>

<style scoped>
.inventory-title {
  margin: 0 0 16px;
  font-size: 16px;
}

.inventory-error,
.stock-missing,
.inventory-summary {
  margin-bottom: 16px;
}

.inventory-hint {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
