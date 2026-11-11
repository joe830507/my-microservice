import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

// 購物車只存 productId 與數量：價格、名稱、狀態可能被後台改，存在瀏覽器的會過期，
// 購物車頁與結帳頁每次用 GET /products/batch 重新查
export interface CartItem {
  productId: string
  qty: number
}

// 和建單 API 的單項上限一致
export const MAX_QTY = 99

// 不跟帳號綁定，key 沒有使用者 id；登出時保留（雛型）
export const CART_KEY = 'vue-customer.cart'

function readStoredItems(): CartItem[] {
  try {
    const raw = localStorage.getItem(CART_KEY)
    const parsed: unknown = raw ? JSON.parse(raw) : []
    if (!Array.isArray(parsed)) return []
    // 內容可能被改過或來自舊版本：不合法的項目丟掉，數量限制在 1–MAX_QTY，同一商品合併
    const merged = new Map<string, number>()
    for (const item of parsed as Partial<CartItem>[]) {
      if (typeof item?.productId === 'string' && Number.isInteger(item.qty) && item.qty! >= 1) {
        merged.set(item.productId, Math.min(MAX_QTY, (merged.get(item.productId) ?? 0) + item.qty!))
      }
    }
    return [...merged].map(([productId, qty]) => ({ productId, qty }))
  } catch {
    return []
  }
}

function writeStoredItems(items: CartItem[]): void {
  try {
    localStorage.setItem(CART_KEY, JSON.stringify(items))
  } catch {
    // 無痕模式、儲存空間已滿或被封鎖：只是重新整理後購物車會空掉，這次使用不受影響
  }
}

export const useCartStore = defineStore('cart', () => {
  const items = ref<CartItem[]>(readStoredItems())

  // 頂部列顯示的件數：所有商品的數量加總
  const count = computed(() => items.value.reduce((sum, item) => sum + item.qty, 0))

  function persist(): void {
    writeStoredItems(items.value)
  }

  // 同一商品再加入時合併數量，總數不超過 MAX_QTY
  function add(productId: string, qty: number): void {
    // NaN 會讓 Math.max 也回 NaN，先擋掉
    const amount = Number.isFinite(qty) ? Math.max(1, Math.floor(qty)) : 1
    const existing = items.value.find((item) => item.productId === productId)
    if (existing) {
      existing.qty = Math.min(MAX_QTY, existing.qty + amount)
    } else {
      items.value.push({ productId, qty: Math.min(MAX_QTY, amount) })
    }
    persist()
  }

  return { items, count, add }
})
