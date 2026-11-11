import apiClient from '@/api/http'
import type { LoginReq, Me, TokenRes } from './types'

// 登入、refresh、登出都不需要 access token，收到 401 也不能再觸發 refresh，所以帶 skipAuth

export async function login(req: LoginReq): Promise<TokenRes> {
  const { data } = await apiClient.post<TokenRes>('/auth/login', req, { skipAuth: true })
  return data
}

// refresh token 在 HttpOnly cookie，瀏覽器自動帶（同源，不必設定）；程式碼看不到也不用傳
// X-Requested-With 是後端要求的：cookie 是瀏覽器自動帶的，跨站的表單和圖片請求加不上自訂標頭，用來擋 CSRF
const AJAX_HEADERS = { 'X-Requested-With': 'XMLHttpRequest' }

export async function refresh(): Promise<TokenRes> {
  const { data } = await apiClient.post<TokenRes>('/auth/refresh', undefined, {
    skipAuth: true,
    headers: AJAX_HEADERS,
  })
  return data
}

// 後端一律回 204（token 不存在或已失效也一樣），並要求瀏覽器刪掉 cookie
export async function logout(): Promise<void> {
  await apiClient.post('/auth/logout', undefined, { skipAuth: true, headers: AJAX_HEADERS })
}

export async function getMe(): Promise<Me> {
  const { data } = await apiClient.get<Me>('/users/me')
  return data
}
