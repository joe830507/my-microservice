import { HttpResponse } from 'msw'
import type { Me, TokenRes } from '../types'

// auth 測試共用的假資料

export function tokens(n: number): TokenRes {
  return {
    accessToken: `access-${n}`,
    tokenType: 'Bearer',
    expiresIn: 900,
    refreshToken: `refresh-${n}`,
    refreshExpiresIn: 1209600,
  }
}

export function me(roles: Me['roles'] = ['CUSTOMER']): Me {
  return {
    id: '7f1c2a9e-0000-4000-8000-000000000001',
    email: 'user@example.com',
    displayName: '小明',
    status: 'ACTIVE',
    roles,
    createdAt: '2026-11-01T00:00:00Z',
    version: 0,
  }
}

// 和後端一樣的 ProblemDetail 回應
export function problem(status: number, code: string, detail = code) {
  return HttpResponse.json(
    { type: 'about:blank', status, detail, code },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}
