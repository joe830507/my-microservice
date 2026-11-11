import apiClient from '@/api/http'
import type { LoginReq, Me, RegisterReq, TokenRes } from './types'

// 登入、refresh、登出都不需要 access token，收到 401 也不能再觸發 refresh，所以帶 skipAuth

// 註冊成功回 201 與使用者資料，但沒有 token；要登入還得再呼叫 login
export async function register(req: RegisterReq): Promise<void> {
  await apiClient.post('/auth/register', req, { skipAuth: true })
}

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

// 撤銷這個帳號所有裝置的 refresh token（包含目前這台）；回 204。要帶 access token，所以不是 skipAuth
// 已經發出去的 access token 仍會在到期前（約 5 分鐘）有效，這是無狀態 JWT 的限制
export async function revokeAllSessions(): Promise<void> {
  await apiClient.delete('/users/me/sessions')
}
