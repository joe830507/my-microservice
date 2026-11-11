import { AxiosError, AxiosHeaders, CanceledError, type AxiosResponse } from 'axios'
import { describe, expect, it } from 'vitest'
import {
  ApiError,
  ClientErrorCode,
  isProblemDetail,
  NO_RESPONSE_STATUS,
  toApiError,
} from '../errors'

// 組一個帶回應的 AxiosError，模擬伺服器有回應但狀態碼不是 2xx
function responseError(status: number, data: unknown): AxiosError {
  const config = { headers: new AxiosHeaders() }
  const response: AxiosResponse = {
    status,
    statusText: '',
    headers: {},
    config,
    data,
  }
  return new AxiosError('Request failed', AxiosError.ERR_BAD_RESPONSE, config, {}, response)
}

describe('isProblemDetail', () => {
  it('code 是字串才算 ProblemDetail', () => {
    expect(isProblemDetail({ status: 409, code: 'ORDER_NOT_SHIPPABLE' })).toBe(true)
  })

  it.each([
    ['null', null],
    ['字串', '<html>502 Bad Gateway</html>'],
    ['空字串', ''],
    ['沒有 code', { status: 500, detail: 'oops' }],
    ['code 不是字串', { code: 123 }],
  ])('%s 不算 ProblemDetail', (_, data) => {
    expect(isProblemDetail(data)).toBe(false)
  })
})

describe('toApiError', () => {
  it('ProblemDetail 回應：使用後端的 code、detail', () => {
    const error = responseError(409, {
      type: 'about:blank',
      title: 'Conflict',
      status: 409,
      detail: '訂單狀態不能出貨',
      instance: '/api/v1/orders/1/ship',
      code: 'ORDER_NOT_SHIPPABLE',
    })

    const apiError = toApiError(error)

    expect(apiError).toBeInstanceOf(ApiError)
    expect(apiError).toBeInstanceOf(Error)
    expect(apiError.status).toBe(409)
    expect(apiError.code).toBe('ORDER_NOT_SHIPPABLE')
    expect(apiError.detail).toBe('訂單狀態不能出貨')
    expect(apiError.message).toBe('訂單狀態不能出貨')
    expect(apiError.cause).toBe(error)
    expect(apiError.isNetworkError).toBe(false)
  })

  it('status 以 HTTP 回應為準，不採用 body 裡的 status', () => {
    const apiError = toApiError(responseError(400, { status: 999, code: 'VALIDATION_FAILED' }))

    expect(apiError.status).toBe(400)
  })

  it('沒有 detail 時改用 title', () => {
    const apiError = toApiError(responseError(403, { title: 'Forbidden', code: 'FORBIDDEN' }))

    expect(apiError.detail).toBe('Forbidden')
  })

  it('detail 是空字串時改用 title', () => {
    const apiError = toApiError(
      responseError(403, { title: 'Forbidden', detail: '', code: 'FORBIDDEN' }),
    )

    expect(apiError.detail).toBe('Forbidden')
  })

  it('detail、title 都沒有時用預設訊息', () => {
    const apiError = toApiError(responseError(401, { code: 'UNAUTHORIZED' }))

    expect(apiError.code).toBe('UNAUTHORIZED')
    expect(apiError.detail).not.toBe('')
  })

  it.each([
    ['HTML 錯誤頁', 502, '<html><body>502 Bad Gateway</body></html>'],
    ['空 body', 503, ''],
    ['沒有 code 的 JSON', 500, { message: 'Internal Server Error' }],
  ])('非 ProblemDetail 回應（%s）：HTTP_ERROR，不顯示伺服器內容', (_, status, data) => {
    const apiError = toApiError(responseError(status, data))

    expect(apiError.status).toBe(status)
    expect(apiError.code).toBe(ClientErrorCode.HTTP_ERROR)
    expect(apiError.detail).not.toContain('<html>')
    expect(apiError.detail).not.toContain('Internal Server Error')
  })

  it('沒有回應：NETWORK_ERROR，status 為 0', () => {
    const error = new AxiosError('Network Error', AxiosError.ERR_NETWORK)

    const apiError = toApiError(error)

    expect(apiError.status).toBe(NO_RESPONSE_STATUS)
    expect(apiError.code).toBe(ClientErrorCode.NETWORK_ERROR)
    expect(apiError.isNetworkError).toBe(true)
    expect(apiError.cause).toBe(error)
  })

  it.each([AxiosError.ECONNABORTED, AxiosError.ETIMEDOUT])('逾時（%s）：TIMEOUT', (code) => {
    const apiError = toApiError(new AxiosError('timeout', code))

    expect(apiError.status).toBe(NO_RESPONSE_STATUS)
    expect(apiError.code).toBe(ClientErrorCode.TIMEOUT)
  })

  it('請求取消：REQUEST_CANCELED', () => {
    const apiError = toApiError(new CanceledError())

    expect(apiError.status).toBe(NO_RESPONSE_STATUS)
    expect(apiError.code).toBe(ClientErrorCode.REQUEST_CANCELED)
  })

  it('不是 axios 的錯誤：UNKNOWN_ERROR，保留原始錯誤', () => {
    const original = new TypeError('boom')

    const apiError = toApiError(original)

    expect(apiError.status).toBe(NO_RESPONSE_STATUS)
    expect(apiError.code).toBe(ClientErrorCode.UNKNOWN_ERROR)
    expect(apiError.cause).toBe(original)
  })

  it('已經是 ApiError：原樣回傳', () => {
    const original = new ApiError(404, 'PRODUCT_NOT_FOUND', '找不到商品')

    expect(toApiError(original)).toBe(original)
  })
})
