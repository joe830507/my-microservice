import { describe, expect, it } from 'vitest'
import { ApiError } from '@/api/errors'
import { deleteErrorMessage, saveErrorMessage } from '../messages'

describe('saveErrorMessage', () => {
  it.each([
    ['CATEGORY_SLUG_DUPLICATED', '這個 slug 已被其他分類使用'],
    ['CATEGORY_NAME_DUPLICATED', '同一層已有相同名稱的分類'],
    ['CATEGORY_PARENT_NOT_FOUND', '上層分類不存在，請重新整理後再試'],
    ['CATEGORY_PARENT_INVALID', '上層分類不能是自己或自己的子分類'],
    ['CATEGORY_NOT_FOUND', '分類不存在，可能已被刪除，請重新整理'],
    ['VERSION_CONFLICT', '分類已被其他人修改，請重新整理後再試'],
    ['DATA_CONFLICT', '名稱或 slug 和其他分類重複'],
  ])('%s', (code, message) => {
    expect(saveErrorMessage(new ApiError(400, code, 'server detail'))).toBe(message)
  })

  it('VALIDATION_FAILED：附上後端的 detail', () => {
    expect(saveErrorMessage(new ApiError(400, 'VALIDATION_FAILED', 'slug: must match'))).toBe(
      '欄位格式不正確：slug: must match',
    )
  })

  it('其他代碼：顯示 detail', () => {
    expect(saveErrorMessage(new ApiError(0, 'NETWORK_ERROR', '無法連線'))).toBe('無法連線')
  })
})

describe('deleteErrorMessage', () => {
  it('DATA_CONFLICT：底下還有子分類或商品', () => {
    expect(deleteErrorMessage(new ApiError(409, 'DATA_CONFLICT', 'data conflicts'))).toBe(
      '這個分類底下還有子分類或商品，無法刪除',
    )
  })

  it('其他代碼：顯示 detail', () => {
    expect(deleteErrorMessage(new ApiError(403, 'FORBIDDEN', 'forbidden'))).toBe('forbidden')
  })
})
