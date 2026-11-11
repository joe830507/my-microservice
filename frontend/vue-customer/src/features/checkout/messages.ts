import { ClientErrorCode, toApiError } from '@/api/errors'
import type { CartLine } from '@/features/cart/lines'

export interface CreateOrderFailure {
  message: string
  // 商品狀態可能已經變了：畫面要重新查商品，更新「無法購買」的標示
  refresh: boolean
  // 不確定訂單有沒有建立（沒收到回應）：提示使用者先到「我的訂單」確認
  uncertain: boolean
}

// 後端的 detail 是 "out of stock: SKU-A, SKU-B."、"product is not available for purchase: id1, id2."
// 取出冒號後面的清單；格式不符回傳空陣列
function parseList(detail: string): string[] {
  const colon = detail.indexOf(': ')
  if (colon < 0) return []
  return detail
    .slice(colon + 2)
    .replace(/\.$/, '')
    .split(',')
    .map((part) => part.trim())
    .filter(Boolean)
}

// 把後端回的 SKU 或商品 id 換成顧客看得懂的商品名稱；對不到就不顯示識別碼，只說「部分商品」
function namesOf(keys: string[], lines: CartLine[], field: 'sku' | 'id'): string[] {
  return keys.flatMap((key) => {
    const line = lines.find((l) => (field === 'sku' ? l.product?.sku === key : l.productId === key))
    return line?.product ? [`「${line.product.name}」`] : []
  })
}

function listOrFallback(names: string[]): string {
  return names.length > 0 ? names.join('、') : '部分商品'
}

// 建單失敗時顯示的訊息；lines 是送出時的商品清單（用來把 SKU、id 換成名稱）
export function createOrderFailure(error: unknown, lines: CartLine[]): CreateOrderFailure {
  const apiError = toApiError(error)
  const base = { refresh: false, uncertain: false }

  switch (apiError.code) {
    case 'OUT_OF_STOCK': {
      const names = namesOf(parseList(apiError.detail), lines, 'sku')
      return {
        ...base,
        message: `${listOrFallback(names)}庫存不足，請調整數量或移除後再試`,
      }
    }
    case 'PRODUCT_NOT_AVAILABLE':
    case 'PRODUCT_NOT_FOUND': {
      const names = namesOf(parseList(apiError.detail), lines, 'id')
      return {
        ...base,
        refresh: true,
        message: `${listOrFallback(names)}已無法購買，請移除後再結帳`,
      }
    }
    // 下游服務連不上：訂單確定沒有建立，可以稍後再試
    case 'PRODUCT_SERVICE_UNAVAILABLE':
    case 'INVENTORY_SERVICE_UNAVAILABLE':
    case 'TOO_MANY_REQUESTS':
      return { ...base, message: '系統目前忙碌，訂單尚未建立，請稍後再試' }
    // 沒有收到回應：請求可能已經送到後端並建立了訂單，直接再送一次會變成兩張
    case ClientErrorCode.NETWORK_ERROR:
    case ClientErrorCode.TIMEOUT:
      return {
        ...base,
        uncertain: true,
        message: '無法確認訂單是否已建立，請先到「我的訂單」查看，確認沒有這張訂單再重新送出',
      }
    default:
      return { ...base, message: apiError.detail }
  }
}
