import apiClient from '@/api/http'

// GET /products/batch：只有下單需要的欄位（沒有圖片、原價）；不存在的 id 不會出現在結果裡
export interface BatchProduct {
  id: string
  sku: string
  name: string
  price: number
  // 公開 API 照舊回傳所有狀態，購物車靠它知道哪些商品已下架
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
