import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

// 購物車只存 productId 與數量：價格、名稱、狀態可能被後台改，存在瀏覽器的會過期，
// 購物車頁與結帳頁每次用 GET /products/batch 重新查
export interface CartItem {
  productId: string
  qty: number
}

// 和建單 API（CreateOrderReqDto）的限制一致：單項 1–99 件、一張訂單最多 50 種商品
export const MAX_QTY = 99
export const MAX_ITEMS = 50

// 不跟帳號綁定，key 沒有使用者 id；登出時保留（雛型）
export const CART_KEY = 'vue-customer.cart'

function readStoredItems(): CartItem[] {
  try {
    const raw = localStorage.getItem(CART_KEY)
    const parsed: unknown = raw ? JSON.parse(raw) : []
    if (!Array.isArray(parsed)) return []
    // 內容可能被改過或來自舊版本：不合法的項目丟掉，數量限制在 1–MAX_QTY，同一商品合併，種類不超過上限
    const merged = new Map<string, number>()
    for (const item of parsed as Partial<CartItem>[]) {
      if (typeof item?.productId === 'string' && Number.isInteger(item.qty) && item.qty! >= 1) {
        merged.set(item.productId, Math.min(MAX_QTY, (merged.get(item.productId) ?? 0) + item.qty!))
      }
    }
    return [...merged].slice(0, MAX_ITEMS).map(([productId, qty]) => ({ productId, qty }))
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

// 數量取整數並限制在 1–MAX_QTY；NaN 會讓 Math.max 也回 NaN，先擋掉
function normalizeQty(qty: number): number {
  return Number.isFinite(qty) ? Math.min(MAX_QTY, Math.max(1, Math.floor(qty))) : 1
}

export const useCartStore = defineStore('cart', () => {
  const items = ref<CartItem[]>(readStoredItems())

  // 頂部列顯示的件數：所有商品的數量加總
  const count = computed(() => items.value.reduce((sum, item) => sum + item.qty, 0))

  function persist(): void {
    writeStoredItems(items.value)
  }

  // 同一商品再加入時合併數量，總數不超過 MAX_QTY
  // 回傳 false：購物車已有 MAX_ITEMS 種商品，不能再加新的商品（已在購物車裡的商品仍可增加數量）
  function add(productId: string, qty: number): boolean {
    const amount = normalizeQty(qty)
    const existing = items.value.find((item) => item.productId === productId)
    if (existing) {
      existing.qty = Math.min(MAX_QTY, existing.qty + amount)
    } else {
      if (items.value.length >= MAX_ITEMS) {
        return false
      }
      items.value.push({ productId, qty: amount })
    }
    persist()
    return true
  }

  // 直接設定數量（購物車頁）；商品不在購物車裡不處理
  function setQty(productId: string, qty: number): void {
    const existing = items.value.find((item) => item.productId === productId)
    if (existing) {
      existing.qty = normalizeQty(qty)
      persist()
    }
  }

  function remove(productId: string): void {
    removeMany([productId])
  }

  // 一次移除多個（結帳成功後移除已下單的商品、清掉無法購買的商品）
  function removeMany(productIds: string[]): void {
    const ids = new Set(productIds)
    items.value = items.value.filter((item) => !ids.has(item.productId))
    persist()
  }

  return { items, count, add, setQty, remove, removeMany }
})
