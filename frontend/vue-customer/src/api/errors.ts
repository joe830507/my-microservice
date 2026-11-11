import axios from 'axios'

// 後端（各服務的 GlobalExceptionHandler、Gateway 的 ProblemDetailWriter）統一回 RFC 9457 ProblemDetail + code
// 欄位都當成 optional：回應內容不一定可信，用之前要先檢查
export interface ProblemDetail {
  type?: string
  title?: string
  status?: number
  detail?: string
  instance?: string
  code?: string
}

// 前端自己產生的錯誤代碼；後端的代碼（ORDER_NOT_SHIPPABLE 等）直接沿用字串
export const ClientErrorCode = {
  NETWORK_ERROR: 'NETWORK_ERROR',
  TIMEOUT: 'TIMEOUT',
  REQUEST_CANCELED: 'REQUEST_CANCELED',
  HTTP_ERROR: 'HTTP_ERROR',
  UNKNOWN_ERROR: 'UNKNOWN_ERROR',
} as const

export type ClientErrorCode = (typeof ClientErrorCode)[keyof typeof ClientErrorCode]

const DEFAULT_DETAIL: Record<ClientErrorCode, string> = {
  NETWORK_ERROR: '無法連線到伺服器，請檢查網路後再試一次',
  TIMEOUT: '伺服器回應逾時，請稍後再試',
  REQUEST_CANCELED: '請求已取消',
  HTTP_ERROR: '伺服器發生錯誤，請稍後再試',
  UNKNOWN_ERROR: '發生未預期的錯誤',
}

// 沒有 HTTP 回應時（網路中斷、逾時、取消）status 用 0
export const NO_RESPONSE_STATUS = 0

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly detail: string
  // 原始錯誤（通常是 AxiosError），除錯用；專案的 lib 是 ES2020，Error 本身沒有 cause，所以自己宣告
  readonly cause?: unknown

  constructor(status: number, code: string, detail: string, cause?: unknown) {
    super(detail)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.detail = detail
    this.cause = cause
  }

  get isNetworkError(): boolean {
    return this.status === NO_RESPONSE_STATUS
  }
}

// 只要求 code 是字串；不看 Content-Type，因為 proxy 可能改掉 header，axios 也會自動 parse JSON
export function isProblemDetail(data: unknown): data is ProblemDetail & { code: string } {
  return (
    typeof data === 'object' &&
    data !== null &&
    typeof (data as Record<string, unknown>).code === 'string'
  )
}

// 把 axios 丟出的任何錯誤轉成 ApiError；純函式，不依賴 http.ts，方便單獨測試
export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) {
    return error
  }

  if (!axios.isAxiosError(error)) {
    return clientError(ClientErrorCode.UNKNOWN_ERROR, error)
  }

  if (axios.isCancel(error) || error.code === 'ERR_CANCELED') {
    return clientError(ClientErrorCode.REQUEST_CANCELED, error)
  }

  if (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT') {
    return clientError(ClientErrorCode.TIMEOUT, error)
  }

  const response = error.response
  if (!response) {
    // 網路中斷、DNS 失敗、CORS 被擋：瀏覽器都不會給回應
    return clientError(ClientErrorCode.NETWORK_ERROR, error)
  }

  const data: unknown = response.data
  if (isProblemDetail(data)) {
    // status 以實際的 HTTP status 為準，body 裡的只是參考
    return new ApiError(
      response.status,
      data.code,
      data.detail || data.title || DEFAULT_DETAIL.HTTP_ERROR,
      error,
    )
  }

  // 有回應但不是 ProblemDetail（nginx 的 HTML 錯誤頁、空 body、proxy 的純文字）：
  // 不把伺服器內容顯示給使用者，一律用預設訊息
  return new ApiError(response.status, ClientErrorCode.HTTP_ERROR, DEFAULT_DETAIL.HTTP_ERROR, error)
}

function clientError(code: ClientErrorCode, cause: unknown): ApiError {
  return new ApiError(NO_RESPONSE_STATUS, code, DEFAULT_DETAIL[code], cause)
}
