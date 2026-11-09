# vue-management 後台前端實作步驟

> 建立日期：2026-11-09
> 分支：`feature/vue-management`
> 位置：`frontend/vue-management`
> 狀態：規劃中，尚未開始

## 進度

| Step | 內容 | 狀態 |
|---|---|---|
| 0 | 後端前置問題：SeaweedFS CORS、決定要補哪些後台 API | 未開始 |
| 1 | 建立專案、Element Plus、Vite proxy | 未開始 |
| 2 | HTTP 層：axios 實例、`ApiError` | 未開始 |
| 3 | 登入：token store、refresh（同時只發一次）、登出、路由守衛 | 未開始 |
| 4 | AdminLayout、選單、403 / 404 | 未開始 |
| 5 | 分類管理 | 未開始 |
| 6 | 商品管理 | 未開始 |
| 7 | 商品圖片 | 未開始 |
| 8 | 庫存（放在商品編輯頁） | 未開始 |
| 9 | 訂單管理 | 未開始 |
| 10 | 使用者列表 | 未開始 |
| 11 | 部署：Dockerfile、nginx、K8s | 未開始 |
| 12 | Playwright E2E（之後再做） | 未開始 |

每一步都附測試，測試通過後單獨 commit。

---

## 後端前置問題

| 問題 | 現況 | 決定 |
|---|---|---|
| CORS | Gateway 沒有設定 CORS | 開發時用 **Vite proxy** 把 `/api` 轉到 `localhost:8080`；正式環境由 nginx 轉發，維持同一個網域，後端不設 CORS |
| 圖片直傳 | presigned URL 讓瀏覽器直接 PUT 到 SeaweedFS `:8333`，是跨網域請求 | **SeaweedFS 要設定 CORS**。簽章包含 Host，不能靠 proxy 改 Host 繞過。Step 7 之前先實測 |
| 庫存列表 | 只有 `GET /api/v1/inventory/{sku}` | 雛型階段在商品編輯頁依 SKU 查詢；需要總覽頁時再補列表 API |
| 訂單出貨、完成 | 沒有 API，`SHIPPED`、`COMPLETED` 無法到達 | 待決定；補的話放在 Step 9 之前 |
| 付款列表 | 沒有 | 雛型階段不做 |
| 使用者角色 | 無法修改 | 雛型階段只做列表 |

## 技術選型

| 項目 | 選擇 | 理由 |
|---|---|---|
| 框架 | Vue 3 + Vite + TypeScript（`<script setup>`） | 主流做法，型別可以對照後端 DTO |
| 路由與狀態 | Vue Router、Pinia | 官方方案 |
| UI 元件 | Element Plus | 表格、表單、樹狀、上傳元件齊全 |
| HTTP | axios | 用攔截器處理 token 與 refresh 最直接 |
| 測試 | Vitest + Vue Test Utils + MSW | 不用啟動後端就能測錯誤與 401 流程 |
| E2E | Playwright（Step 12） | 搭配 compose 跑一次完整流程 |
| API 型別 | 先手寫在各功能的 `types.ts` | 之後可以考慮用 springdoc + `openapi-typescript` 產生 |

## 目錄結構

依功能分資料夾，每個功能自己包含 api、types、views、components。

```
frontend/vue-management/
├── .env.development        VITE_API_BASE=/api/v1
├── vite.config.ts          proxy: /api → http://localhost:8080
├── Dockerfile / nginx.conf 正式環境：靜態檔 + /api 轉發到 api-gateway
└── src/
    ├── main.ts
    ├── app/                router（含守衛）、全域 plugin 註冊
    ├── api/
    │   ├── http.ts         axios 實例、攔截器（帶 token、401 → refresh）
    │   └── errors.ts       ProblemDetail → ApiError { status, code, detail }
    ├── features/
    │   ├── auth/           登入頁、token store、refresh
    │   ├── categories/
    │   ├── products/       含 images/、庫存區塊
    │   ├── orders/
    │   └── users/
    ├── layouts/            AdminLayout（側邊欄、頂部列、麵包屑）
    ├── components/         跨功能共用：分頁表格、確認按鈕、狀態 Tag
    ├── composables/        usePagedQuery（列表條件同步到 URL query）、useConfirm
    └── views/              403、404
```

## 關鍵設計

- **錯誤處理集中在一處**：後端統一回 ProblemDetail 加上 `code`（見 [backend-common-implementation-plan.md](backend-common-implementation-plan.md)）。`errors.ts` 轉成 `ApiError`，畫面依 `code` 顯示對應訊息，例如 `PRODUCT_ARCHIVED`、`IMAGE_TOO_LARGE`
- **refresh 同時只發一次**：後端的 refresh token 每次都會換新，舊的立即失效。多個請求同時收到 401 時要共用同一個 refresh Promise，否則後面的請求會拿已經失效的 token 去換。refresh 本身失敗就清除登入狀態，導回登入頁
- **token 存放**：access token 只放記憶體（Pinia）；refresh token 雛型階段放 localStorage，有 XSS 風險，之後考慮改成 httpOnly cookie（需要後端配合）
- **角色檢查只影響畫面**：登入後用 `GET /api/v1/users/me` 確認是 `ADMIN`，不是就登出並提示。真正的權限檢查仍由 Gateway 與 `@RequireRoles` 負責
- **列表條件放在 URL query**：重新整理、分享連結都能保留篩選與分頁

