import { computed, ref } from 'vue'
import { toApiError } from '@/api/errors'
import { getProductsByIds, type BatchProduct } from './api'
import { buildLines, cartTotal } from './lines'
import { useCartStore } from './store'

// 購物車頁與結帳頁共用：用 GET /products/batch 重新查購物車裡商品的最新名稱、價格、狀態
// 購物車的增減（數量、移除）立刻反映在 lines 與 total；商品資料只在 load 時查
export function useCartLines() {
  const cart = useCartStore()

  const products = ref<BatchProduct[]>([])
  const loading = ref(false)
  const loaded = ref(false)
  const errorMessage = ref('')

  async function load(): Promise<void> {
    loading.value = true
    errorMessage.value = ''
    try {
      products.value = await getProductsByIds(cart.items.map((item) => item.productId))
      loaded.value = true
    } catch (error) {
      errorMessage.value = toApiError(error).detail
    } finally {
      loading.value = false
    }
  }

  const lines = computed(() => buildLines(cart.items, products.value))
  const purchasableLines = computed(() => lines.value.filter((line) => line.purchasable))
  const unavailableLines = computed(() => lines.value.filter((line) => !line.purchasable))
  const total = computed(() => cartTotal(lines.value))

  return {
    products,
    loading,
    loaded,
    errorMessage,
    load,
    lines,
    purchasableLines,
    unavailableLines,
    total,
  }
}
