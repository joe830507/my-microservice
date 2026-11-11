// 對應 user-service 的 DTO（LoginReqDto、TokenResDto、GetMeResDto）

export type Role = 'CUSTOMER' | 'ADMIN'

export type UserStatus = 'ACTIVE' | 'DISABLED'

export interface LoginReq {
  email: string
  password: string
}

export interface TokenRes {
  accessToken: string
  // 固定為 Bearer
  tokenType: string
  // access token 剩餘有效秒數
  expiresIn: number
  // 每次 refresh 都會換新，舊的立即失效
  refreshToken: string
  refreshExpiresIn: number
}

export interface Me {
  id: string
  email: string
  displayName: string
  status: UserStatus
  roles: Role[]
  createdAt: string
  version: number
}
