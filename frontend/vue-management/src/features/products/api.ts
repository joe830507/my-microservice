import apiClient from '@/api/http'
import type {
  ChangeProductStatusRes,
  Product,
  ProductPage,
  ProductQueryParams,
  ProductReq,
  UpdateProductReq,
} from './types'

// 包含所有狀態（草稿、封存也會列出）
export async function getProducts(params: ProductQueryParams): Promise<ProductPage> {
  const { data } = await apiClient.get<ProductPage>('/products', { params })
  return data
}

export async function getProduct(id: string): Promise<Product> {
  const { data } = await apiClient.get<Product>(`/products/${id}`)
  return data
}

// 新增時狀態固定是 DRAFT
export async function createProduct(req: ProductReq): Promise<Product> {
  const { data } = await apiClient.post<Product>('/products', req)
  return data
}

export async function updateProduct(id: string, req: UpdateProductReq): Promise<Product> {
  const { data } = await apiClient.put<Product>(`/products/${id}`, req)
  return data
}

export type StatusAction = 'publish' | 'unpublish' | 'archive'

export async function changeProductStatus(
  id: string,
  action: StatusAction,
  version: number,
): Promise<ChangeProductStatusRes> {
  const { data } = await apiClient.post<ChangeProductStatusRes>(`/products/${id}/${action}`, {
    version,
  })
  return data
}