## 會用到的 API

詳細規格見 [microservice-architecture.md](../architecture/microservice-architecture.md)。

| 功能 | API |
|---|---|
| 登入 | `POST /auth/login`、`POST /auth/refresh`、`POST /auth/logout`、`GET /users/me` |
| 分類 | `GET /categories/tree`、`GET /categories/{id}`、`POST /categories`、`PUT /categories/{id}`、`DELETE /categories/{id}` |
| 商品 | `GET /products`、`GET /products/{id}`、`POST /products`、`PUT /products/{id}`、`POST /products/{id}/publish`、`/unpublish`、`/archive` |
| 商品圖片 | `POST /products/{id}/images/upload-url`、`POST /products/{id}/images`、`GET /products/{id}/images`、`PUT .../{imageId}`、`DELETE .../{imageId}`、`POST .../{imageId}/primary`、`PUT .../order` |
| 庫存 | `GET /inventory/{sku}`、`PUT /inventory/{sku}` |
| 訂單 | `GET /orders`（ADMIN 可查全部）、`GET /orders/{id}`、`POST /orders/{id}/cancel` |
| 使用者 | `GET /users` |

---

## Step 0：後端前置問題

- 實測 SeaweedFS 的 CORS：從 `http://localhost:5173` 對 presigned URL 發 PUT，確認 preflight 通過；不通過就在 compose 的初始化加入 CORS 設定
- 決定訂單出貨、完成 API 要不要補；要補就照後端流程（整合測試、單獨 commit）

## Step 1：建立專案

- `npm create vue@latest`：TypeScript、Router、Pinia、Vitest、ESLint、Prettier
- 加入 Element Plus（按需引入）
- `vite.config.ts` 設定 proxy：`/api` → `http://localhost:8080`

**測試**：`npm run build`、`npm run test:unit` 通過；冒煙測試能掛載 App。

## Step 2：HTTP 層

- `http.ts`：`baseURL` 用 `VITE_API_BASE`、timeout
- `errors.ts`：把 ProblemDetail 轉成 `ApiError`；網路錯誤、非 JSON 回應也要能轉換

**測試**（MSW）：4xx ProblemDetail → 正確的 `status`、`code`；5xx、網路中斷 → 一般錯誤。

## Step 3：登入

- 登入頁、`useAuthStore`（token、目前使用者）
- request 攔截器帶 `Authorization: Bearer`；response 攔截器遇到 401 → refresh → 重送原請求
- 登出：呼叫 `/auth/logout` 後清除狀態
- 路由守衛：未登入 → 登入頁（帶 redirect）；非 ADMIN → 拒絕登入

**測試**：同時兩個請求收到 401 只發一次 refresh，兩個都成功重送；refresh 失敗導回登入頁；非 ADMIN 登入被拒；登入限流（429）顯示提示。

## Step 4：Layout

- AdminLayout：側邊選單、頂部列（使用者名稱、登出）、麵包屑
- 403、404 頁面

**測試**：未登入進入後台頁被導到登入頁；不存在的路徑顯示 404。

## Step 5：分類管理

- 樹狀顯示、新增（可選父分類）、修改、刪除

**測試**：表單驗證；刪除失敗時依錯誤碼顯示訊息。

## Step 6：商品管理

- 列表：篩選、分頁，條件同步到 URL query
- 新增、編輯表單；上架、下架、封存

**測試**：狀態按鈕依目前狀態啟用或停用；封存後不能編輯；上架失敗（例如沒有主圖）顯示錯誤碼。

## Step 7：商品圖片

- 流程：選檔 → 前端先檢查類型與大小 → `upload-url` → 帶 `requiredHeaders` PUT → 登記
- 設定主圖、拖曳排序、刪除

**測試**：類型或大小不符時不發請求；PUT 失敗不會登記；排序送出的順序正確。

## Step 8：庫存

- 放在商品編輯頁：依商品 SKU 查詢與調整庫存

**測試**：調整成功後重新讀取；SKU 尚未建立庫存時的顯示。

## Step 9：訂單管理

- 列表（依狀態篩選、分頁）、詳情（商品快照、金額、狀態）、取消
- Step 0 補了 API 的話，再加出貨、完成

**測試**：只有 `PENDING_PAYMENT` 能取消；取消失敗顯示錯誤碼。

## Step 10：使用者列表

**測試**：分頁、篩選條件同步到 URL query。

## Step 11：部署

- 多階段 Dockerfile：node build → nginx
- nginx：SPA fallback（`try_files ... /index.html`），`/api` 轉發到 `http://api-gateway:8080`
- K8s Deployment、Service

**驗證**：在 kind 上實際登入並跑一次建立商品 → 上傳主圖 → 上架。

## Step 12：E2E（之後再做）

Playwright 搭配 compose：登入 → 建立商品 → 上傳主圖 → 上架。
