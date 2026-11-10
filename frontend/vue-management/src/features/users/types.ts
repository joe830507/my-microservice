import type { Role, UserStatus } from '@/features/auth/types'

export type { Role, UserStatus }

export const USER_STATUSES: UserStatus[] = ['ACTIVE', 'DISABLED']
export const ROLES: Role[] = ['ADMIN', 'CUSTOMER']

// 對應 user-service 的 UserListItem
export interface UserListItem {
  id: string
  email: string
  displayName: string
  status: UserStatus
  roles: Role[]
  createdAt: string
}

export interface UserPage {
  items: UserListItem[]
  // 從 0 開始
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export type UserSortBy = 'createdAt' | 'email' | 'displayName'

// GET /users 的查詢參數（page 從 0 開始）
export interface UserQueryParams {
  keyword?: string
  status?: UserStatus
  role?: Role
  page: number
  size: number
  sortBy: UserSortBy
  sortDirection: 'asc' | 'desc'
}
