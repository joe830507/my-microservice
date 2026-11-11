import { describe, expect, it } from 'vitest'
import {
  validateDisplayName,
  validateEmail,
  validateLoginPassword,
  validateNewPassword,
} from '../validation'

describe('validateEmail', () => {
  it('合法的 Email（前後空白會去掉）', () => {
    expect(validateEmail(' user@example.com ')).toBe('')
  })

  it.each([
    ['', '請輸入 Email'],
    ['   ', '請輸入 Email'],
    ['user', 'Email 格式不正確'],
    ['user@', 'Email 格式不正確'],
    ['a b@example.com', 'Email 格式不正確'],
    [`${'a'.repeat(250)}@b.co`, 'Email 格式不正確'],
  ])('%j → %s', (value, message) => {
    expect(validateEmail(value)).toBe(message)
  })
})

describe('validateLoginPassword', () => {
  it('只檢查有沒有填', () => {
    expect(validateLoginPassword('')).toBe('請輸入密碼')
    expect(validateLoginPassword('x')).toBe('')
  })
})

describe('validateNewPassword', () => {
  it('8–72 個字元合法（邊界）', () => {
    expect(validateNewPassword('a'.repeat(8))).toBe('')
    expect(validateNewPassword('a'.repeat(72))).toBe('')
  })

  it('太短、太長、沒填', () => {
    expect(validateNewPassword('')).toBe('請輸入密碼')
    expect(validateNewPassword('a'.repeat(7))).toBe('密碼長度需為 8–72 個字元')
    expect(validateNewPassword('a'.repeat(73))).toBe('密碼長度需為 8–72 個字元')
  })

  it('字元數合格但超過 72 bytes（中文）：拒絕', () => {
    expect(validateNewPassword('密'.repeat(24))).toBe('') // 72 bytes
    expect(validateNewPassword('密'.repeat(25))).toContain('密碼過長')
  })
})

describe('validateDisplayName', () => {
  it('1–50 個字元合法（前後空白不算）', () => {
    expect(validateDisplayName(' 小明 ')).toBe('')
    expect(validateDisplayName('x'.repeat(50))).toBe('')
  })

  it('沒填、只有空白、太長', () => {
    expect(validateDisplayName('')).toBe('請輸入顯示名稱')
    expect(validateDisplayName('   ')).toBe('請輸入顯示名稱')
    expect(validateDisplayName('x'.repeat(51))).toBe('顯示名稱最多 50 個字元')
  })
})
