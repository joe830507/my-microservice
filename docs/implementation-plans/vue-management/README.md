# vue-management 後台前端實作與驗收紀錄

> 2026-11-13 文件整理：暫緩／後續項目的現況、前提與影響範圍統一見[集中清單](../deferred-items.md)；本處原始說明與歷史紀錄保留。

目前程式分工與依賴圖見[元件關係與職責](../../component-relationships/vue-management.md)；本文件保留實作步驟、設計決策與當時驗收紀錄。

> 建立日期：2026-11-09
> 分支：`feature/vue-management`
> 位置：`frontend/vue-management`
> 狀態：完成（Step 0–12）

## 進度

| Step | 內容                                                       | 狀態                                               |
| ---- | ---------------------------------------------------------- | -------------------------------------------------- |
| [0](#step-00) | 後端前置問題：SeaweedFS CORS、決定要補哪些後台 API         | 完成：CORS 不用改；補上訂單 `ship`、`complete` |
| [1](#step-01) | 建立專案、Element Plus、Vite proxy                         | 完成                                               |
| [2](#step-02) | HTTP 層：axios 實例、`ApiError`                          | 完成                                               |
| [3](#step-03) | 登入：token store、refresh（同時只發一次）、登出、路由守衛 | 完成                                               |
| [4](#step-04) | AdminLayout、選單、403 / 404                               | 完成                                               |
| [5](#step-05) | 分類管理                                                   | 完成：後端補上含停用分類的列表 API                 |
| [6](#step-06) | 商品管理                                                   | 完成                                               |
| [7](#step-07) | 商品圖片                                                   | 完成                                               |
| [8](#step-08) | 庫存（放在商品編輯頁）                                     | 完成                                               |
| [9](#step-09) | 訂單管理                                                   | 完成                                               |
| [10](#step-10) | 使用者列表                                                 | 完成                                               |
| [11](#step-11) | 部署：Dockerfile、nginx、K8s                               | 完成：kind 上驗證；CSP 已在 Step 12 於瀏覽器驗證            |
| [12](#step-12) | Playwright E2E                                             | 完成：登入、建立商品、上傳主圖、上架；含對著 nginx（CSP）的驗證 |

每一步都附測試，測試通過後單獨 commit。

**接上後端的實測**：Step 10 完成後，在本機啟動整套後端，照前端的呼叫順序打真正的 API（58 項全部符合預期），見 [後端 API 實測](#backend-integration)。這次 API 實測不包含畫面操作；後續瀏覽器自動化驗證見 [Step 12](#step-12)。

本文件集中整體設計、各步驟實作與驗收；上方進度表可直接跳到對應章節。

---

## 後端前置問題

| 問題           | 現況                                                                | 決定                                                                                                                    |
| -------------- | ------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| CORS           | Gateway 沒有設定 CORS                                               | 開發時用 **Vite proxy** 把 `/api` 轉到 `localhost:8080`；正式環境由 nginx 轉發，維持同一個網域，後端不設 CORS |
| 圖片直傳       | presigned URL 讓瀏覽器直接 PUT 到 SeaweedFS `:8333`，是跨網域請求 | 已實測：SeaweedFS 預設允許所有來源，不用改設定（見 [Step 0](#step-00)）。簽章包含 Host，不能靠 proxy 改 Host 繞過                   |
| 庫存列表       | 只有 `GET /api/v1/inventory/{sku}`                                | 雛型階段在商品編輯頁依 SKU 查詢；需要總覽頁時再補列表 API                                                               |
| 訂單出貨、完成 | 沒有 API，`SHIPPED`、`COMPLETED` 無法到達                       | 已補：`POST /orders/{id}/ship`、`POST /orders/{id}/complete`（見 [Step 0](#step-00)）                                           |
| 停用的分類     | `GET /categories/tree` 只有啟用中的分類（給前台用）                | 已補：`GET /categories`（ADMIN），平面列表含停用的分類（見 [Step 5](#step-05)）                            |
| 付款列表       | 沒有                                                                | 雛型階段不做                                                                                                            |
| 使用者角色     | 無法修改                                                            | 雛型階段只做列表                                                                                                        |

## 技術選型

| 項目       | 選擇                                            | 理由                                                   |
| ---------- | ----------------------------------------------- | ------------------------------------------------------ |
| 框架       | Vue 3 + Vite + TypeScript（`<script setup>`） | 主流做法，型別可以對照後端 DTO                         |
| 路由與狀態 | Vue Router、Pinia                               | 官方方案                                               |
| UI 元件    | Element Plus                                    | 表格、表單、樹狀、上傳元件齊全                         |
| HTTP       | axios                                           | 用攔截器處理 token 與 refresh 最直接                   |
| 測試       | Vitest + Vue Test Utils + MSW                   | 不用啟動後端就能測錯誤與 401 流程                      |
| E2E        | Playwright（Step 12）                           | 搭配 compose 跑一次完整流程                            |
| API 型別   | 先手寫在各功能的 `types.ts`                   | 之後可以考慮用 springdoc + `openapi-typescript` 產生 |

## 目錄結構

依功能分資料夾，每個功能自己包含 api、types、views、components。

```
frontend/vue-management/
├── .env.development        VITE_API_BASE=/api/v1
├── vite.config.ts          proxy: /api → http://localhost:8080
├── Dockerfile / nginx.conf 正式環境：靜態檔 + /api 轉發到 api-gateway
└── src/
    ├── main.ts
    ├── router/             路由表、登入守衛
    ├── api/
    │   ├── http.ts         axios 實例、攔截器（帶 token、401 → refresh）
    │   └── errors.ts       ProblemDetail → ApiError { status, code, detail }
    ├── features/
    │   ├── auth/           登入頁、token store、refresh、axios 攔截器
    │   ├── categories/
    │   ├── products/       含 images/、庫存區塊
    │   ├── orders/
    │   └── users/
    ├── layouts/            AdminLayout（側邊欄、頂部列、麵包屑）
    ├── components/         跨功能共用：分頁表格、確認按鈕、狀態 Tag
    ├── composables/        useRouteQuery（列表條件同步到 URL query）、useConfirm
    ├── utils/              跨功能的純函式：價格、時間格式化
    └── views/              不屬於任何功能的頁面：首頁、403、404
```

## 關鍵設計

- **錯誤處理集中在一處**：後端統一回 ProblemDetail 加上 `code`（見 [backend-common-implementation-plan.md](../backend/backend-common-implementation-plan.md)）。`errors.ts` 轉成 `ApiError`，畫面依 `code` 顯示對應訊息，例如 `PRODUCT_ARCHIVED`、`IMAGE_TOO_LARGE`
- **refresh 同時只發一次**：後端的 refresh token 每次都會換新，舊的立即失效。多個請求同時收到 401 時要共用同一個 refresh Promise，否則後面的請求會拿已經失效的 token 去換。refresh 本身失敗就清除登入狀態，導回登入頁
- **token 存放**：access token 只放記憶體（Pinia）；refresh token 雛型階段放 localStorage，有 XSS 風險，之後考慮改成 httpOnly cookie（需要後端配合）（**2026-11-11 SEC-06 後已改為 HttpOnly cookie**）
- **角色檢查只影響畫面**：登入後用 `GET /api/v1/users/me` 確認是 `ADMIN`，不是就登出並提示。真正的權限檢查仍由 Gateway 與 `@RequireRoles` 負責
- **列表條件放在 URL query**：重新整理、分享連結都能保留篩選與分頁

## 會用到的 API

詳細規格見 [microservice-architecture.md](../../architecture/microservice-architecture.md)。

| 功能     | API                                                                                                                                                                                                          |
| -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 登入     | `POST /auth/login`、`POST /auth/refresh`、`POST /auth/logout`、`GET /users/me`                                                                                                                       |
| 分類     | `GET /categories`（後台列表，含停用）、`GET /categories/tree`、`GET /categories/{id}`、`POST /categories`、`PUT /categories/{id}`、`DELETE /categories/{id}`                                                                              |
| 商品     | `GET /products`、`GET /products/{id}`、`POST /products`、`PUT /products/{id}`、`POST /products/{id}/publish`、`/unpublish`、`/archive`                                                         |
| 商品圖片 | `POST /products/{id}/images/upload-url`、`POST /products/{id}/images`、`GET /products/{id}/images`、`PUT .../{imageId}`、`DELETE .../{imageId}`、`POST .../{imageId}/primary`、`PUT .../order` |
| 庫存     | `GET /inventory/{sku}`、`PUT /inventory/{sku}`                                                                                                                                                           |
| 訂單     | `GET /orders`（ADMIN 可查全部）、`GET /orders/{id}`、`POST /orders/{id}/cancel`、`/ship`、`/complete`                                                                                              |
| 使用者   | `GET /users`                                                                                                                                                                                               |

> 以下 Step 保留實作當時的決策、問題與驗證數字；「待辦／尚未驗證」須連同後續步驟閱讀，不能視為今日重新執行的結果。

---

<a id="step-00"></a>

## vue-management Step 0：後端前置問題

- 實測 SeaweedFS 的 CORS：從 `http://localhost:5173` 對 presigned URL 發 PUT，確認 preflight 通過；不通過就在 compose 的初始化加入 CORS 設定
- 決定訂單出貨、完成 API 要不要補；要補就照後端流程（整合測試、單獨 commit）

**CORS 實測結果（2026-11-09）**：不用改設定。

- SeaweedFS 3.80 預設就允許所有來源：OPTIONS 預檢回 200，帶 `Access-Control-Allow-Origin: *`、`Allow-Methods: *`、`Allow-Headers: *`
- 用簽了 `content-type`、`content-length` 的 presigned URL、帶 `Origin: http://localhost:5173` 實際 PUT：回 200，回應也帶 `Access-Control-Allow-Origin: *`；換成別的 Content-Type 仍然回 403，簽章檢查沒有因為 CORS 放寬
- 前端上傳**不能帶 credentials**（axios 的 `withCredentials` 保持 false）：回應是 `*` 加上 `Allow-Credentials: true`，瀏覽器在帶 credentials 時會拒絕 `*`
- 正式環境的物件儲存（S3 或 SeaweedFS）如果改成只允許後台網域，要再設定 bucket CORS
- 順便遇到的本機問題：Windows 的 TCP 動態 port 範圍從 1024 開始時，Hyper-V / WSL 會保留 8045–8344 一帶的 port，造成 8080–8085、8333 綁定失敗。修正方式：系統管理員執行 `netsh int ipv4 set dynamic tcp start=49152 num=16384`，再重啟 `winnat`
- compose 的 `object-storage-init` 可能在 filer 準備好之前就執行，出現 `fail to dial` 錯誤但仍然以 0 結束；重新執行 `docker compose up object-storage-init` 即可

**訂單出貨、完成 API（2026-11-09）**：

| API                                   | 轉換                                           | 重複呼叫                            | 其他狀態                      |
| ------------------------------------- | ---------------------------------------------- | ----------------------------------- | ----------------------------- |
| `POST /api/v1/orders/{id}/ship`     | `PAID → SHIPPED`，記錄 `shippedAt`        | 已是 `SHIPPED` 回 200，不再改動   | 409 `ORDER_NOT_SHIPPABLE`   |
| `POST /api/v1/orders/{id}/complete` | `SHIPPED → COMPLETED`，記錄 `completedAt` | 已是 `COMPLETED` 回 200，不再改動 | 409 `ORDER_NOT_COMPLETABLE` |

- 限 ADMIN（`@RequireRoles`）：order-service 第一次用到，啟動類別加上 `@Import(RequireRolesGuard.class)`
- 用條件式 UPDATE，同時多個請求只有一個真的改動；不需要呼叫 inventory（付款時已確認扣除）
- V2、V3 migration 新增 `shipped_at`、`completed_at`；`OrderResDto` 多了這兩個欄位
- 測試：`OrderControllerShipIntegrationTests`（9）、`OrderControllerCompleteIntegrationTests`（10），order-service 全部 114 個通過

---

<a id="step-01"></a>

## vue-management Step 1：建立專案

- `npm create vue@latest`：TypeScript、Router、Pinia、Vitest、ESLint、Prettier
- 加入 Element Plus（按需引入）
- `vite.config.ts` 設定 proxy：`/api` → `http://localhost:8080`

**測試**：`npm run build`、`npm run test:unit` 通過；冒煙測試能掛載 App。

**檢查結果（2026-11-09）**：

| 檢查                                | 結果                                                                                                                       |
| ----------------------------------- | -------------------------------------------------------------------------------------------------------------------------- |
| `npm run build`（含 `vue-tsc`） | 通過                                                                                                                       |
| eslint、oxlint                      | 沒有問題                                                                                                                   |
| `vitest`                          | 1 個通過（create-vue 的範例測試）                                                                                          |
| dev server + proxy                  | 首頁 200；`/api/v1/products` 有轉送到 `localhost:8080`（Gateway 沒啟動時回 502 `ECONNREFUSED`，代表 proxy 設定正確） |

- 一開始 IDE 在 `vite.config.ts`、`tsconfig.node.json` 報找不到 `vite`、`@types/node`：只是還沒執行 `npm install`
- `npm audit` 有 16 個 moderate，全部在開發工具（eslint 相關、`editorconfig` 依賴的 `minimatch`），不會打包進前端；**不要用 `npm audit fix --force`**，會跨主要版本升降級，可能弄壞 eslint 設定，等上游出新版再 `npm update`

**待辦**：

- [X] 加入 Element Plus：安裝 `element-plus`；建議按需引入（`unplugin-vue-components`、`unplugin-auto-import` + `ElementPlusResolver`），只打包用到的元件
- [X] 整理 `vite.config.ts`
  - [X] 用 Prettier 格式化：`npx prettier --write vite.config.ts`（`npm run format` 只處理 `src/`）
  - [X] 刪除 `secure: false`：只對 https 目標有作用
  - [X] 刪除註解掉的 `rewrite`：Gateway 的路徑本來就是 `/api/v1/...`，前綴不能去掉
  - [X] 範例的英文註解改成專案說明（開發時轉給 api-gateway；正式環境由 nginx 轉發）
- [X] 清掉 create-vue 的範例內容
  - [X] 刪除 `src/stores/counter.ts`
  - [X] `App.vue` 只放 `<RouterView />`，router 加一個暫時的首頁路由
  - [X] `App.spec.ts` 改成冒煙測試：掛載 App + router，確認首頁顯示；順便確認 Element Plus 元件能渲染
- [X] 刪除 `frontend/vue-management/.gitkeep`
- [X] `.env.development`：`VITE_API_BASE=/api/v1`（也可以延到 Step 2 寫 `http.ts` 時再加）
- [X] 補完後重新檢查，整個前端專案（含 `package-lock.json`）commit 並 push

**收尾時遇到的問題**：

- **Vitest 載入 Element Plus 的 CSS 失敗**（`Unknown file extension ".css"`）：按需引入會 import `element-plus/theme-chalk/*.css`，而 Vitest 預設讓 Node 直接載入 `node_modules`。在 `vitest.config.ts` 設 `server.deps.inline: ['element-plus']` 改由 Vite 處理
- 自動產生的 `src/auto-imports.d.ts`、`src/components.d.ts` 放在 `src/` 才會被 `tsconfig.app.json` 涵蓋；加進 `.prettierignore` 與 eslint 的 `globalIgnores`，不做格式檢查
- `App.vue` 的 `<RouterView />` 前面混進一個零寬空白（U+200B），畫面上會多一個文字節點；編輯器看不出來，用 `grep $'\xe2\x80\x8b'` 才找到
- `env.d.ts` 宣告 `ImportMetaEnv.VITE_API_BASE`，Step 2 的 `http.ts` 讀取時有型別
- 冒煙測試用 `createMemoryHistory` 建立自己的 router（`router/index.ts` 另外匯出 `routes`），並確認 `<el-button>` 被解析成 Element Plus 元件

**最終檢查**：vitest 1 個通過、`npm run build`（含 `vue-tsc`）通過、eslint、oxlint、`prettier --check .` 都沒有問題；dev server 首頁 200，`/api` 有轉送到 8080。

---

<a id="step-02"></a>

## vue-management Step 2：HTTP 層

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

---

<a id="step-03"></a>

## vue-management Step 3：登入

- 登入頁、`useAuthStore`（token、目前使用者）
- request 攔截器帶 `Authorization: Bearer`；response 攔截器遇到 401 → refresh → 重送原請求
- 登出：呼叫 `/auth/logout` 後清除狀態
- 路由守衛：未登入 → 登入頁（帶 redirect）；非 ADMIN → 拒絕登入

**測試**：同時兩個請求收到 401 只發一次 refresh，兩個都成功重送；refresh 失敗導回登入頁；非 ADMIN 登入被拒；登入限流（429）顯示提示。

<a id="step-03-完成內容2026-11-10"></a>

### 完成內容（2026-11-10）

| 檔案 | 內容 |
| ---- | ---- |
| `src/features/auth/types.ts` | 對應 user-service 的 `LoginReqDto`、`TokenResDto`、`GetMeResDto` |
| `src/features/auth/api.ts` | `login`、`refresh`、`logout`、`getMe`；前三個帶 `skipAuth` |
| `src/features/auth/store.ts` | `useAuthStore`：`login`、`logout`、`refreshAccessToken`、`restoreSession` |
| `src/features/auth/interceptors.ts` | `installAuthInterceptors`：帶 token；401 → refresh → 重送一次 |
| `src/features/auth/messages.ts` | 依錯誤代碼顯示登入失敗訊息；`safeRedirect` 只接受站內路徑 |
| `src/features/auth/LoginView.vue` | 登入頁（Element Plus 表單、欄位驗證、錯誤提示） |
| `src/router/index.ts` | `/login` 路由（`meta.public`）、`installAuthGuard` |
| `src/api/http.ts` | 新增請求設定 `skipAuth`、`authRetried`（module augmentation） |
| `src/main.ts` | 註冊順序：pinia → auth 攔截器 → router |
| `src/views/HomeView.vue` | 暫時顯示使用者名稱與登出按鈕；Step 4 移到頂部列 |

後端的對應（實作前確認過程式碼）：

| 情況 | 來源 | 回應 |
| ---- | ---- | ---- |
| access token 缺少、無效或過期 | Gateway `ProblemDetailAuthenticationEntryPoint` | 401 `UNAUTHENTICATED` |
| 帳密錯誤 | user-service | 401 `INVALID_CREDENTIALS` |
| 帳號停用 | user-service | 403 `USER_DISABLED` |
| 同一個 email 失敗太多次 | user-service | 429 `TOO_MANY_LOGIN_ATTEMPTS` |
| 同一個 IP 登入太頻繁 | Gateway `ClientIpRateLimit` | 429 `TOO_MANY_REQUESTS` |
| refresh token 失效或被重複使用 | user-service | 401 `REFRESH_TOKEN_INVALID` |

<a id="step-03-設計"></a>

#### 設計

- **token 存放**：access token 只放 Pinia（記憶體）；refresh token 另外寫進 localStorage（key `vue-management.refreshToken`），讀寫都包 try/catch，無痕模式讀寫失敗時只是重新整理後要重新登入（**2026-11-11 SEC-06 後已改為 HttpOnly cookie，見 [known-issues](../../security/known-issues.md)**）
- **登入流程**：`/auth/login` → 保存 token → `/users/me` → 不是 `ADMIN` 就呼叫 `/auth/logout` 撤銷剛拿到的 refresh token，丟出 `NOT_ADMIN`；`/users/me` 失敗也一樣登出，不留下半登入的狀態
- **refresh 同時只發一次**：store 保留進行中的 refresh Promise，同時收到 401 的請求共用它
- **請求送出後 token 已被換新**：攔截器比對這個請求帶的 token 和 store 目前的 token，不同就直接用新 token 重送，不再 refresh（refresh token 每次都會輪替，多 refresh 一次只是浪費）
- **只重送一次**：重送的請求帶 `authRetried`，再收到 401 就直接失敗，避免無限循環
- **skipAuth**：登入、refresh、登出本身不帶 token，收到 401 也不觸發 refresh（帳密錯誤的 401 不能被當成 token 過期）
- **refresh 失敗的處理**：
  - 後端回 4xx（`REFRESH_TOKEN_INVALID`）：清除登入狀態，呼叫 `onSessionExpired` 導回登入頁並帶 redirect
  - 網路中斷、5xx：保留 refresh token，原請求失敗，不導回登入頁；refresh token 可能還有效，之後的請求可以再試
- **重新整理頁面**：access token 不見了，路由守衛呼叫 `restoreSession()`，用 refresh token 換新後再取 `/users/me`；同時被觸發多次也只恢復一次
- **登出**：先清除本機狀態再呼叫 `/auth/logout`，伺服器端撤銷失敗不影響本機登出
- **redirect 只接受站內路徑**：`safeRedirect` 拒絕外部網址、`//`、`/\`、相對路徑與 `/login` 本身，避免開放重新導向
- **攔截器的順序**：`http.ts` 的錯誤轉換最先註冊，auth 的 response 攔截器之後註冊，收到的已經是 `ApiError`；原本的請求設定從 `ApiError.cause`（AxiosError）的 `config` 取得
- **避免循環 import**：`http.ts` 不 import auth；攔截器由 `main.ts` 呼叫 `installAuthInterceptors` 掛上，回傳移除函式，測試時每個案例各自掛上、移除
- 目錄結構：計畫原本寫 `src/app/`，實際沿用 create-vue 產生的 `src/router/`，README 的目錄結構已更新

<a id="step-03-測試"></a>

#### 測試

| 測試檔 | 案例 |
| ------ | ---- |
| `features/auth/__tests__/interceptors.spec.ts` | 9 個：帶 Bearer token、`skipAuth` 不帶、同時兩個 401 只 refresh 一次且都重送成功、token 已被換新時不 refresh、refresh 被拒絕 → 清除並導回登入頁、沒有 refresh token → 導回登入頁、refresh 網路中斷 → 保留登入狀態、重送後仍 401 不再重試、403 不觸發 refresh |
| `features/auth/__tests__/store.spec.ts` | 10 個：ADMIN 登入成功、非 ADMIN 被拒並撤銷 refresh token、帳密錯誤不觸發 refresh、登入限流 429、恢復登入狀態（同時兩次只 refresh 一次）、沒有 refresh token、refresh token 失效、恢復後不是 ADMIN、登出、`/auth/logout` 失敗仍清除 |
| `features/auth/__tests__/messages.spec.ts` | 16 個：各錯誤代碼的訊息、`safeRedirect` 接受與拒絕的路徑 |
| `features/auth/__tests__/LoginView.spec.ts` | 6 個：成功後回到 redirect、外部 redirect 回首頁、429 提示、非 ADMIN 提示、帳密錯誤提示、欄位沒填不發請求 |
| `router/__tests__/guard.spec.ts` | 5 個：未登入導到登入頁並帶 redirect、登入頁不需登入、有 refresh token 時恢復登入、refresh token 失效導到登入頁、已登入進登入頁回首頁 |

- MSW 都設 `onUnhandledRequest: 'error'`：「不應該發出 refresh」之類的案例，不註冊 handler 就能驗證沒有多發請求
- 共用的假資料在 `features/auth/__tests__/fixtures.ts`（`tokens(n)`、`me(roles)`、`problem(status, code)`）
- `App.spec.ts` 的冒煙測試加上 pinia：首頁會用到 auth store

<a id="step-03-遇到的問題"></a>

#### 遇到的問題

- **`problem()` 的回傳型別**：一開始標成 `HttpResponse<never>` 再轉型，`vue-tsc` 報型別不相容（vitest 不做型別檢查所以測試照樣通過）。拿掉標註，讓 TypeScript 推斷即可
- **還沒接真正的後端測試**：寫完時 Gateway 沒有啟動，登入流程只用 MSW 驗證過；之後啟動 compose 時要用 `npm run dev` 實際登入一次（含重新整理頁面後恢復登入、登出）（2026-11-10 已接上後端，用 API 實測過，見 [後端 API 實測](#backend-integration)；畫面還沒在瀏覽器操作過）

<a id="step-03-最終檢查"></a>

### 最終檢查

vitest 73 個通過（新增 46 個）、`npm run build`（含 `vue-tsc`）通過、eslint、oxlint、prettier 都沒有問題。

<a id="step-03-登入頁改版2026-11-10"></a>

### 登入頁改版（2026-11-10）

參考 `docs/pictures/adminstration login page.webp` 的版面重新設計 `LoginView.vue`：

- 深色背景、大圓角卡片，左上角兩片漸層葉片（自己畫的 inline SVG），中間是品牌名稱與說明、「歡迎回來」
- 輸入框改成只有底線（`:deep()` 覆寫 `el-input__wrapper`），聚焦時底線變成強調色；Email 格式看起來正確時右邊顯示勾號；密碼沿用 `show-password` 的眼睛圖示
- 全寬的青色登入按鈕
- 登入頁固定深色，不跟隨系統主題；顏色集中在 `.login-page` 的 CSS 變數
- 窄螢幕（600px 以下）縮小卡片圓角、葉片與內距
- **沒有照搬的部分**：參考圖的品牌名稱換成自己的系統名稱（後來定為「商城管理後台」，見下方）；「忘記密碼」「申請帳號」「需要協助」「使用條款、隱私權政策」沒有做，後端沒有對應的功能，放了也只是無效的連結
- 登入邏輯、欄位驗證、`name` 屬性都沒變，原本的測試全部通過；用 headless Chrome 截圖確認桌面與窄螢幕的版面

---

<a id="step-04"></a>

## vue-management Step 4：Layout

- AdminLayout：側邊選單、頂部列（使用者名稱、登出）、麵包屑
- 403、404 頁面

**測試**：未登入進入後台頁被導到登入頁；不存在的路徑顯示 404。

<a id="step-04-完成內容2026-11-10"></a>

### 完成內容（2026-11-10）

| 檔案 | 內容 |
| ---- | ---- |
| `src/layouts/AdminLayout.vue` | 側邊選單（品牌名稱 + `el-menu`）、頂部列（麵包屑、使用者名稱、登出）、`<RouterView />` |
| `src/router/index.ts` | 路由改成巢狀：`/` 是 AdminLayout，後台頁面放在 `adminChildren`；新增 `/403`、404（catch-all）、`menuItems`、`installTitleUpdater` |
| `src/views/HomeView.vue` | 改成後台首頁（歡迎訊息）；登出按鈕移到頂部列 |
| `src/views/NotFoundView.vue`、`ForbiddenView.vue` | `el-result` + 「回首頁」按鈕 |
| `src/App.vue` | 全站基本樣式：拿掉 body 外距、字型、背景用 Element Plus 色彩變數 |

<a id="step-04-設計"></a>

#### 設計

- **選單由路由產生**：`adminChildren` 裡設了 `meta.menu: true` 的路由會出現在側邊選單，標題用 `meta.title`。之後的功能（分類、商品、訂單…）只要在 `adminChildren` 加路由，不用另外改選單
- **選單選中項目**：目前路徑等於選單路徑，或在它底下（`/products/1` 對應 `/products`）；`/products-x` 這種只是前綴相同的路徑不算
- **麵包屑**：第一層固定是「首頁」，接著是 `route.matched` 裡有 `meta.title` 的層級；最後一層（目前頁面）不放連結
- **瀏覽器分頁標題**：`installTitleUpdater` 在每次導覽後設成「頁面標題 - 商城管理後台」（原本是 vue-management，見下方「系統名稱」）
- **403、404 不需要登入**（`meta.public`），也不在 AdminLayout 裡：
  - 未登入時打錯網址直接顯示 404，不先導到登入頁
  - 403 頁面目前沒有地方會導過去：前端的角色檢查只在登入時做（非 ADMIN 不能登入）。之後如果後端 API 回 403，可以導到這裡
- **AdminLayout 用動態 import**：AdminLayout 會 import `router/index.ts` 的 `menuItems`、`APP_TITLE`；router 如果靜態 import AdminLayout 會形成循環，先載入 AdminLayout 的那一方（例如測試）會在模組初始化時讀到還沒定義的值

<a id="step-04-測試"></a>

#### 測試

| 測試檔 | 案例 |
| ------ | ---- |
| `layouts/__tests__/AdminLayout.spec.ts` | 4 個：頂部列顯示使用者名稱、選單列出 `menuItems` 且目前頁面被選中、麵包屑、登出（呼叫 `/auth/logout`、清除狀態、回登入頁） |
| `router/__tests__/guard.spec.ts` | 新增 3 個：不存在的路徑未登入也顯示 404、403 不需要登入、瀏覽器分頁標題 |
| `views/__tests__/StatusViews.spec.ts` | 2 個：404、403 顯示對應文字、不在 AdminLayout 裡、按「回首頁」回到首頁 |
| `__tests__/App.spec.ts` | 改成確認首頁顯示在 AdminLayout 裡 |

「未登入進入後台頁被導到登入頁」在 Step 3 的 `guard.spec.ts` 已經有測試。

<a id="step-04-遇到的問題"></a>

#### 遇到的問題

- **`Array.prototype.at` 型別檢查失敗**：專案的 TS `lib` 是 ES2020（Step 2 的 `Error.cause` 也是同一個原因），vitest 不做型別檢查所以測試照樣通過，`vue-tsc` 才報錯。改用 `items[items.length - 1]`
- **還沒在瀏覽器實際看過畫面**：後端沒有啟動，登入後才看得到 AdminLayout；版面只靠測試確認結構。之後啟動 compose 時要用 `npm run dev` 檢查版面（含窄螢幕）（2026-11-10 已接上後端，用 API 實測過，見 [後端 API 實測](#backend-integration)；畫面還沒在瀏覽器操作過）

<a id="step-04-最終檢查"></a>

### 最終檢查

vitest 82 個通過（新增 9 個）、`npm run build`（含 `vue-tsc`）通過、eslint、oxlint、prettier 都沒有問題。

<a id="step-04-管理頁面改版2026-11-10"></a>

### 管理頁面改版（2026-11-10）

參考 `docs/pictures/dashboard_01.png`（只留在本機，不放進 repo）重新設計後台。使用者選擇：固定深色主題；首頁做 KPI 卡片與待辦清單，不加統計圖表（後端沒有統計 API）。

**深色主題**

- `index.html` 的 `<html>` 加上 `class="dark"`；`main.ts` 載入 Element Plus 的 `theme-chalk/dark/css-vars.css`（元件樣式是按需引入，深色變數要另外載入），再載入 `src/styles/theme.css` 覆寫配色
- `theme.css`：頁面、卡片、浮層的底色（深藍）、文字與邊框顏色；主色改成紫色 `#9b5cf6`，`light-N`、`dark-2` 依 Element Plus 深色模式的算法（往卡片底色、往白色混色）算好寫死；另外定義洋紅、青色與四組漸層給選單與首頁用
- `el-card` 沒有邊框、16px 圓角；表格表頭和卡片同色
- 登入頁本來就是自己的深色配色，不受影響

**AdminLayout**

- 側邊欄依 `meta.section` 分組（總覽：首頁；商品：分類管理、商品管理；營運：訂單管理、使用者），分組標題是洋紅色；`menuItems` 多了 `section`，分組順序在 `MENU_SECTIONS`
- 選中的項目是紫色漸層底加左邊一條強調色
- 頂部列：麵包屑、使用者名稱、頭像（顯示名稱的第一個字）、圓角的登出按鈕；窄螢幕隱藏名稱、縮小側邊欄

**首頁（`views/HomeView.vue`）**

- 數字都來自列表 API 的 `totalElements`（每個只查 1 筆）：
  - 漸層卡片：待出貨訂單（`PAID`）、待付款訂單（`PENDING_PAYMENT`）、上架中商品（`ACTIVE`）、使用者總數
  - 小卡片：草稿商品、已下架商品、已出貨待完成（`SHIPPED`）
- 點卡片進入對應的列表並帶上篩選條件（例如 `/orders?status=PAID`）
- 待辦清單：最新 5 筆待出貨訂單、草稿商品，點了進入詳情或編輯頁
- 每一塊各自讀取，一塊失敗只顯示「—」或錯誤文字，不影響其他塊
- 測試：`views/__tests__/HomeView.spec.ts`（6 個）：打招呼、各數字、點卡片帶篩選條件、待辦清單與進入詳情、一塊失敗不影響其他、空狀態

**確認畫面**

Chrome 擴充功能沒有連上，改用本機 Chrome 的 headless 模式透過 DevTools protocol 截圖：先用 API 登入，把 refresh token 寫進 localStorage，頁面載入時路由守衛就會恢復登入（和重新整理頁面走同一條路）。確認了首頁、商品列表、訂單列表的版面。

**之後可以考慮**：需要趨勢圖（每日訂單數、營收）時，要先在 order-service 補統計 API，前端再加圖表套件。

vitest 261 個通過、`npm run build`（含 `vue-tsc`）、eslint、oxlint、prettier 都沒有問題。

<a id="step-04-系統名稱2026-11-10"></a>

### 系統名稱（2026-11-10）

畫面上顯示的名稱從專案名稱 `vue-management` 改成「商城管理後台」：技術名稱（Vue）對使用者沒有意義，管理系統通常用「品牌或用途 + 後台」命名；之後有店名時把「商城」換成店名即可。

- 名稱集中在 `src/router/index.ts` 的 `APP_TITLE`：側邊欄、瀏覽器分頁標題（「頁面標題 - 商城管理後台」）、登入頁都用它；`index.html` 的 `<title>` 也改了
- 登入頁的說明改成「商品、訂單與庫存管理」，避免和名稱重複「後台管理」
- **沒改的**：repo、資料夾、`package.json` 的名稱仍是 `vue-management`；localStorage 的 key `vue-management.refreshToken` 是內部用的，改了會讓已登入的人都被登出

<a id="step-04-字體放大2026-11-10"></a>

### 字體放大（2026-11-10）

使用者覺得整體字體偏小。做法是在 `src/styles/theme.css` 統一調整，不在各頁面個別改：

- Element Plus 的字級變數各加 2px：`--el-font-size-base` 14 → 16px、`small` 13 → 15px、`extra-small` 12 → 14px、`medium` 16 → 18px、`large` 18 → 20px、`extra-large` 20 → 22px；`body` 也用 `--el-font-size-base`
- 元件高度跟著加高（`--el-component-size` 32 → 36px、`large` 40 → 44px、`small` 24 → 28px），放大後的字不會擠
- 自己寫死的字級（12、13、14、16、18、20px）都改成上面的變數，之後要再調只改一個地方；標題、數字卡片這類大字改成 24、28、42px
- **Element Plus 有些元件不用字級變數，另外覆寫**：`el-tag`（12px）、`el-breadcrumb`、`el-select__wrapper` 與 `--el-select-input-font-size`、`el-pagination`、`el-alert`、`el-dialog` 內容、`el-message`、`el-descriptions` 的儲存格、`el-timeline` 的時間（都是寫死 14px 或 12px）
- **原生 `<button>` 不會繼承頁面字級**（瀏覽器預設約 13px）：首頁的數字卡片、小卡片、待辦項目是自己做的按鈕，全域加上 `button { font: inherit }`
- 用 headless Chrome 截圖確認首頁、商品列表、訂單詳情

---

<a id="step-05"></a>

## vue-management Step 5：分類管理

- 樹狀顯示、新增（可選父分類）、修改、刪除

**測試**：表單驗證；刪除失敗時依錯誤碼顯示訊息。

<a id="step-05-後端補強2026-11-10"></a>

### 後端補強（2026-11-10）

**問題**：`GET /categories/tree` 是給前台用的，只回傳啟用中的分類，父分類停用時整個子樹也會藏起來。後台如果用它顯示分類，管理員把分類停用後它就從畫面消失，再也無法重新啟用或編輯。

**決定**：在 product-service 補一支後台專用的 API（使用者選擇由 Claude 實作）。

| API | 權限 | 回應 |
| --- | ---- | ---- |
| `GET /api/v1/categories` | ADMIN（`@RequireRoles`） | `{ categories: CategoryListItem[] }`：平面列表，含停用的分類；欄位有 `id`、`parentId`、`name`、`slug`、`description`、`sortOrder`、`active`、`createdAt`、`updatedAt`、`version`；依 `sortOrder`、`name` 排序，不分頁 |

- 不經過 Redis 快取：分類樹的快取只有啟用中的分類，而且後台修改後要立即看到結果
- Gateway 對 `GET /api/v1/categories/**` 放行，但前端會帶 token，Gateway 驗簽後照樣轉發身分 header；沒帶 token 時 product-service 回 401 `UNAUTHENTICATED`
- 測試：`CategoryListIntegrationTests`（3）、`AdminEndpointsAuthorizationIntegrationTests` 加入這支 API（401、403、ADMIN 通過各 1）；product-service 126 個通過。commit `b96cd5e`

**刪除的行為**（實作前先用整合測試確認，`CategoryDeleteIntegrationTests`，4 個，commit `069bdb5`）：

| 情況 | 回應 |
| ---- | ---- |
| 沒有子分類、沒有商品 | 200 |
| 底下還有子分類 | 409 `DATA_CONFLICT`（`parent_id` 外鍵 `ON DELETE RESTRICT`） |
| 還有商品使用這個分類 | 409 `DATA_CONFLICT`（`product.category_id` 外鍵 `ON DELETE RESTRICT`） |
| id 不存在 | 200（`deleteById` 不會丟例外） |

- 沒有專用的錯誤代碼，前端在刪除時把 `DATA_CONFLICT` 解讀成「底下還有子分類或商品」
- 刪除 API 的 `@ResponseStatus` 寫 204，實際回 200 加上純文字 `delete successfully.`；前端只看狀態碼，不受影響

<a id="step-05-完成內容2026-11-10"></a>

### 完成內容（2026-11-10）

| 檔案 | 內容 |
| ---- | ---- |
| `src/features/categories/types.ts` | `Category`、`CategoryNode`、新增與修改的 request |
| `src/features/categories/api.ts` | `getAllCategories`、`createCategory`、`updateCategory`、`deleteCategory` |
| `src/features/categories/tree.ts` | `buildTree`：依 `parentId` 組成樹；`subtreeIds`：自己與所有子孫 |
| `src/features/categories/messages.ts` | 新增、修改、刪除失敗時依錯誤代碼顯示的訊息 |
| `src/features/categories/CategoriesView.vue` | 樹狀表格（含停用標籤）、新增、新增子分類、編輯、刪除 |
| `src/features/categories/CategoryFormDialog.vue` | 新增、編輯共用的表單對話框 |
| `src/composables/useConfirm.ts` | `confirmDanger`：包一層 `ElMessageBox.confirm`，回傳 true / false |
| `src/router/index.ts` | `/categories`，出現在側邊選單 |
| `tsconfig.vitest.json` | include 加上 `auto-imports.d.ts`、`components.d.ts` |

<a id="step-05-設計"></a>

#### 設計

- **樹由前端組**：`buildTree` 依 `parentId` 組樹，同一層維持後端的順序；找不到父分類的當成頂層（不讓資料消失），資料有循環也不會無限遞迴；沒有子分類的節點不放 `children`，表格才不會顯示展開箭頭
- **上層分類選單**：`el-tree-select`（`check-strictly`，任何一層都能選）；清除就是頂層分類。修改時排除自己與所有子孫，後端也會擋（`CATEGORY_PARENT_INVALID`）
- **表單驗證和後端一致**：名稱必填（只有空白也算沒填）、最多 100 字；slug 必填、最多 100 字、`^[a-z0-9]+(-[a-z0-9]+)*$`；排序不小於 0
- **送出前整理**：名稱、slug 去掉前後空白；說明空白時送 `null`
- **修改帶 `version`**：用列表回傳的 `version`，不另外查詳細資料；已被其他人修改時後端回 409 `VERSION_CONFLICT`，提示重新整理
- **錯誤顯示的位置**：
  - 新增、修改失敗：顯示在對話框裡，對話框不關閉，使用者可以直接修正
  - 刪除結果：`ElMessage`（成功或失敗都重新讀取列表，失敗可能是因為資料已被其他人改過）
  - 讀取列表失敗：表格上方的錯誤提示

錯誤代碼對應的訊息：

| 代碼 | 情境 | 訊息 |
| ---- | ---- | ---- |
| `CATEGORY_SLUG_DUPLICATED` | 新增、修改 | 這個 slug 已被其他分類使用 |
| `CATEGORY_NAME_DUPLICATED` | 新增、修改 | 同一層已有相同名稱的分類 |
| `CATEGORY_PARENT_NOT_FOUND` | 新增、修改 | 上層分類不存在，請重新整理後再試 |
| `CATEGORY_PARENT_INVALID` | 修改 | 上層分類不能是自己或自己的子分類 |
| `CATEGORY_NOT_FOUND` | 修改 | 分類不存在，可能已被刪除，請重新整理 |
| `VERSION_CONFLICT` | 修改 | 分類已被其他人修改，請重新整理後再試 |
| `DATA_CONFLICT` | 新增、修改 | 名稱或 slug 和其他分類重複（同時送出，被 DB 唯一限制擋下） |
| `DATA_CONFLICT` | 刪除 | 這個分類底下還有子分類或商品，無法刪除 |
| `VALIDATION_FAILED` | 新增、修改 | 欄位格式不正確：後端的 detail |

- **確認視窗獨立成 `confirmDanger`**：測試直接 `vi.mock`，不必操作 `ElMessageBox` 的 DOM；之後商品封存、訂單取消也會用到

<a id="step-05-測試"></a>

#### 測試

| 測試檔 | 案例 |
| ------ | ---- |
| `features/categories/__tests__/tree.spec.ts` | 5 個：組樹與順序、葉節點沒有 `children`、找不到父分類、循環資料、`subtreeIds` |
| `features/categories/__tests__/messages.spec.ts` | 11 個：新增、修改、刪除各錯誤代碼的訊息 |
| `features/categories/__tests__/CategoriesView.spec.ts` | 17 個：樹狀顯示含停用、讀取失敗；新增（送出內容、去空白、成功後關閉並重新讀取）、新增子分類帶入上層、必填驗證、只有空白、5 種錯誤的 slug、slug 重複；編輯帶入目前的值與 `version`、`VERSION_CONFLICT`；刪除成功、取消不發請求、`DATA_CONFLICT` 顯示原因 |

<a id="step-05-遇到的問題"></a>

#### 遇到的問題

- **`ElMessage`、`ElMessageBox` 型別找不到**：它們由 unplugin-auto-import 自動引入，宣告在 `src/auto-imports.d.ts`。`tsconfig.vitest.json` 原本只 include 測試檔與 `env.d.ts`，被測試 import 的元件改用這個專案檢查型別時看不到宣告。include 加上 `auto-imports.d.ts`、`components.d.ts`
- **`auto-imports.d.ts` 是執行 vite（dev、build、vitest）時才更新**：第一次用到 `ElMessage` 後要跑過一次，檔案才會加上宣告；這個檔案有 commit
- **`el-table` 插槽的 `row` 是通用的 `DefaultRow`**：在插槽解構上標型別會和 Element Plus 的插槽型別衝突，改成呼叫時 `row as CategoryNode`
- **表單驗證訊息要等下一次渲染**：`validate()` 失敗後訊息不會馬上出現在 DOM，測試要用 `vi.waitFor`
- **還沒接真正的後端測試**：寫完時 compose 沒有啟動。前端只用 MSW 驗證；後端 API 已用整合測試（Testcontainers）驗證（2026-11-10 已接上後端，用 API 實測過，見 [後端 API 實測](#backend-integration)；畫面還沒在瀏覽器操作過）

<a id="step-05-最終檢查"></a>

### 最終檢查

- 前端：vitest 115 個通過（新增 33 個）、`npm run build`（含 `vue-tsc`）通過、eslint、oxlint、prettier 都沒有問題
- 後端：product-service 全部 130 個通過（含新 API 與刪除行為的測試）

---

<a id="step-06"></a>

## vue-management Step 6：商品管理

- 列表：篩選、分頁，條件同步到 URL query
- 新增、編輯表單；上架、下架、封存

**測試**：狀態按鈕依目前狀態啟用或停用；封存後不能編輯；上架失敗（例如沒有主圖）顯示錯誤碼。

<a id="step-06-後端的對應實作前確認過程式碼"></a>

### 後端的對應（實作前確認過程式碼）

| API | 說明 |
| --- | ---- |
| `GET /products` | 篩選 `keyword`（名稱模糊比對）、`categoryId`、`status`、`minPrice`、`maxPrice`；分頁 `page`（從 0 開始）、`size`（1–100，預設 20）；排序 `sortBy`（`createdAt`、`price`、`name`）、`sortDirection`。**回傳所有狀態**，草稿、封存也會列出。項目沒有 `description`、`version` |
| `GET /products/{id}` | 完整資料，含 `version` |
| `POST /products` | 新增，狀態固定 `DRAFT`；草稿可以不指定分類 |
| `PUT /products/{id}` | 整筆取代，帶 `version`；封存的商品回 `PRODUCT_ARCHIVED` |
| `POST /products/{id}/publish`、`/unpublish`、`/archive` | body 只有 `{ version }`，回傳新的 `status`、`version`、`updatedAt` |

狀態流轉：

| 操作 | 允許的狀態 | 其他檢查 |
| ---- | ---------- | -------- |
| 上架 | `DRAFT`、`INACTIVE` → `ACTIVE` | 要有啟用中的分類（`PRODUCT_ACTIVE_CATEGORY_REQUIRED`）、要有主圖（`PRODUCT_PRIMARY_IMAGE_REQUIRED`） |
| 下架 | `ACTIVE` → `INACTIVE` | |
| 封存 | `ARCHIVED` 以外 → `ARCHIVED` | 已封存回 `PRODUCT_ALREADY_ARCHIVED`；封存後不能修改 |

- 新增時沒有先檢查 SKU，重複時由 DB 的唯一限制擋下，回 409 `DATA_CONFLICT`（修改時有檢查，回 `PRODUCT_SKU_DUPLICATED`）
- 修改時會檢查分類是否啟用（`CATEGORY_INACTIVE`）；新增時不檢查

<a id="step-06-完成內容2026-11-10"></a>

### 完成內容（2026-11-10）

| 檔案 | 內容 |
| ---- | ---- |
| `src/features/products/types.ts` | 商品、分頁、request、查詢參數的型別 |
| `src/features/products/api.ts` | `getProducts`、`getProduct`、`createProduct`、`updateProduct`、`changeProductStatus` |
| `src/features/products/query.ts` | URL query ↔ 列表條件 ↔ API 參數的轉換 |
| `src/features/products/status.ts` | 狀態文字、標籤顏色、`canPublish` 等可執行的操作 |
| `src/features/products/messages.ts` | 依錯誤代碼顯示的訊息 |
| `src/features/products/format.ts` | 價格顯示（千分位，小數最多兩位） |
| `src/features/products/ProductListView.vue` | 篩選、表格（分類名稱、價格、狀態）、欄位排序、分頁 |
| `src/features/products/ProductEditView.vue` | 新增與編輯共用：表單、狀態列（上架、下架、封存） |
| `src/composables/useRouteQuery.ts` | 列表條件放在 URL query 的通用 composable |
| `src/router/index.ts` | `/products`（列表）、`/products/new`、`/products/:id`，放在沒有元件的父路由「商品管理」底下 |
| `src/layouts/AdminLayout.vue` | 麵包屑：沒有名稱的父路由用路徑連結 |

<a id="step-06-設計"></a>

#### 設計

- **列表條件放在 URL query**：
  - `useRouteQuery` 從目前的 URL 解析條件，`update` 只改 URL；畫面 watch 條件變化再讀取，所以搜尋、換頁、排序、上一頁都走同一條路
  - URL 上的 `page` 從 1 開始，送給後端時減 1；和預設值相同的條件不放進 URL
  - 不合法的值（手動改網址、舊連結）一律改回預設值，不送到後端
  - 篩選表單是另一份狀態，按「搜尋」才寫進 URL，並回到第一頁；URL 變化（例如上一頁）時表單跟著更新
  - 條件快速切換時，只採用最後一次請求的結果
- **搜尋前檢查價格**：只能是數字、最多兩位小數、最低不能大於最高；不合法時提示，不改 URL（後端也會回 `VALIDATION_FAILED`）
- **排序**：名稱、售價、建立時間三欄可以排序；取消排序回到預設（最新的排前面）；表格用 `:key` 依排序條件重建，上一頁時欄位上的排序箭頭才會同步
- **分類**：列表與篩選共用 `GET /categories`（含停用）；表單的分類選單中停用的分類不能選，但仍顯示，已選到停用分類時看得到名稱
- **新增後進入編輯頁**：之後的圖片（Step 7）、庫存（Step 8）、上架都在編輯頁操作
- **狀態列只在編輯頁**：列表項目沒有 `version`，狀態變更需要；按鈕依目前狀態啟用或停用，成功後用回應更新狀態與 `version`，下一個操作帶新的 `version`
- **封存**：先用 `confirmDanger` 確認；封存後表單停用、不顯示儲存按鈕
- **未儲存的修改**：表單和已儲存的內容不同、而且可以上架時，提醒「上架檢查的是已儲存的內容」
- **麵包屑**：「商品管理」是沒有元件、沒有名稱的父路由，麵包屑用它的路徑連結回列表；選單在 `/products/:id` 也選中「商品管理」

<a id="step-06-測試"></a>

#### 測試

| 測試檔 | 案例 |
| ------ | ---- |
| `features/products/__tests__/query.spec.ts` | 7 個：預設值、合法參數、不合法參數改回預設、重複參數取第一個、URL 省略預設值、轉成 API 參數 |
| `features/products/__tests__/status.spec.ts` | 16 個：四種狀態可執行的操作、各錯誤代碼的訊息 |
| `features/products/__tests__/ProductListView.spec.ts` | 12 個：預設參數、URL 條件轉成 API 參數並帶入表單、列表內容、讀取失敗；搜尋寫進 URL 並回第一頁、最低大於最高、價格不是數字、換頁、清除條件、欄位排序、上一頁、進入編輯頁 |
| `features/products/__tests__/ProductEditView.spec.ts` | 18 個：新增送出內容並進入編輯頁、必填驗證、原價低於售價、SKU 重複；編輯帶入目前的值與 `version`、`VERSION_CONFLICT`、商品不存在、未儲存的提醒；四種狀態的按鈕、封存後不能編輯、上架成功並帶新的 `version`、沒有主圖、沒有分類、封存取消、封存確認 |
| `layouts/__tests__/AdminLayout.spec.ts` | 新增 1 個：巢狀頁面的麵包屑與選單 |

<a id="step-06-遇到的問題"></a>

#### 遇到的問題

- **又是 ES2020 的 `lib`**：測試裡用了 `Array.prototype.at`，`vue-tsc` 才報錯（vitest 不做型別檢查）。改成自己寫 `lastRequest()`
- **Element Plus 的型別比較通用**：`el-table` 的 `sort-change` 參數 `prop` 可能是 `null`；`el-tree-select` 的 `disabled` 函式參數是 `TreeNodeData`。都在函式裡自己判斷或轉型
- **動態 import 的頁面**：按「編輯」導到編輯頁時要等元件載入，`flushPromises` 不夠，測試改用 `vi.waitFor`
- **還沒接真正的後端測試**：寫完時 compose 沒有啟動（2026-11-10 已接上後端，用 API 實測過，見 [後端 API 實測](#backend-integration)；畫面還沒在瀏覽器操作過）

<a id="step-06-發現的後端問題沒有修改"></a>

#### 發現的後端問題（沒有修改）

- **`GET /products`、`GET /products/{id}` 公開，而且回傳所有狀態**：未登入的人也能列出草稿、已封存的商品。後台需要看到全部，但前台應該只看得到上架中的商品。之後做前台或整理後端時再決定（例如公開 API 只回 `ACTIVE`，後台另外用 ADMIN 的列表）

<a id="step-06-最終檢查"></a>

### 最終檢查

vitest 169 個通過（新增 54 個）、`npm run build`（含 `vue-tsc`）通過、eslint、oxlint、prettier 都沒有問題。

---

<a id="step-07"></a>

## vue-management Step 7：商品圖片

- 流程：選檔 → 前端先檢查類型與大小 → `upload-url` → 帶 `requiredHeaders` PUT → 登記
- 設定主圖、拖曳排序、刪除

**測試**：類型或大小不符時不發請求；PUT 失敗不會登記；排序送出的順序正確。

<a id="step-07-後端的對應實作前確認過程式碼"></a>

### 後端的對應（實作前確認過程式碼）

| API | 說明 |
| --- | ---- |
| `POST /products/{id}/images/upload-url` | body `{ contentType, contentLength }`；回 `objectKey`、`uploadUrl`（presigned PUT，10 分鐘）、`requiredHeaders`（`Content-Type`、`Content-Length`）、`expiresAt`。只接受 `image/jpeg`、`image/png`、`image/webp`，最大 5 MB（`storage.allowed-content-types`、`storage.max-size`） |
| PUT `uploadUrl` | 直接上傳到物件儲存（本機是 SeaweedFS `:8333`），不經過 Gateway |
| `POST /products/{id}/images` | 登記 `{ objectKey, altText }`；後端確認檔案存在且符合限制後，把物件從 `tmp/` 搬到 `products/`；第一張自動成為主圖 |
| `GET /products/{id}/images` | 依 `sortOrder` 排序 |
| `POST /products/{id}/images/{imageId}/primary` | 回傳整個列表 |
| `PUT /products/{id}/images/order` | `{ imageIds }`，必須剛好是這個商品的所有圖片，否則 `PRODUCT_IMAGE_IDS_MISMATCH`；回傳整個列表 |
| `DELETE /products/{id}/images/{imageId}` | 上架中（`ACTIVE`）的商品不能刪除主圖（`PRODUCT_IMAGE_PRIMARY_UNDELETABLE`）；其他狀態刪除主圖後**不會自動補上新的主圖** |

- 已封存的商品：所有圖片操作都回 `PRODUCT_ARCHIVED`
- Step 0 已實測 SeaweedFS 的 CORS：預設允許所有來源，上傳不能帶 credentials

<a id="step-07-完成內容2026-11-10"></a>

### 完成內容（2026-11-10）

| 檔案 | 內容 |
| ---- | ---- |
| `src/features/products/images/types.ts` | 圖片、上傳網址的型別 |
| `src/features/products/images/api.ts` | 圖片 API、`putToStorage`、完整上傳流程 `uploadProductImage` |
| `src/features/products/images/validate.ts` | `validateImageFile`（類型、大小）、`moveItem` |
| `src/features/products/images/messages.ts` | 圖片錯誤代碼的訊息，沒有對應時沿用商品的訊息 |
| `src/features/products/images/ProductImages.vue` | 圖片區塊：上傳（含進度）、主圖標籤、左右移動、拖曳排序、設為主圖、刪除 |
| `src/features/products/ProductEditView.vue` | 編輯頁加上圖片區塊（新增頁沒有，商品建立後才能上傳）；封存時唯讀 |

<a id="step-07-設計"></a>

#### 設計

- **上傳流程**：申請上傳網址 → PUT 到物件儲存 → 登記。PUT 失敗就不登記，避免留下指向不存在檔案的圖片；上傳完重新讀取列表（第一張自動成為主圖，順序以後端為準）
- **前端先檢查**：類型（JPEG、PNG、WebP）、空檔案、大小（5 MB），不合格的不申請上傳網址；限制和後端設定一致，後端仍會再檢查
- **物件儲存用另一個 axios 實例**（`storageClient`）：
  - 不能帶我們 API 的 `Authorization`，auth 攔截器只掛在 `apiClient`
  - `withCredentials: false`：SeaweedFS 回 `Access-Control-Allow-Origin: *`，帶 credentials 會被瀏覽器拒絕
  - 網址是完整的 presigned URL，不套用 `baseURL`
  - 錯誤是 S3 的 XML，不是 ProblemDetail，統一轉成前端的 `IMAGE_UPLOAD_FAILED`
- **`Content-Length` 不手動設定**：它是瀏覽器禁止設定的 header，會依 body 自動帶上；簽章裡的值就是檔案大小，所以結果一致。`requiredHeaders` 裡其他的 header（`Content-Type`）照樣帶上
- **上傳進度**：axios 的 `onUploadProgress`（XHR），按鈕顯示「上傳中 n%」
- **排序**：先在畫面上換好順序再送出（所有圖片的 id），失敗時改回原本的順序；成功後用回應的列表為準
  - 拖曳用 HTML5 drag and drop，不另外加套件
  - 每張圖片也有「←」「→」按鈕：不能拖曳時（觸控裝置、鍵盤操作）的替代方式
- **同一時間只做一件事**：上傳、排序、設主圖、刪除進行中時，其他操作的按鈕停用，避免互相覆蓋
- **沒有主圖時提醒**：例如刪除了主圖，提示「上架前要先設定主圖」；刪除主圖前的確認訊息也會提醒
- **刪除**：成功或失敗都重新讀取列表
- 沒有做替代文字（`altText`）的編輯：計畫沒有列，API 已經有（`PUT /images/{imageId}`），需要時再補

<a id="step-07-測試"></a>

#### 測試

| 測試檔 | 案例 |
| ------ | ---- |
| `features/products/images/__tests__/ProductImages.spec.ts` | 25 個：`validateImageFile` 6 種情況、`moveItem`；依順序顯示並標出主圖、沒有主圖的提醒、唯讀；完整上傳流程（三個步驟的順序與內容、不手動帶 `Content-Length`）、`storageClient` 的設定、類型不符與超過大小不發請求、PUT 失敗不登記、後端拒絕申請；設為主圖、刪除、刪除主圖的確認訊息、上架中不能刪主圖；往後移、往前移、第一張與最後一張的按鈕停用、拖曳、排序失敗改回原順序 |

- `ProductEditView.spec.ts` 加上圖片 API 的預設 handler：編輯頁現在也會讀圖片

<a id="step-07-遇到的問題"></a>

#### 遇到的問題

- **vitest 4.1 的 jsdom 橋接不能傳 `Blob`、`File` 當請求 body**：MSW 的 XHR 攔截器會把請求轉成 Node 的 `Request`，vitest 轉換 jsdom Blob 時找不到內部資料（`Cannot read properties of undefined (reading '_buffer')`），連單純的 `new Request(url, { body: new Blob(...) })` 都會失敗。這是測試環境的問題，瀏覽器不受影響。做法：
  - 上傳本體這一步在測試裡 `vi.spyOn(storageClient, 'put')`，驗證網址、檔案、header
  - 申請網址、登記等我們的 API 照樣用 MSW
  - `storageClient` 因此匯出，註解寫明原因
- **還沒在瀏覽器實際上傳過**：寫完時 compose 沒有啟動。實際操作時要特別確認 presigned PUT 的 CORS 與簽章（`Content-Type` 要和申請時一致）（2026-11-10 已接上後端，用 API 實測過，見 [後端 API 實測](#backend-integration)；畫面還沒在瀏覽器操作過）

<a id="step-07-最終檢查"></a>

### 最終檢查

vitest 194 個通過（新增 25 個）、`npm run build`（含 `vue-tsc`）通過、eslint、oxlint、prettier 都沒有問題。

---

<a id="step-08"></a>

## vue-management Step 8：庫存

- 放在商品編輯頁：依商品 SKU 查詢與調整庫存

**測試**：調整成功後重新讀取；SKU 尚未建立庫存時的顯示。

<a id="step-08-後端的對應實作前確認過程式碼"></a>

### 後端的對應（實作前確認過程式碼）

| API | 說明 |
| --- | ---- |
| `GET /api/v1/inventory/{sku}` | 公開；回 `sku`、`available`（可賣，不含已預扣）、`reserved`（已預扣、待付款）、`updatedAt`；還沒建立回 404 `STOCK_NOT_FOUND` |
| `PUT /api/v1/inventory/{sku}` | 限 ADMIN；body `{ available }`（0 以上的整數），直接設定盤點後的可賣數量；不存在就建立（201），存在就更新（200）；`reserved` 不能從這裡改 |

- **沒有版本檢查**：以最後一次設定為準
- 雛型階段 inventory-service 不檢查 product-service 是否有這個 SKU

<a id="step-08-完成內容2026-11-10"></a>

### 完成內容（2026-11-10）

| 檔案 | 內容 |
| ---- | ---- |
| `src/features/products/inventory/api.ts` | `getStock`（404 `STOCK_NOT_FOUND` 轉成 `null`）、`setStock`；SKU 放在路徑上會編碼 |
| `src/features/products/inventory/ProductInventory.vue` | 顯示可賣、已預扣、更新時間；設定可賣數量（建立或更新） |
| `src/features/products/ProductEditView.vue` | 編輯頁在圖片下方加上庫存區塊，用**已儲存的** SKU；封存時唯讀 |

<a id="step-08-設計"></a>

#### 設計

- **還沒建立庫存不是錯誤**：`getStock` 把 404 `STOCK_NOT_FOUND` 轉成 `null`，畫面顯示「還沒有建立庫存，設定可賣數量後就會建立」，按鈕是「建立庫存」；其他錯誤（例如 inventory-service 掛了）才顯示錯誤，而且不顯示表單
- **調整成功後重新讀取**：預扣數量可能同時有變化，以後端為準
- **用已儲存的 SKU**：表單上還沒儲存的 SKU 不算；商品儲存了新的 SKU 後重新讀取
- **修改 SKU 的影響**：庫存以 SKU 對應，修改商品的 SKU 後，舊 SKU 的庫存不會跟著搬過去。目前只在畫面上提示，要重新設定
- **前端檢查**：可賣數量要是 0 以上的整數（`el-input-number` 限制最小值與整數，清空時另外檢查）
- 封存的商品只顯示庫存，不能調整

<a id="step-08-測試"></a>

#### 測試

| 測試檔 | 案例 |
| ------ | ---- |
| `features/products/inventory/__tests__/ProductInventory.spec.ts` | 9 個：顯示可賣與已預扣、調整成功後重新讀取、還沒建立時的提示與建立、沒填數量不發請求、儲存失敗、讀取失敗、SKU 編碼、SKU 改了重新讀取、唯讀 |

- `ProductEditView.spec.ts` 加上庫存 API 的預設 handler：編輯頁現在也會讀庫存

<a id="step-08-之後可以考慮"></a>

#### 之後可以考慮

- **修改 SKU 時庫存跟著搬**：目前要手動重新設定；可以在 product-service 修改 SKU 時通知 inventory-service，或改成以商品 id 對應庫存
- **庫存設定沒有版本檢查**：兩個管理員同時調整時，後送出的會蓋掉先送出的；盤點情境通常可以接受

<a id="step-08-最終檢查"></a>

### 最終檢查

vitest 203 個通過（新增 9 個）、`npm run build`（含 `vue-tsc`）通過、eslint、oxlint、prettier 都沒有問題。

---

<a id="step-09"></a>

## vue-management Step 9：訂單管理

- 列表（依狀態篩選、分頁）、詳情（商品快照、金額、狀態、各階段時間）、取消、出貨、完成

**測試**：按鈕依狀態顯示（`PENDING_PAYMENT` 取消、`PAID` 出貨、`SHIPPED` 完成）；失敗時顯示錯誤碼。

<a id="step-09-後端的對應實作前確認過程式碼"></a>

### 後端的對應（實作前確認過程式碼）

| API | 說明 |
| --- | ---- |
| `GET /orders` | ADMIN 看全部，可用 `status`、`userId` 篩選；`page`（從 0 開始）、`size`（1–100）；固定最新的在前，不能換排序。項目只有摘要（`id`、`userId`、`status`、`totalAmount`、`createdAt`） |
| `GET /orders/{id}` | 明細：商品快照（下單當下的名稱、單價）、數量、小計，以及 `createdAt`、`paidAt`、`shippedAt`、`completedAt`、`cancelledAt` |
| `POST /orders/{id}/cancel` | 只限 `PENDING_PAYMENT`，會釋放預扣的庫存；本人或 ADMIN |
| `POST /orders/{id}/ship` | 只限 `PAID`；限 ADMIN（Step 0 補上） |
| `POST /orders/{id}/complete` | 只限 `SHIPPED`；限 ADMIN（Step 0 補上） |

- 三個操作重複呼叫（已經是目標狀態）都回 200，回傳更新後的訂單
- 已付款之後不能取消：雛型沒有退款流程

<a id="step-09-完成內容2026-11-10"></a>

### 完成內容（2026-11-10）

| 檔案 | 內容 |
| ---- | ---- |
| `src/features/orders/types.ts` | 訂單、明細、分頁、查詢參數的型別 |
| `src/features/orders/api.ts` | `getOrders`、`getOrder`、`changeOrderStatus` |
| `src/features/orders/query.ts` | URL query ↔ 列表條件 ↔ API 參數；`userId` 檢查 UUID 格式 |
| `src/features/orders/status.ts` | 狀態文字、標籤顏色、`availableAction`、確認訊息、錯誤訊息 |
| `src/features/orders/OrderListView.vue` | 狀態、使用者 ID 篩選；分頁；點使用者 ID 只看他的訂單 |
| `src/features/orders/OrderDetailView.vue` | 訂單資訊、各階段時間（只顯示發生過的）、商品快照；依狀態顯示一個操作按鈕 |
| `src/utils/format.ts` | 從 `features/products/format.ts` 搬過來，加上 `formatDateTime`；商品、庫存、訂單共用 |
| `src/router/index.ts` | `/orders`、`/orders/:id`，放在「訂單管理」父路由底下 |

<a id="step-09-設計"></a>

#### 設計

- **列表條件放在 URL query**：和商品列表一樣用 `useRouteQuery`；`userId` 不是 UUID 時搜尋前就提示，網址上不合法的值改回預設（送到後端會回 400）
- **點使用者 ID 篩選**：列表上的使用者 ID 是連結，點了只看這個使用者的訂單
- **每個狀態最多一個操作**：`availableAction` 決定（待付款 → 取消、已付款 → 出貨、已出貨 → 完成，其他沒有）；用 `v-if` 只顯示能做的那一個
- **三個操作都先確認**：都不能復原；取消的確認訊息提醒會釋放預扣的庫存
- **操作成功後用回應更新**：回應就是完整的訂單，不必再查一次
- **失敗時顯示原因**：例如使用者剛好付款，取消會回 `ORDER_NOT_CANCELLABLE`，提示狀態可能剛改變、請重新整理；詳情頁有「重新整理」按鈕
- **列表只顯示使用者 ID**：order-service 只有 `userId`；Step 10 做好使用者列表後，可以考慮對應成 email

錯誤代碼對應的訊息：

| 代碼 | 訊息 |
| ---- | ---- |
| `ORDER_NOT_FOUND` | 訂單不存在 |
| `ORDER_NOT_CANCELLABLE` | 只有待付款的訂單可以取消，訂單狀態可能剛改變，請重新整理 |
| `ORDER_ALREADY_CANCELLED` | 訂單已經取消 |
| `ORDER_NOT_SHIPPABLE` | 只有已付款的訂單可以出貨，請重新整理 |
| `ORDER_NOT_COMPLETABLE` | 只有已出貨的訂單可以完成，請重新整理 |
| `INVENTORY_SERVICE_UNAVAILABLE` | 庫存服務暫時無法使用，請稍後再試（取消時要釋放庫存） |

<a id="step-09-測試"></a>

#### 測試

| 測試檔 | 案例 |
| ------ | ---- |
| `features/orders/__tests__/query.spec.ts` | 16 個：URL 解析（預設、合法、不合法）、轉成 URL 與 API 參數、五種狀態可執行的操作、各錯誤代碼的訊息 |
| `features/orders/__tests__/OrderListView.spec.ts` | 10 個：預設參數、URL 條件轉成 API 參數並帶入表單、列表內容、搜尋使用者、使用者 ID 格式不對、點使用者 ID 篩選、換頁、清除條件、讀取失敗、進入詳情 |
| `features/orders/__tests__/OrderDetailView.spec.ts` | 14 個：訂單資訊與商品快照、只顯示發生過的階段時間、訂單不存在；五種狀態顯示的按鈕；取消、出貨、完成成功後更新、取消確認視窗與按取消、取消失敗（`ORDER_NOT_CANCELLABLE`）、出貨失敗（`ORDER_NOT_SHIPPABLE`） |

<a id="step-09-遇到的問題"></a>

#### 遇到的問題

- **oxlint 的 `no-conditional-expect`**：按鈕的測試一開始用 `if` 分成「有按鈕」「沒按鈕」兩種斷言，被擋下。改成每個狀態直接比對按鈕文字的陣列（沒有按鈕就是空陣列）

<a id="step-09-最終檢查"></a>

### 最終檢查

vitest 243 個通過（新增 40 個）、`npm run build`（含 `vue-tsc`）通過、eslint、oxlint、prettier 都沒有問題。

---

<a id="step-10"></a>

## vue-management Step 10：使用者列表

**測試**：分頁、篩選條件同步到 URL query。

<a id="step-10-後端的對應實作前確認過程式碼"></a>

### 後端的對應（實作前確認過程式碼）

`GET /api/v1/users`，限 ADMIN：

- 篩選：`keyword`（不分大小寫，比對 email 或顯示名稱，最多 254 字）、`status`（`ACTIVE`、`DISABLED`）、`role`（`ADMIN`、`CUSTOMER`）
- 分頁：`page`（從 0 開始）、`size`（1–100，預設 20）
- 排序：`sortBy`（`createdAt`、`email`、`displayName`）、`sortDirection`；預設最新註冊的排前面
- 項目：`id`、`email`、`displayName`、`status`、`roles`、`createdAt`
- 雛型階段只有列表：不能修改角色或停用（計畫的「後端前置問題」已決定）

<a id="step-10-完成內容2026-11-10"></a>

### 完成內容（2026-11-10）

| 檔案 | 內容 |
| ---- | ---- |
| `src/features/users/types.ts` | 使用者、分頁、查詢參數的型別；`Role`、`UserStatus` 沿用 `features/auth/types.ts` |
| `src/features/users/api.ts` | `getUsers` |
| `src/features/users/query.ts` | URL query ↔ 列表條件 ↔ API 參數 |
| `src/features/users/UserListView.vue` | 關鍵字、狀態、角色篩選；Email、顯示名稱、註冊時間排序；分頁；「查看訂單」 |
| `src/router/index.ts` | `/users`，出現在側邊選單 |

<a id="step-10-設計"></a>

#### 設計

- 和商品、訂單列表一樣用 `useRouteQuery`：條件放在 URL，搜尋與排序回到第一頁，不合法的值改回預設
- **查看訂單**：顧客可以直接到訂單列表，帶上 `?userId=` 只看這個使用者的訂單（Step 9 的訂單列表支援這個條件）
  - 只有具備 `CUSTOMER` 角色的使用者才顯示：註冊的帳號是 `CUSTOMER`，dev 建立的管理員只有 `ADMIN`，不會有訂單（實際看畫面時發現，2026-11-10 補上；同時有兩個角色的仍然顯示）
- 角色可能有多個（例如同時是 ADMIN 與 CUSTOMER），每個角色一個標籤

<a id="step-10-測試"></a>

#### 測試

| 測試檔 | 案例 |
| ------ | ---- |
| `features/users/__tests__/UserListView.spec.ts` | 12 個：只有顧客有「查看訂單」、URL 條件的解析（預設、合法、不合法）、轉成 URL 與 API 參數；預設參數、URL 條件轉成 API 參數並帶入表單、角色與狀態的顯示、搜尋、換頁、欄位排序、清除條件、讀取失敗、查看訂單 |

<a id="step-10-之後可以考慮"></a>

#### 之後可以考慮

- **三個列表的 URL 條件處理很像**（`first`、`positiveInt`、省略預設值）：目前各自寫在 `query.ts`，之後如果再多一個列表，可以抽出共用的工具
- **訂單列表顯示 email**：order-service 只回 `userId`；可以批次查使用者，或在訂單列表上提供「查看使用者」

<a id="step-10-最終檢查"></a>

### 最終檢查

vitest 254 個通過（新增 11 個）、`npm run build`（含 `vue-tsc`）通過、eslint、oxlint、prettier 都沒有問題。

---

<a id="step-11"></a>

## vue-management Step 11：部署

照 [vue-customer Step 11](../vue-customer/README.md#step-11) 的做法（Dockerfile、nginx、K8s、安全標頭的決定與理由都寫在那裡，這裡只記錄差異與驗證）。檔案在 `frontend/vue-management/`：`Dockerfile`、`.dockerignore`、`.env.production`、`nginx/`、`k8s/`。

<a id="step-11-和-vue-customer-的差異"></a>

### 和 vue-customer 的差異

- **`.env.production`**：vue-management 同樣沒有這個檔案，production build 的 `VITE_API_BASE` 會是 `undefined`，所有請求會打到 `/users` 而不是 `/api/v1/users`。已補上
- **CSP 的 `connect-src` 要允許物件儲存的來源**：後台上傳圖片是瀏覽器用 presigned URL **直接 PUT 到物件儲存**，不經過 nginx，所以除了 `img-src`，`connect-src` 也要加 `STORAGE_PUBLIC_ORIGIN`（vue-customer 不上傳，不需要）。實測簽章上傳網址的主機是 `localhost:8333`，和 `STORAGE_PUBLIC_ORIGIN` 一致；**後端的儲存網址改了（例如換成 CDN／儲存網域），ConfigMap 的這個值要跟著改**，否則上傳會被 CSP 擋掉
- 打包內容檢查：`index.html` 沒有 inline script／style、沒有 `eval`、執行期沒有對外部網址的請求（只有文件連結與 XML 命名空間字串）；應用程式碼沒有 `blob:`／`data:`、沒有 `fetch`／`XMLHttpRequest`／iframe
- 後台是 Element Plus（按需引入、樣式被抽成 CSS 檔），沒有執行期注入 `<style>`；`style-src 'self'` 理論上可行，但**沒有在瀏覽器驗證過**。如果實際發現某個元件被 CSP 擋掉，先在瀏覽器確認是哪個，再決定要不要放寬 `style-src`
- 資源名稱、映像檔名稱（`vue-management:0.1.0`）、ConfigMap／Deployment／Service 名稱都是 `vue-management`，可以和 `vue-customer` 部署在同一個 namespace
- 後台 API 的 JSON 很小，圖片不經過 nginx，所以 `client_max_body_size 1m` 夠用（註解已改成說明這點）

<a id="step-11-驗證"></a>

### 驗證

**1. 本機 Docker**（`docker run -e API_GATEWAY_URL=http://host.docker.internal:8080`，對真的 Gateway）：
- 以非 root（uid 101）執行
- `/`、`/login`、`/products`、`/products/new`、`/orders/abc`、`/no/such` 都回 200 `text/html`（SPA fallback）
- 每個回應都有五個安全標頭，CSP 的 `img-src`、`connect-src` 都含 `http://localhost:8333`
- 靜態資源只有一個 `Cache-Control: public, max-age=31536000, immutable`；CSS 回 `text/css`
- 管理員經 nginx 登入、查 `/users`、`/orders`、草稿商品都成功；匿名查 `/users` 回 401；`/api/actuator/health` 回 404
- vue-management 的 261 個測試通過

**2. kind**（計畫要求：登入 → 建立商品 → 上傳主圖 → 上架）。叢集建立方式與 cgroup v1 的處理、驗證用的 `ExternalName` Service 見 [vue-customer Step 11](../vue-customer/README.md#step-11-在-kind-上驗證計畫要求的)。這次先建好 `api-gateway` 的 Service 再部署，Pod 一次就 Ready，沒有出現「找不到上游主機」的重啟。經 `kubectl port-forward svc/vue-management` 從叢集外透過 K8s 裡的 nginx，全部成功：
1. 管理員登入，`roles: ['ADMIN']`；匿名新增商品 → 401
2. 建立商品（`DRAFT`）
3. 還沒有主圖就上架 → 被擋（400 `PRODUCT_PRIMARY_IMAGE_REQUIRED`）
4. 申請上傳網址（經 nginx）→ **直接 PUT 到物件儲存**（不經 nginx）→ 登記圖片，`primary=true`
5. 上架 → `ACTIVE`，設定庫存
6. 匿名在商店搜尋，看得到這件商品、原價與主圖

驗證用的商品是目錄裡合理的新品（`HAT-002`「保暖針織毛帽」，木棉 KAPOK，420 元、原價 520），不是測試垃圾，留在資料庫。驗證完叢集已刪除。

<a id="step-11-還沒做的"></a>

### 還沒做的

- **CSP 已在 [Step 12](#step-12) 於真實 Chrome 驗證**：對著這個 nginx 映像檔跑 E2E，Element Plus 的元件、圖片直傳（`connect-src`）與圖片顯示（`img-src`）都沒有被擋；原本擔心的 `style-src 'self'` 沒有問題
- 後端的 Dockerfile／K8s 設定與 `NetworkPolicy` 見 [後端部署](../backend-deployment-implementation-plan.md)
- Ingress、TLS、HSTS、映像檔倉庫與 CI；和商店一起規劃網域（例如 `shop.example.com`、`admin.example.com`）
- 後台不應該對外公開給所有人：之後在 Ingress 層限制來源（例如內網、VPN 或 IP allowlist），前端的登入檢查只是畫面，真正的權限在後端

---

<a id="step-12"></a>

## vue-management Step 12：Playwright E2E

對著**真的後端**，在**真的瀏覽器（Chrome）**裡操作後台：登入 → 建立商品 → 上傳主圖 → 上架。檔案在 `frontend/vue-management/`：`playwright.config.ts`、`e2e/`、`tsconfig.e2e.json`。

<a id="step-12-怎麼跑"></a>

### 怎麼跑

```bash
# 1. 先啟動整套後端（Gateway :8080 與各服務、資料庫、物件儲存）
# 2. 管理員帳密用環境變數傳入，不寫進 repo（本機開發用的值在 backend/spring-user-service/secrets/dev.env）
cd frontend/vue-management
export E2E_ADMIN_EMAIL=...  E2E_ADMIN_PASSWORD=...
npm run test:e2e
```

- 預設自己啟動 Vite dev server（**:5183**，`--strictPort`），`/api` 由 Vite proxy 轉給 Gateway。用專屬連接埠是因為平常開發的 :5173 可能被別的程式佔用（這次就遇到佔用的程式回 404），不要誤連
- 預設用本機安裝的 Chrome（`channel: 'chrome'`），不用另外下載瀏覽器；CI 上設 `E2E_CHANNEL=` 改用 Playwright 自己的 Chromium（先 `npx playwright install chromium`）
- **對著 nginx 映像檔跑（有 CSP 與安全標頭的版本）**：

  ```bash
  docker run -d --name vm-e2e -p 8092:8080 -e API_GATEWAY_URL=http://host.docker.internal:8080 vue-management:0.1.0
  E2E_BASE_URL=http://localhost:8092 npm run test:e2e
  ```

  這時 `e2e/support/test.ts` 的檢查才有意義：每個測試結束時，頁面不能有 CSP 違規（`Content Security Policy`、`Refused to ...`）或未捕捉的錯誤

<a id="step-12-測試內容"></a>

### 測試內容

| 檔案 | 測試 |
| ---- | ---- |
| `auth.spec.ts` | 沒有登入進入後台頁面 → 導到登入頁並帶 `redirect`；帳密錯誤顯示「Email 或密碼錯誤」；管理員登入後回到原本要去的頁面；一般顧客登入被拒（「此帳號沒有後台管理權限」）；重新整理後用 refresh token 恢復登入 |
| `product-flow.spec.ts` | **計畫的主流程**：建立商品（草稿，分類用樹狀選單選）→ 還沒有主圖就上架被擋（「上架前要先設定主圖」）→ 上傳主圖（瀏覽器直接上傳到物件儲存，圖片真的載入）→ 上架 → 後端確認 `ACTIVE`、商店（匿名）看得到且有主圖網址 → 下架後商店看不到 |

<a id="step-12-設計決定與遇到的問題"></a>

### 設計決定與遇到的問題

- **登入限流**：Gateway 的登入限流依來源 IP（約每分鐘 10 次，每 6 秒補 1 次）。一開始每個測試各登入一次，連跑幾次就會 429，失敗訊息還五花八門（有時是找不到元素、有時是訊息不對）。現在一次執行約 5 次登入；API 登入遇到 429 會等 7 秒重試
- **refresh token 是單次使用的**（每次 refresh 都會換新、舊的立即失效）。一度想用一個 setup 專案登入一次、存 `storageState` 給所有測試共用，結果「重新整理後仍然登入」的測試先用掉那個 token，主流程再用就失效、被導回登入頁。改成：每個需要登入狀態的測試，各自用 API 登入拿到自己的 refresh token，放進瀏覽器的 localStorage（`startSignedIn`）。注入的腳本每次頁面載入都會執行，所以只在 localStorage 還沒有時才設定，否則重新整理會把已經輪替的新 token 蓋回舊的
- **等頁面載入完再填表單**：`/products/new` 在分類等資料載入後會重設表單，測試填得比載入快，SKU 欄位會被清空。真人來不及打字，所以不是應用程式的缺陷，測試等 `networkidle` 並確認欄位值有保留
- **不留垃圾資料**（商品沒有刪除 API）：
  - E2E 專用分類 `e2e-playwright` 每次共用同一個，測試開始時確保存在且啟用，結束後**停用**，商店的分類列不會出現
  - 商品結束時**下架**（不是封存，封存不可逆），商店看不到；但後台商品列表每次執行會多一件「E2E 自動化測試商品」（狀態：已下架），可以用狀態篩選過濾
  - 一般顧客帳號固定 `e2e-customer@example.com`（已註冊回 409 當成成功），不會每次多一個
- 型別檢查：`tsconfig.e2e.json`（含 DOM 型別，因為 `page.evaluate` 的回呼在瀏覽器裡執行）加進 `vue-tsc --build` 的參考；原本 `tsconfig.node.json` 只涵蓋 `playwright.config.*`，不包含 `e2e/`

<a id="step-12-驗證"></a>

### 驗證

- Vite dev server 上連續 **3 次**全部通過（6 個測試，每次約 15 秒）
- **對著 nginx 映像檔（嚴格 CSP）通過**：6 個測試全過、沒有 CSP 違規。包含 Element Plus 的樹狀選單與訊息提示、**圖片直傳物件儲存（`connect-src`）與圖片顯示（`img-src`）**。這是 [vue-management Step 11](#step-11) 留下的「CSP 沒有在瀏覽器裡驗證」缺口
- 確認 CSP 偵測有效（不是偵測不到才看起來沒違規）：故意在頁面塞入內嵌腳本與外部圖片，瀏覽器確實擋下並回報 `Executing inline script violates the following Content Security Policy directive 'script-src 'self''`，訊息符合偵測的規則（確認後這個探測測試已刪除）
- 既有檢查不受影響：261 個單元測試、build、oxlint、eslint、`vue-tsc --build`（含 e2e）、prettier 全過

<a id="step-12-還沒做的"></a>

### 還沒做的

- 沒有放進 CI（需要整套後端與資料庫）；Playwright 的報告與追蹤檔（`test-results/`）已加進 `.gitignore`
- 只涵蓋計畫的主流程與登入；分類、庫存、訂單、使用者列表的畫面沒有 E2E（單元測試與先前對真實後端的 API 檢查有涵蓋）
- 只用 Chrome 一個瀏覽器

---

<a id="backend-integration"></a>

## vue-management 接上後端的實測

> 日期：2026-11-10（Step 10 完成後、Step 11 之前）

Step 3–10 寫完時都只用 MSW 驗證過。這次把整套後端在本機跑起來，照前端各頁面的呼叫順序打一遍真正的 API。

<a id="backend-integration-環境"></a>

### 環境

| 項目 | 做法 |
| ---- | ---- |
| 基礎設施 | `infra/docker-compose.yml`：5 個 Postgres、3 個 Redis、SeaweedFS |
| 後端 | `./mvnw package -DskipTests` 後，6 個服務各自 `java -jar` 在本機執行（8080–8085），`/actuator/health` 都是 200 |
| user-service | `--spring.profiles.active=dev`，由 `BootstrapAdminInitializer` 建立 ADMIN |
| 前端 | `npm run dev`（`http://localhost:5173`），API 經由 Vite proxy 轉給 Gateway |

- 測試用的 ADMIN 另外建一個 `claude-admin@example.com`（用環境變數 `BOOTSTRAP_ADMIN_EMAIL`、`BOOTSTRAP_ADMIN_PASSWORD` 覆蓋 `secrets/dev.env`），不使用個人的管理員帳號
- `object-storage-init` 又遇到 Step 0 記錄的 `fail to dial`（filer 還沒準備好就執行，仍以 0 結束），`docker compose up object-storage-init` 重跑一次就好

<a id="backend-integration-方式"></a>

### 方式

Chrome 擴充功能沒有連上，這次**沒有操作畫面**，改用腳本照前端的呼叫順序打 API：

- 我們的 API 全部經過 Vite proxy（`http://localhost:5173/api/v1/...`），和瀏覽器走同一條路
- 每個請求帶 `Origin: http://localhost:5173`
- 圖片上傳和瀏覽器一樣直接 PUT 到 presigned URL（SeaweedFS `:8333`），先送一次 CORS 預檢（`OPTIONS`，`Access-Control-Request-Headers: content-type`）；PUT 時帶上 `requiredHeaders` 裡的 `Content-Type`，`Content-Length` 不手動設定，交給 HTTP 用戶端依 body 自動帶（和瀏覽器一致）
- 測試資料以 `e2e-<timestamp>` 為前綴；這次是 `e2e-1794284652`

<a id="backend-integration-結果58-項全部符合預期"></a>

### 結果：58 項，全部符合預期

| 範圍 | 確認的行為 |
| ---- | ---------- |
| 登入（Step 3） | ADMIN 登入、`/users/me` 有 `ADMIN`；無效 token → 401 `UNAUTHENTICATED`（前端據此 refresh）；refresh 會輪替 token；帳密錯誤 → `INVALID_CREDENTIALS`；登出 204，之後 refresh 被拒 |
| 分類（Step 5） | 新增、子分類；slug 重複 → `CATEGORY_SLUG_DUPLICATED`；帶 `version` 停用；舊 `version` → `VERSION_CONFLICT`；後台列表有停用的分類，公開的分類樹沒有；刪除有子分類的 → `DATA_CONFLICT` |
| 商品（Step 6） | 新增為 `DRAFT`；新增時 SKU 重複 → `DATA_CONFLICT`；沒有分類上架 → `PRODUCT_ACTIVE_CATEGORY_REQUIRED`；沒有主圖上架 → `PRODUCT_PRIMARY_IMAGE_REQUIRED`；關鍵字、排序；封存後修改 → `PRODUCT_ARCHIVED` |
| 圖片（Step 7） | 申請上傳網址；**SeaweedFS 預檢與 PUT 都回 `Access-Control-Allow-Origin: *`**；**不手動帶 `Content-Length`，簽章仍通過**；登記後第一張是主圖；公開網址可以讀到圖片；GIF → `IMAGE_CONTENT_TYPE_NOT_ALLOWED`；排序、設主圖、補齊條件後上架成功；上架中刪主圖 → `PRODUCT_IMAGE_PRIMARY_UNDELETABLE` |
| 庫存（Step 8） | 還沒建立 → 404 `STOCK_NOT_FOUND`；第一次設定 201、之後 200 |
| 訂單（Step 9） | 顧客下單後庫存被預扣；後台取消待付款訂單，庫存釋放；未付款出貨 → `ORDER_NOT_SHIPPABLE`；模擬付款成功後變成 `PAID`；已付款取消 → `ORDER_NOT_CANCELLABLE`；出貨、完成並記錄時間；依使用者與狀態篩選；`userId` 不是 UUID → 400（前端搜尋前就擋下） |
| 使用者（Step 10） | 關鍵字、角色篩選與排序；顧客呼叫 → 403 |

<a id="backend-integration-一開始判定失敗實際上正確的一項"></a>

#### 一開始判定失敗、實際上正確的一項

- 腳本預期「剛換過的舊 refresh token 立刻再用會被拒」，實際上回了新的 token
- 原因是 user-service 的 `refresh-token-reuse-grace: 10s`：換新後 10 秒內舊的 token 仍可使用，給同時送出 refresh 的情況（例如兩個分頁）用。這是設計好的行為，腳本的預期錯了
- 對前端是好事：Step 3 的「同時只發一次 refresh」只能管同一個分頁，多個分頁同時 refresh 時靠這個寬限期

<a id="backend-integration-還沒驗證的"></a>

### 還沒驗證的

- **畫面本身**：版面、表單互動、拖曳排序、`ElMessage`、窄螢幕等，要在瀏覽器實際操作（Chrome 擴充功能連上後，或手動用 `http://localhost:5173` 操作）
- **瀏覽器的 CORS 行為**：腳本確認了回應的 CORS header，但沒有真的由瀏覽器執行同源政策檢查；header 正確時瀏覽器應該會放行

<a id="backend-integration-留在本機資料庫的資料"></a>

### 留在本機資料庫的資料

- ADMIN 帳號 `claude-admin@example.com`
- 名稱以 `e2e-1794284652` 開頭的分類（含一個停用的子分類）、一個已封存的商品（2 張圖片、庫存 20）、一個顧客帳號 `e2e-1794284652@example.com`、兩張訂單（一張已取消、一張已完成）
