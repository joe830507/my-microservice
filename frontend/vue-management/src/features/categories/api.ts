import apiClient from '@/api/http'
import type { Category, CategoryReq, GetAllCategoriesRes, UpdateCategoryReq } from './types'

// 後台用的平面列表，包含停用的分類（/categories/tree 只有啟用中的，給前台用）
export async function getAllCategories(): Promise<Category[]> {
  const { data } = await apiClient.get<GetAllCategoriesRes>('/categories')
  return data.categories
}

export async function createCategory(req: CategoryReq): Promise<Category> {
  const { data } = await apiClient.post<Category>('/categories', req)
  return data
}

export async function updateCategory(id: string, req: UpdateCategoryReq): Promise<Category> {
  const { data } = await apiClient.put<Category>(`/categories/${id}`, req)
  return data
}

// 底下還有子分類或商品時，後端回 409 DATA_CONFLICT
export async function deleteCategory(id: string): Promise<void> {
  await apiClient.delete(`/categories/${id}`)
}
