import apiClient from '@/api/http'
import type {
  CategoryBySlug,
  CategoryTreeNode,
  GetCategoriesTreeRes,
  ProductPage,
  ProductQueryParams,
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
