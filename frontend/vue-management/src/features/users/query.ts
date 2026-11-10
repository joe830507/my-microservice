import type { LocationQuery, LocationQueryRaw, LocationQueryValue } from 'vue-router'
import {
  ROLES,
  USER_STATUSES,
  type Role,
  type UserQueryParams,
  type UserSortBy,
  type UserStatus,
} from './types'

// 使用者列表的條件，放在 URL query；URL 上的 page 從 1 開始，送給後端時減 1
export interface UserListQuery {
  keyword: string
  status: UserStatus | ''
  role: Role | ''
  page: number
  size: number
  sortBy: UserSortBy
  sortDirection: 'asc' | 'desc'
}

export const USER_PAGE_SIZES = [10, 20, 50, 100]

export const DEFAULT_USER_QUERY: UserListQuery = {
  keyword: '',
  status: '',
  role: '',
  page: 1,
  size: 20,
  sortBy: 'createdAt',
  sortDirection: 'desc',
}

const SORT_FIELDS: UserSortBy[] = ['createdAt', 'email', 'displayName']

function first(value: LocationQueryValue | LocationQueryValue[] | undefined): string {
  const single = Array.isArray(value) ? value[0] : value
  return typeof single === 'string' ? single.trim() : ''
}

function positiveInt(value: string, fallback: number): number {
  const parsed = Number(value)
  return Number.isInteger(parsed) && parsed >= 1 ? parsed : fallback
}

function oneOf<T extends string>(value: string, allowed: readonly T[]): T | '' {
  return (allowed as readonly string[]).includes(value) ? (value as T) : ''
}

// 不合法的值改回預設值
export function parseUserQuery(query: LocationQuery): UserListQuery {
  const size = positiveInt(first(query.size), DEFAULT_USER_QUERY.size)
  const direction = first(query.sortDirection)
  return {
    keyword: first(query.keyword).slice(0, 254),
    status: oneOf(first(query.status), USER_STATUSES),
    role: oneOf(first(query.role), ROLES),
    page: positiveInt(first(query.page), DEFAULT_USER_QUERY.page),
    size: USER_PAGE_SIZES.includes(size) ? size : DEFAULT_USER_QUERY.size,
    sortBy: oneOf(first(query.sortBy), SORT_FIELDS) || DEFAULT_USER_QUERY.sortBy,
    sortDirection: direction === 'asc' || direction === 'desc' ? direction : 'desc',
  }
}

export function toUserRouteQuery(query: UserListQuery): LocationQueryRaw {
  const result: LocationQueryRaw = {}
  for (const key of Object.keys(DEFAULT_USER_QUERY) as (keyof UserListQuery)[]) {
    const value = typeof query[key] === 'string' ? (query[key] as string).trim() : query[key]
    if (value !== DEFAULT_USER_QUERY[key] && value !== '') {
      result[key] = String(value)
    }
  }
  return result
}

export function toUserApiParams(query: UserListQuery): UserQueryParams {
  return {
    keyword: query.keyword || undefined,
    status: query.status || undefined,
    role: query.role || undefined,
    page: query.page - 1,
    size: query.size,
    sortBy: query.sortBy,
    sortDirection: query.sortDirection,
  }
}
