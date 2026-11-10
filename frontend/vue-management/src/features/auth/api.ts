import apiClient from '@/api/http'
import type { LoginReq, Me, TokenRes } from './types'

// 登入、refresh、登出都不需要 access token，收到 401 也不能再觸發 refresh，所以帶 skipAuth

export async function login(req: LoginReq): Promise<TokenRes> {
  const { data } = await apiClient.post<TokenRes>('/auth/login', req, { skipAuth: true })
  return data
}

export async function refresh(refreshToken: string): Promise<TokenRes> {
  const { data } = await apiClient.post<TokenRes>(
    '/auth/refresh',
    { refreshToken },
    { skipAuth: true },
  )
  return data
}

// 後端一律回 204（token 不存在或已失效也一樣）
export async function logout(refreshToken: string): Promise<void> {
  await apiClient.post('/auth/logout', { refreshToken }, { skipAuth: true })
}

export async function getMe(): Promise<Me> {
  const { data } = await apiClient.get<Me>('/users/me')
  return data
}
