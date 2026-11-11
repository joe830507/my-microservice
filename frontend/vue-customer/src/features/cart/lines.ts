import type { BatchProduct } from './api'
import type { CartItem } from './store'

// 購物車的一列：購物車存的 id 與數量，加上最新查回來的商品資料
export interface CartLine {
  productId: string
  qty: number
  // null：查不到（商品已被刪除）
  product: BatchProduct | null
  // 只有上架中（ACTIVE）的商品可以結帳；已下架、封存、草稿或查不到的都不行
  purchasable: boolean
}

export function buildLines(items: CartItem[], products: BatchProduct[]): CartLine[] {
  const byId = new Map(products.map((product) => [product.id, product]))
  return items.map((item) => {
    const product = byId.get(item.productId) ?? null
    return {
      productId: item.productId,
      qty: item.qty,
      product,
      purchasable: product?.status === 'ACTIVE',
    }
  })
}

// 金額用「分」計算再換回來：0.1 * 3 這類浮點誤差不會出現在畫面上
function toCents(price: number): number {
  return Math.round(price * 100)
}

export function lineSubtotal(line: CartLine): number {
  return line.product ? (toCents(line.product.price) * line.qty) / 100 : 0
}

// 總計只算可購買的商品
export function cartTotal(lines: CartLine[]): number {
  const cents = lines
    .filter((line) => line.purchasable && line.product)
    .reduce((sum, line) => sum + toCents(line.product!.price) * line.qty, 0)
  return cents / 100
}
