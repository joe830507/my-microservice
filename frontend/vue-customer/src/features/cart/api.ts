import apiClient from '@/api/http'

// GET /products/batch：只有下單需要的欄位（沒有圖片、原價）；不存在的 id 不會出現在結果裡
// 不是上架中（ACTIVE）的商品，後端對一般使用者只回 id 與 status，sku、name、price 是 null，
// 購物車只需要知道「不能買」；所以只有 status 是 ACTIVE 時才可以用這三個欄位
export interface BatchProduct {
  id: string
  sku: string | null
  name: string | null
  price: number | null
  status: string
}

interface GetProductsByIdsRes {
  products: BatchProduct[]
}

export async function getProductsByIds(ids: string[]): Promise<BatchProduct[]> {
  if (ids.length === 0) {
    return []
  }
  const { data } = await apiClient.get<GetProductsByIdsRes>('/products/batch', {
    params: { ids },
    // axios 預設把陣列寫成 ids[]=a&ids[]=b，Spring 的 @RequestParam List 要的是 ids=a&ids=b
    paramsSerializer: { indexes: null },
  })
  return data.products
}
