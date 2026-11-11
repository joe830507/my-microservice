import { delay, http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { ApiError, ClientErrorCode, NO_RESPONSE_STATUS } from '../errors'
import apiClient from '../http'

// 用 MSW 攔截真正發出的請求，驗證 http.ts 的攔截器 + errors.ts 的轉換串起來是對的
// 路徑前面用 *，不受 VITE_API_BASE 影響（vitest 的 mode 是 test，不會讀 .env.development）
const server = setupServer()

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

// 等請求失敗並取得錯誤；請求意外成功時讓測試失敗
async function captureError(request: Promise<unknown>): Promise<ApiError> {
  const error = await request.then(
    () => {
      throw new Error('預期請求失敗，但成功了')
    },
    (reason: unknown) => reason,
  )
  expect(error).toBeInstanceOf(ApiError)
  return error as ApiError
}

describe('apiClient', () => {
  it('成功回應：照常回傳資料', async () => {
    server.use(http.get('*/products/1', () => HttpResponse.json({ id: 1, name: '咖啡豆' })))

    const response = await apiClient.get('/products/1')

    expect(response.status).toBe(200)
    expect(response.data).toEqual({ id: 1, name: '咖啡豆' })
  })

  it('4xx ProblemDetail：轉成對應的 status、code、detail', async () => {
    server.use(
      http.post('*/orders/1/ship', () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Conflict',
            status: 409,
            detail: '訂單狀態不能出貨',
            instance: '/api/v1/orders/1/ship',
            code: 'ORDER_NOT_SHIPPABLE',
          },
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )

    const error = await captureError(apiClient.post('/orders/1/ship'))

    expect(error.status).toBe(409)
    expect(error.code).toBe('ORDER_NOT_SHIPPABLE')
    expect(error.detail).toBe('訂單狀態不能出貨')
  })

  it('5xx HTML 錯誤頁：HTTP_ERROR，不顯示 HTML', async () => {
    server.use(
      http.get('*/products', () =>
        HttpResponse.html('<html><body>502 Bad Gateway</body></html>', { status: 502 }),
      ),
    )

    const error = await captureError(apiClient.get('/products'))

    expect(error.status).toBe(502)
    expect(error.code).toBe(ClientErrorCode.HTTP_ERROR)
    expect(error.detail).not.toContain('<html>')
  })

  it('5xx 空 body：HTTP_ERROR', async () => {
    server.use(http.get('*/products', () => new HttpResponse(null, { status: 503 })))

    const error = await captureError(apiClient.get('/products'))

    expect(error.status).toBe(503)
    expect(error.code).toBe(ClientErrorCode.HTTP_ERROR)
  })

  it('網路中斷：NETWORK_ERROR', async () => {
    server.use(http.get('*/products', () => HttpResponse.error()))

    const error = await captureError(apiClient.get('/products'))

    expect(error.status).toBe(NO_RESPONSE_STATUS)
    expect(error.code).toBe(ClientErrorCode.NETWORK_ERROR)
  })

  // 逾時不在這裡測：MSW 模擬的 XMLHttpRequest 不會觸發 timeout，請求會等到 handler 回應才成功
  // 逾時的轉換（ECONNABORTED、ETIMEDOUT → TIMEOUT）由 errors.spec.ts 測試

  it('取消請求：REQUEST_CANCELED', async () => {
    server.use(
      http.get('*/products', async () => {
        await delay('infinite')
      }),
    )
    const controller = new AbortController()

    const request = apiClient.get('/products', { signal: controller.signal })
    controller.abort()
    const error = await captureError(request)

    expect(error.code).toBe(ClientErrorCode.REQUEST_CANCELED)
  })
})
