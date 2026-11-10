import { describe, expect, it } from 'vitest'
import { ApiError } from '@/api/errors'
import { productErrorMessage } from '../messages'
import { canArchive, canEdit, canPublish, canUnpublish } from '../status'
import type { ProductStatus } from '../types'

describe('商品狀態可以執行的操作', () => {
  it.each<[ProductStatus, boolean, boolean, boolean, boolean]>([
    // 狀態, 上架, 下架, 封存, 修改
    ['DRAFT', true, false, true, true],
    ['ACTIVE', false, true, true, true],
    ['INACTIVE', true, false, true, true],
    ['ARCHIVED', false, false, false, false],
  ])('%s', (status, publish, unpublish, archive, edit) => {
    expect(canPublish(status)).toBe(publish)
    expect(canUnpublish(status)).toBe(unpublish)
    expect(canArchive(status)).toBe(archive)
    expect(canEdit(status)).toBe(edit)
  })
})

describe('productErrorMessage', () => {
  it.each([
    ['PRODUCT_SKU_DUPLICATED', 'SKU 已被其他商品使用'],
    ['DATA_CONFLICT', 'SKU 已被其他商品使用'],
    ['PRODUCT_NOT_FOUND', '商品不存在，可能已被刪除'],
    ['PRODUCT_ARCHIVED', '商品已封存，不能修改'],
    ['PRODUCT_ALREADY_ARCHIVED', '商品已經封存'],
    ['PRODUCT_STATUS_TRANSITION_INVALID', '目前的狀態不能執行這個操作，請重新整理後再試'],
    ['PRODUCT_ACTIVE_CATEGORY_REQUIRED', '上架中的商品必須設定啟用中的分類'],
    ['PRODUCT_PRIMARY_IMAGE_REQUIRED', '上架前要先設定主圖'],
    ['CATEGORY_INACTIVE', '選擇的分類已停用'],
    ['VERSION_CONFLICT', '商品已被其他人修改，請重新整理後再試'],
  ])('%s', (code, message) => {
    expect(productErrorMessage(new ApiError(400, code, 'server detail'))).toBe(message)
  })

  it('VALIDATION_FAILED：附上後端的 detail', () => {
    expect(productErrorMessage(new ApiError(400, 'VALIDATION_FAILED', 'price: must be'))).toBe(
      '欄位格式不正確：price: must be',
    )
  })

  it('其他代碼：顯示 detail', () => {
    expect(productErrorMessage(new ApiError(0, 'NETWORK_ERROR', '無法連線'))).toBe('無法連線')
  })
})
