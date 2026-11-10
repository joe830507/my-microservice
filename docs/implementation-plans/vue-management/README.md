# vue-management 後台前端實作步驟

> 建立日期：2026-11-09
> 分支：`feature/vue-management`
> 位置：`frontend/vue-management`
> 狀態：進行中（Step 4 完成）

## 進度

| Step | 內容                                                       | 狀態                                               |
| ---- | ---------------------------------------------------------- | -------------------------------------------------- |
| [0](step-00-backend-prereqs.md) | 後端前置問題：SeaweedFS CORS、決定要補哪些後台 API         | 完成：CORS 不用改；補上訂單 `ship`、`complete` |
| [1](step-01-project-setup.md) | 建立專案、Element Plus、Vite proxy                         | 完成                                               |
| [2](step-02-http-layer.md) | HTTP 層：axios 實例、`ApiError`                          | 完成                                               |
| [3](step-03-login.md) | 登入：token store、refresh（同時只發一次）、登出、路由守衛 | 完成                                               |
| [4](step-04-layout.md) | AdminLayout、選單、403 / 404                               | 完成                                               |
| 5    | 分類管理                                                   | 未開始                                             |
| 6    | 商品管理                                                   | 未開始                                             |
| 7    | 商品圖片                                                   | 未開始                                             |
| 8    | 庫存（放在商品編輯頁）                                     | 未開始                                             |
| 9    | 訂單管理                                                   | 未開始                                             |
| 10   | 使用者列表                                                 | 未開始                                             |
| 11   | 部署：Dockerfile、nginx、K8s                               | 未開始                                             |
| 12   | Playwright E2E（之後再做）                                 | 未開始                                             |

每一步都附測試，測試通過後單獨 commit。

**文件的組織方式**：這份 README 放整體規劃（技術選型、目錄結構、關鍵設計、API 清單）與還沒開始的 step。開始一個 step 時，把它的段落搬到 `step-NN-<名稱>.md`，之後的檢查結果、遇到的問題都記在那個檔案，並在進度表加上連結。

---

## 後端前置問題

| 問題           | 現況                                                                | 決定                                                                                                                    |
| -------------- | ------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| CORS           | Gateway 沒有設定 CORS                                               | 開發時用 **Vite proxy** 把 `/api` 轉到 `localhost:8080`；正式環境由 nginx 轉發，維持同一個網域，後端不設 CORS |
| 圖片直傳       | presigned URL 讓瀏覽器直接 PUT 到 SeaweedFS `:8333`，是跨網域請求 | 已實測：SeaweedFS 預設允許所有來源，不用改設定（見 [Step 0](step-00-backend-prereqs.md)）。簽章包含 Host，不能靠 proxy 改 Host 繞過                   |
| 庫存列表       | 只有 `GET /api/v1/inventory/{sku}`                                | 雛型階段在商品編輯頁依 SKU 查詢；需要總覽頁時再補列表 API                                                               |
| 訂單出貨、完成 | 沒有 API，`SHIPPED`、`COMPLETED` 無法到達                       | 已補：`POST /orders/{id}/ship`、`POST /orders/{id}/complete`（見 [Step 0](step-00-backend-prereqs.md)）                                           |
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
    ├── composables/        usePagedQuery（列表條件同步到 URL query）、useConfirm
    └── views/              不屬於任何功能的頁面：首頁、403、404
```

## 關鍵設計

- **錯誤處理集中在一處**：後端統一回 ProblemDetail 加上 `code`（見 [backend-common-implementation-plan.md](../backend-common-implementation-plan.md)）。`errors.ts` 轉成 `ApiError`，畫面依 `code` 顯示對應訊息，例如 `PRODUCT_ARCHIVED`、`IMAGE_TOO_LARGE`
- **refresh 同時只發一次**：後端的 refresh token 每次都會換新，舊的立即失效。多個請求同時收到 401 時要共用同一個 refresh Promise，否則後面的請求會拿已經失效的 token 去換。refresh 本身失敗就清除登入狀態，導回登入頁
- **token 存放**：access token 只放記憶體（Pinia）；refresh token 雛型階段放 localStorage，有 XSS 風險，之後考慮改成 httpOnly cookie（需要後端配合）
- **角色檢查只影響畫面**：登入後用 `GET /api/v1/users/me` 確認是 `ADMIN`，不是就登出並提示。真正的權限檢查仍由 Gateway 與 `@RequireRoles` 負責
- **列表條件放在 URL query**：重新整理、分享連結都能保留篩選與分頁

## 會用到的 API

詳細規格見 [microservice-architecture.md](../../architecture/microservice-architecture.md)。

| 功能     | API                                                                                                                                                                                                          |
| -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 登入     | `POST /auth/login`、`POST /auth/refresh`、`POST /auth/logout`、`GET /users/me`                                                                                                                       |
| 分類     | `GET /categories/tree`、`GET /categories/{id}`、`POST /categories`、`PUT /categories/{id}`、`DELETE /categories/{id}`                                                                              |
| 商品     | `GET /products`、`GET /products/{id}`、`POST /products`、`PUT /products/{id}`、`POST /products/{id}/publish`、`/unpublish`、`/archive`                                                         |
| 商品圖片 | `POST /products/{id}/images/upload-url`、`POST /products/{id}/images`、`GET /products/{id}/images`、`PUT .../{imageId}`、`DELETE .../{imageId}`、`POST .../{imageId}/primary`、`PUT .../order` |
| 庫存     | `GET /inventory/{sku}`、`PUT /inventory/{sku}`                                                                                                                                                           |
| 訂單     | `GET /orders`（ADMIN 可查全部）、`GET /orders/{id}`、`POST /orders/{id}/cancel`、`/ship`、`/complete`                                                                                              |
| 使用者   | `GET /users`                                                                                                                                                                                               |

---

## 還沒開始的 step

### Step 5：分類管理

- 樹狀顯示、新增（可選父分類）、修改、刪除

**測試**：表單驗證；刪除失敗時依錯誤碼顯示訊息。

### Step 6：商品管理

- 列表：篩選、分頁，條件同步到 URL query
- 新增、編輯表單；上架、下架、封存

**測試**：狀態按鈕依目前狀態啟用或停用；封存後不能編輯；上架失敗（例如沒有主圖）顯示錯誤碼。

### Step 7：商品圖片

- 流程：選檔 → 前端先檢查類型與大小 → `upload-url` → 帶 `requiredHeaders` PUT → 登記
- 設定主圖、拖曳排序、刪除

**測試**：類型或大小不符時不發請求；PUT 失敗不會登記；排序送出的順序正確。

### Step 8：庫存

- 放在商品編輯頁：依商品 SKU 查詢與調整庫存

**測試**：調整成功後重新讀取；SKU 尚未建立庫存時的顯示。

### Step 9：訂單管理

- 列表（依狀態篩選、分頁）、詳情（商品快照、金額、狀態、各階段時間）、取消、出貨、完成

**測試**：按鈕依狀態顯示（`PENDING_PAYMENT` 取消、`PAID` 出貨、`SHIPPED` 完成）；失敗時顯示錯誤碼。

### Step 10：使用者列表

**測試**：分頁、篩選條件同步到 URL query。

### Step 11：部署

- 多階段 Dockerfile：node build → nginx
- nginx：SPA fallback（`try_files ... /index.html`），`/api` 轉發到 `http://api-gateway:8080`
- K8s Deployment、Service

**驗證**：在 kind 上實際登入並跑一次建立商品 → 上傳主圖 → 上架。

### Step 12：E2E（之後再做）

Playwright 搭配 compose：登入 → 建立商品 → 上傳主圖 → 上架。
