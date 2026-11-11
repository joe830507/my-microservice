import { ApiError } from '@/api/errors'
import apiClient from '@/api/http'
import type {
  CategoryBySlug,
  CategoryTreeNode,
  GetCategoriesTreeRes,
  Product,
  ProductImage,
  ProductImagesRes,
  ProductPage,
  ProductQueryParams,
  Stock,
} from './types'

export async function getCategoryTree(): Promise<CategoryTreeNode[]> {
  const { data } = await apiClient.get<GetCategoriesTreeRes>('/categories/tree')
  return data.roots
}

export async function getCategoryBySlug(slug: string): Promise<CategoryBySlug> {
  const { data } = await apiClient.get<CategoryBySlug>(
    `/categories/slug/${encodeURIComponent(slug)}`,
  )
  return data
}

export async function getProducts(params: ProductQueryParams): Promise<ProductPage> {
  const { data } = await apiClient.get<ProductPage>('/products', { params })
  return data
}

export async function getProduct(id: string): Promise<Product> {
  const { data } = await apiClient.get<Product>(`/products/${encodeURIComponent(id)}`)
  return data
}

export async function getProductImages(id: string): Promise<ProductImage[]> {
  const { data } = await apiClient.get<ProductImagesRes>(
    `/products/${encodeURIComponent(id)}/images`,
  )
  return data.images
}

// 還沒建立庫存時回 null（後端回 404 STOCK_NOT_FOUND），畫面上當成缺貨
export async function getStock(sku: string): Promise<Stock | null> {
  try {
    const { data } = await apiClient.get<Stock>(`/inventory/${encodeURIComponent(sku)}`)
    return data
  } catch (error) {
    if (error instanceof ApiError && error.code === 'STOCK_NOT_FOUND') {
      return null
    }
    throw error
  }
}
