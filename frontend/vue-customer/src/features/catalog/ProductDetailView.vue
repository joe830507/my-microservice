<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { toApiError } from '@/api/errors'
import PriceTag from '@/components/PriceTag.vue'
import QuantityInput from '@/components/QuantityInput.vue'
import { MAX_ITEMS, MAX_QTY, useCartStore } from '@/features/cart/store'
import NotFoundView from '@/views/NotFoundView.vue'
import { getProduct, getProductImages, getStock } from './api'
import ImageGallery from './ImageGallery.vue'
import type { Product, ProductImage, Stock } from './types'

// 庫存少於這個數量時提示「僅剩 N 件」
const LOW_STOCK = 5

const props = defineProps<{ id: string }>()
const cart = useCartStore()

const product = ref<Product | null>(null)
const images = ref<ProductImage[]>([])
// null：還沒有庫存資料（STOCK_NOT_FOUND），當成缺貨
const stock = ref<Stock | null>(null)
const stockError = ref(false)
const notFound = ref(false)
const errorMessage = ref('')
const loading = ref(false)

const quantity = ref(1)
const addedQty = ref(0)
const cartFull = ref(false)

// 快速在商品間切換時，舊請求晚回來不能蓋掉新的商品
let latestRequest = 0

async function loadStock(sku: string, requestId: number): Promise<void> {
  stockError.value = false
  try {
    const result = await getStock(sku)
    if (requestId === latestRequest) stock.value = result
  } catch {
    if (requestId === latestRequest) {
      stock.value = null
      stockError.value = true
    }
  }
}

async function load(): Promise<void> {
  const requestId = ++latestRequest
  loading.value = true
  notFound.value = false
  errorMessage.value = ''
  product.value = null
  images.value = []
  stock.value = null
  stockError.value = false
  quantity.value = 1
  addedQty.value = 0
  cartFull.value = false
  const imageRequest = getProductImages(props.id).catch(() => [] as ProductImage[])
  try {
    const found = await getProduct(props.id)
    if (requestId !== latestRequest) return
    product.value = found
    // 圖片與庫存互相獨立：圖片載入失敗只是沒有圖，庫存失敗只是不能加入購物車，都不影響看商品資訊
    await Promise.all([
      imageRequest
        .then((result) => {
          if (requestId === latestRequest) images.value = result
        })
        .catch(() => {}),
      loadStock(found.sku, requestId),
    ])
  } catch (error) {
    if (requestId !== latestRequest) return
    const apiError = toApiError(error)
    // 下架、不存在，以及網址上的 id 不是合法的 UUID（400）都視為找不到
    if (apiError.code === 'PRODUCT_NOT_FOUND' || apiError.status === 400) {
      notFound.value = true
    } else {
      errorMessage.value = apiError.detail
    }
  } finally {
    if (requestId === latestRequest) loading.value = false
  }
}

watch(() => props.id, load, { immediate: true })

const available = computed(() => (stock.value ? Math.max(0, stock.value.available) : 0))
const inStock = computed(() => available.value > 0)
const maxQty = computed(() => Math.min(MAX_QTY, available.value))

// 庫存變少（重新查詢）時，已選的數量不能超過可賣數量
watch(maxQty, (max) => {
  if (max > 0 && quantity.value > max) quantity.value = max
})

function addToCart(): void {
  if (!product.value || !inStock.value) return
  // 購物車已滿（種類上限，和建單 API 一致）時不能再加新的商品
  const added = cart.add(product.value.id, quantity.value)
  cartFull.value = !added
  addedQty.value = added ? quantity.value : 0
}

function retryStock(): void {
  if (product.value) void loadStock(product.value.sku, latestRequest)
}
</script>

<template>
  <NotFoundView v-if="notFound" />
  <article v-else-if="product" class="detail">
    <ImageGallery :images="images" :product-name="product.name" class="detail__gallery" />

    <div class="detail__info">
      <p v-if="product.brand" class="detail__brand">{{ product.brand }}</p>
      <h1 class="detail__name">{{ product.name }}</h1>
      <PriceTag
        class="detail__price"
        :price="product.price"
        :original-price="product.originalPrice"
      />

      <p v-if="stockError" class="form-error" role="alert">
        庫存查詢失敗，暫時無法加入購物車
        <button type="button" class="detail__link" @click="retryStock">重試</button>
      </p>
      <p v-else-if="!inStock" class="detail__stock detail__stock--out">缺貨</p>
      <p v-else class="detail__stock">
        {{ available <= LOW_STOCK ? `僅剩 ${available} 件` : '尚有庫存' }}
      </p>

      <div class="detail__buy">
        <QuantityInput v-model="quantity" :max="Math.max(1, maxQty)" />
        <button type="button" class="btn detail__add" :disabled="!inStock" @click="addToCart">
          加入購物車
        </button>
      </div>
      <p v-if="cartFull" class="form-error" role="alert">
        購物車最多放 {{ MAX_ITEMS }} 種商品，請先結帳或移除部分商品
      </p>
      <p v-if="addedQty > 0" class="detail__added" role="status">
        已加入 {{ addedQty }} 件到購物車，
        <RouterLink :to="{ name: 'cart' }" class="detail__link">前往購物車</RouterLink>
      </p>

      <section v-if="product.description" class="detail__description">
        <h2>商品說明</h2>
        <p>{{ product.description }}</p>
      </section>
    </div>
  </article>
  <p v-else-if="errorMessage" class="form-error" role="alert">
    {{ errorMessage }}
    <button type="button" class="detail__link" @click="load">重試</button>
  </p>
  <p v-else-if="loading" class="detail__loading">載入中…</p>
</template>

<style scoped>
.detail {
  display: grid;
  gap: var(--space-6);
}

.detail__brand {
  margin: 0;
  color: var(--color-text-muted);
}

.detail__name {
  margin: var(--space-1) 0 var(--space-3);
  font-size: var(--font-size-xl);
}

.detail__price {
  font-size: var(--font-size-lg);
}

.detail__stock {
  margin: var(--space-3) 0;
  color: var(--color-success);
  font-weight: 600;
}

.detail__stock--out {
  color: var(--color-danger);
}

.detail__buy {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
  margin-top: var(--space-4);
}

.detail__add {
  flex: 1 1 160px;
  min-height: 44px;
}

.detail__added {
  margin: var(--space-3) 0 0;
  color: var(--color-success);
}

.detail__link {
  border: 0;
  background: none;
  color: var(--color-primary);
  font: inherit;
  text-decoration: underline;
  cursor: pointer;
}

/* 保留說明裡的換行；用文字插值輸出，不當成 HTML */
.detail__description p {
  white-space: pre-wrap;
}

.detail__loading {
  padding: var(--space-8) 0;
  text-align: center;
  color: var(--color-text-muted);
}

@media (min-width: 768px) {
  .detail {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    gap: var(--space-8);
  }
}
</style>
