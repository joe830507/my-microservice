// 管理員帳密不寫進 repo：用環境變數傳入（本機開發用的是 backend/spring-user-service/secrets/dev.env 的值）
export function requiredEnv(name: string): string {
  const value = process.env[name]
  if (!value) {
    throw new Error(`缺少環境變數 ${name}：E2E 需要後端的管理員帳號（見 step-12-e2e.md）`)
  }
  return value
}

export function adminCredentials(): { email: string; password: string } {
  return { email: requiredEnv('E2E_ADMIN_EMAIL'), password: requiredEnv('E2E_ADMIN_PASSWORD') }
}
