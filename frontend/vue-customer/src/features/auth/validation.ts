// 前端的欄位檢查，規則和 user-service 的 RegisterReqDto 一致；真正的檢查在後端，這裡只是讓使用者早點知道
// 每個函式回傳錯誤訊息，沒問題回傳空字串

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const EMAIL_MAX = 254
const PASSWORD_MIN = 8
const PASSWORD_MAX = 72
const DISPLAY_NAME_MAX = 50

export function validateEmail(value: string): string {
  const email = value.trim()
  if (!email) return '請輸入 Email'
  if (email.length > EMAIL_MAX || !EMAIL_PATTERN.test(email)) return 'Email 格式不正確'
  return ''
}

// 登入時只檢查有沒有填：舊帳號的密碼規則不一定和現在相同
export function validateLoginPassword(value: string): string {
  return value ? '' : '請輸入密碼'
}

export function validateNewPassword(value: string): string {
  if (!value) return '請輸入密碼'
  if (value.length < PASSWORD_MIN || value.length > PASSWORD_MAX) {
    return `密碼長度需為 ${PASSWORD_MIN}–${PASSWORD_MAX} 個字元`
  }
  // bcrypt 只取前 72 bytes：中文等多位元組字元會讓字元數合格但 bytes 超過
  if (new TextEncoder().encode(value).length > PASSWORD_MAX) {
    return `密碼過長（上限 ${PASSWORD_MAX} bytes，中文一個字約 3 bytes）`
  }
  return ''
}

export function validateDisplayName(value: string): string {
  const name = value.trim()
  if (!name) return '請輸入顯示名稱'
  if (name.length > DISPLAY_NAME_MAX) return `顯示名稱最多 ${DISPLAY_NAME_MAX} 個字元`
  return ''
}
