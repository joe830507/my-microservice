# vue-management Step 2：HTTP 層

> 屬於 [vue-management 實作計畫](README.md)

- `http.ts`：`baseURL` 用 `VITE_API_BASE`、timeout
- `errors.ts`：把 ProblemDetail 轉成 `ApiError`；網路錯誤、非 JSON 回應也要能轉換

**測試**（MSW）：4xx ProblemDetail → 正確的 `status`、`code`；5xx、網路中斷 → 一般錯誤。

**完成內容（2026-11-10）**：

- `src/api/http.ts`：axios 實例（`baseURL` 用 `VITE_API_BASE`、timeout 30 秒）；response 攔截器把所有失敗轉成 `ApiError`，呼叫端只需要處理一種錯誤
- `src/api/errors.ts`：`ProblemDetail` 型別、`ApiError`（`status`、`code`、`detail`、`cause`、`isNetworkError`）、`isProblemDetail`、`toApiError`
- 後端各服務（`GlobalExceptionHandler`）與 Gateway（`ProblemDetailWriter`）回的錯誤格式一致，都是 ProblemDetail + `code`，前端只需要一種轉換

`toApiError` 的轉換規則（依判斷順序）：

| 情況                                         | `status`      | `code`                                  | `detail`                                |
| -------------------------------------------- | ------------- | --------------------------------------- | --------------------------------------- |
| 已經是 `ApiError`                          | 不變          | 不變                                    | 不變                                    |
| 不是 axios 錯誤                              | 0             | `UNKNOWN_ERROR`                       | 預設訊息                                |
| 請求取消                                     | 0             | `REQUEST_CANCELED`                    | 預設訊息                                |
| 逾時（`ECONNABORTED`、`ETIMEDOUT`）      | 0             | `TIMEOUT`                             | 預設訊息                                |
| 沒有回應（網路中斷、CORS 被擋）              | 0             | `NETWORK_ERROR`                       | 預設訊息                                |
| ProblemDetail 回應                           | HTTP status   | 後端的 `code`                         | `detail` → `title` → 預設訊息     |
| 其他回應（HTML 錯誤頁、空 body、純文字）     | HTTP status   | `HTTP_ERROR`                          | 預設訊息，不顯示伺服器內容              |

- 判斷是不是 ProblemDetail 只看 `code` 是不是字串，不看 Content-Type：proxy 可能改掉 header，axios 也會自動 parse JSON
- `status` 以 HTTP 回應為準，body 裡的 `status` 不採用
- 前端自訂的錯誤代碼集中在 `ClientErrorCode`（`as const` 物件，不用 `enum`）；`DEFAULT_DETAIL` 宣告成 `Record<ClientErrorCode, string>`，新增代碼卻忘了寫訊息會編譯失敗
- `errors.ts` 是純函式，不 import `http.ts`，可以不靠 MSW 單獨測試

**測試**：

| 測試檔                                  | 方式                           | 案例                                                                                                   |
| --------------------------------------- | ------------------------------ | ------------------------------------------------------------------------------------------------------ |
| `src/api/__tests__/errors.spec.ts`    | 直接把 `AxiosError` 傳給 `toApiError` | 20 個：`isProblemDetail` 判斷、ProblemDetail 各欄位的備用規則、非 ProblemDetail 回應、網路中斷、逾時、取消、非 axios 錯誤、已是 `ApiError` |
| `src/api/__tests__/http.spec.ts`      | MSW 攔截真正發出的請求          | 6 個：成功回應、409 ProblemDetail、502 HTML、503 空 body、網路中斷、取消                                |

**遇到的問題**：

- **專案的 TS `lib` 是 ES2020**：沒有 `ErrorOptions`，`Error` 也沒有 `cause`，`super(message, { cause })` 型別檢查會失敗。改成 `ApiError` 自己宣告 `readonly cause?: unknown`
- **MSW 測不了逾時**：MSW 模擬的 `XMLHttpRequest` 會忽略 axios 的 `timeout`，請求一直等到 handler 回應才成功。整合測試不測逾時，逾時的轉換由 `errors.spec.ts` 負責
- **vitest 不會讀 `.env.development`**：vitest 的 mode 是 `test`，`VITE_API_BASE` 是 undefined。MSW handler 的路徑前面加 `*`（例如 `*/products`），不受 `baseURL` 影響
- **oxlint 的 `no-conditional-expect`**：不能在 `catch` 裡寫 `expect`。測試的 `captureError` 改用 `.then(成功時 throw, 失敗時回傳 reason)`，在外面才 `expect`
- `.env.development` 的 `VITE_API_BASE` 要維持 `/api/v1`：改成 `http://localhost:8080/api` 會少了 `/v1`，而且繞過 Vite proxy，變成跨來源請求

**最終檢查**：vitest 27 個通過（含 Step 1 的冒煙測試）、`npm run build`（含 `vue-tsc`）通過、eslint、oxlint、prettier 都沒有問題。commit `bd14061`。
