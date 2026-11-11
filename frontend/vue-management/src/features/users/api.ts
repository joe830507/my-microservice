import apiClient from '@/api/http'
import type { UserPage, UserQueryParams } from './types'

// 後台使用者列表，限 ADMIN；keyword 不分大小寫比對 email 或顯示名稱
export async function getUsers(params: UserQueryParams): Promise<UserPage> {
  const { data } = await apiClient.get<UserPage>('/users', { params })
  return data
}
