# vue-customer 前台（商店）實作步驟

> 建立日期：2026-11-10
> 分支：之後另開 `feature/vue-customer`
> 位置：`frontend/vue-customer`（目前只有 `.gitkeep`）
> 狀態：進行中（Step 0–10 完成）

顧客用的商店前台：瀏覽商品、購物車、註冊登入、下單、付款（模擬金流）、查詢自己的訂單。後台是 [vue-management](../vue-management/README.md)。

## 進度

| Step | 內容                                                         | 狀態   |
| ---- | ------------------------------------------------------------ | ------ |
| [0](step-00-backend-prereqs.md) | 後端前置問題：公開商品 API 只回上架中、列表帶主圖、分類含子分類 | 完成   |
| [1](step-01-project-setup.md) | 建立專案、Vite proxy、版面骨架                               | 完成   |
| [2](step-02-http-layer.md) | HTTP 層與登入狀態（沿用後台的做法）                          | 完成   |
| [3](step-03-auth-pages.md) | 註冊、登入、登出                                             | 完成   |
| [4](step-04-product-list.md) | 商品列表：分類、搜尋、篩選、排序、分頁                       | 完成   |
| [5](step-05-product-detail.md) | 商品詳情：圖片、價格、庫存、加入購物車                       | 完成   |
| [6](step-06-cart.md) | 購物車（存在瀏覽器）                                         | 完成   |
| [7](step-07-checkout.md) | 結帳、建立訂單                                               | 完成   |
| [8](step-08-payment.md) | 付款（模擬金流）                                             | 完成   |
| [9](step-09-my-orders.md) | 我的訂單：列表、詳情、取消、繼續付款                         | 完成   |
| [10](step-10-account.md) | 帳號頁：個人資料、登出所有裝置                               | 完成   |
| 11   | 部署：Dockerfile、nginx、K8s                                 | 未開始 |
| 12   | Playwright E2E（之後再做）                                   | 未開始 |

每一步都附測試，測試通過後單獨 commit。文件的組織方式和 vue-management 一樣：開始一個 step 時，把它的段落搬到 `step-NN-<名稱>.md`，結果與遇到的問題記在那個檔案。

---

## 後端前置問題

| 問題 | 現況 | 決定 |
| ---- | ---- | ---- |
| 公開商品 API 回傳所有狀態 | `GET /products`、`/products/{id}`、`/products/sku/{sku}`、`/products/{id}/images` 公開，草稿、已下架、已封存都看得到（vue-management Step 6 發現） | **已修正（[Step 0](step-00-backend-prereqs.md)）**：不是 ADMIN 的請求只回 `ACTIVE`（列表自動加上條件；單筆查非上架中的回 404 `PRODUCT_NOT_FOUND`）；ADMIN 照舊看全部，後台不用改 |
| 商品列表沒有圖片 | `ProductListItem` 沒有圖片欄位，商品格狀列表要對每個商品再查一次圖片 | **已補上（[Step 0](step-00-backend-prereqs.md)）**：列表項目加 `primaryImageUrl`（沒有主圖為 `null`），一次查出這一頁商品的主圖 |
| 分類篩選不含子分類 | `categoryId` 只比對同一個分類；點「鞋子」看不到「靴子」底下的商品 | **已補上（[Step 0](step-00-backend-prereqs.md)）**：加 `includeSubcategories=true`，由 product-service 用分類樹展開子孫 id |
| 購物車 | 沒有購物車服務 | 雛型存在瀏覽器（localStorage），只存 `productId`、`qty`；價格與狀態每次用 `GET /products/batch` 重新查 |
| 重複下單 | `POST /orders` 沒有冪等 key，連點或網路重送會建立兩張訂單（各自預扣庫存） | 雛型由前端防止（送出中停用按鈕）；之後再考慮 `Idempotency-Key`，記在「之後再處理」 |
| 個人資料修改、改密碼、忘記密碼 | user-service 沒有這些 API | 雛型不做；帳號頁只顯示資料與「登出所有裝置」 |
| 圖片網址 | 公開網址直接指向物件儲存（本機 SeaweedFS `:8333`） | `<img>` 跨來源沒問題；正式環境換成 CDN 網域（設定 `STORAGE_PUBLIC_BASE_URL`） |
| CORS | Gateway 沒有設定 CORS | 和後台一樣：開發用 Vite proxy、正式環境由 nginx 轉發，同一個網域 |

後端修正照既有的流程：整合測試、單獨 commit。

## 技術選型

| 項目 | 選擇 | 理由 |
| ---- | ---- | ---- |
| 框架 | Vue 3 + Vite + TypeScript（`<script setup>`） | 和後台一致 |
| 路由與狀態 | Vue Router、Pinia | 同上；購物車也放 Pinia |
| UI | **不用元件庫**，自己寫元件與樣式（CSS 變數、mobile-first） | 店面要自己的視覺，而且大部分人用手機逛；Element Plus 的後台風格與體積不適合 |
| HTTP | axios | 沿用後台的 `http.ts`、`errors.ts`、401 → refresh 攔截器 |
| 測試 | Vitest + Vue Test Utils + MSW | 和後台一致 |
| E2E | Playwright（Step 12） | 從瀏覽商品一路到付款 |
| SEO、SSR | 不做（SPA） | 雛型階段；之後需要再評估 Nuxt |
| 和後台共用程式碼 | **先複製**，不抽共用套件 | 兩邊只共用 HTTP 層與登入邏輯，規模小；共用套件要改成 monorepo workspace，等真的重複多了再做 |

開發時 port 用 **5174**（後台是 5173），一樣 proxy `/api` 到 `http://localhost:8080`。

## 目錄結構

```
frontend/vue-customer/
├── .env.development        VITE_API_BASE=/api/v1
├── vite.config.ts          port 5174；proxy: /api → http://localhost:8080
├── Dockerfile / nginx.conf 正式環境：靜態檔 + /api 轉發到 api-gateway
└── src/
    ├── main.ts
    ├── router/             路由表、需要登入的守衛
    ├── api/                http.ts、errors.ts（從後台複製）
    ├── features/
    │   ├── auth/           登入、註冊、token store、refresh
    │   ├── catalog/        分類、商品列表、商品詳情
    │   ├── cart/           購物車 store（localStorage）、購物車頁
    │   ├── checkout/       結帳、付款
    │   ├── orders/         我的訂單
    │   └── account/        帳號頁
    ├── layouts/            StoreLayout（頂部列：分類、搜尋、購物車數量、帳號）、頁尾
    ├── components/         商品卡片、價格（含原價與折扣）、數量選擇、分頁
    ├── composables/        useRouteQuery（從後台複製）
    ├── styles/             設計 token（顏色、字級、間距）
    └── views/              首頁、404
```

## 關鍵設計

- **不用登入就能逛**：商品、分類、購物車都不需要登入；結帳、付款、我的訂單、帳號頁需要登入，未登入時導到登入頁並帶 `redirect`，登入後回到原頁面
- **購物車只存 id 與數量**：價格、名稱、狀態可能被後台改，存在瀏覽器的會過期。購物車頁與結帳頁用 `GET /products/batch` 重新查，下架或不存在的商品標成「無法購買」並排除在結帳之外；**實際金額以建單時的快照為準**
- **購物車不跟帳號綁定**：雛型存在瀏覽器，換裝置不會同步；登出時保留（同一台電腦的下一個人看得到，雛型可以接受，記在「之後再處理」）
- **下單流程**：
  1. 結帳頁確認商品與數量（重新查價格）→ `POST /orders`，狀態 `PENDING_PAYMENT`，庫存同時預扣
  2. 成功後把這些商品從購物車移除，進入付款頁
  3. `POST /payments`（同一張訂單有進行中的付款時回同一筆，重新整理頁面不會重複建立）
  4. 模擬金流：畫面提供「付款成功」「付款失敗」兩個按鈕，呼叫 `mock-callback`
  5. 成功後輪詢 `GET /orders/{id}` 到 `PAID`（付款通知 order-service 是非同步重試，可能晚幾秒）；失敗可以重新付款
- **付款期限**：建單 30 分鐘內沒付款會被自動取消（order-service 的 `payment-timeout`）。付款頁與訂單詳情顯示剩餘時間；過期後提示訂單已取消
- **建單失敗的處理**：`OUT_OF_STOCK`（庫存不足，指出是哪個商品）、`PRODUCT_NOT_AVAILABLE`（已下架）、`PRODUCT_SERVICE_UNAVAILABLE`、`INVENTORY_SERVICE_UNAVAILABLE`（稍後再試）；失敗時購物車不變
- **防止重複下單**：送出中停用按鈕；建單成功後用 `replace` 導到付款頁，按上一頁不會回到可以再送出的結帳頁
- **錯誤處理、登入狀態**：沿用後台的 `ApiError`、401 → refresh（同時只發一次）、refresh token 存 localStorage；不檢查角色（ADMIN 也能逛和下單）
- **列表條件放在 URL query**：分類、關鍵字、價格、排序、分頁都在網址上，分享連結、上一頁都能保留

## 會用到的 API

詳細規格見 [microservice-architecture.md](../../architecture/microservice-architecture.md)。

| 功能 | API |
| ---- | --- |
| 帳號 | `POST /auth/register`、`POST /auth/login`、`POST /auth/refresh`、`POST /auth/logout`、`GET /users/me`、`DELETE /users/me/sessions` |
| 分類 | `GET /categories/tree`、`GET /categories/slug/{slug}` |
| 商品 | `GET /products`（Step 0 後只有上架中、含主圖）、`GET /products/{id}`、`GET /products/{id}/images`、`GET /products/batch?ids=` |
| 庫存 | `GET /inventory/{sku}` |
| 訂單 | `POST /orders`、`GET /orders`（只有自己的）、`GET /orders/{id}`、`POST /orders/{id}/cancel` |
| 付款 | `POST /payments`、`GET /payments/{id}`、`POST /payments/{id}/mock-callback` |

---

## 還沒開始的 step

### Step 11：部署

- 和後台相同的做法：多階段 Dockerfile（node build → nginx）、SPA fallback、`/api` 轉發到 `http://api-gateway:8080`、K8s Deployment 與 Service
- 和後台一起規劃網域（例如 `shop.example.com`、`admin.example.com`）

**驗證**：在 kind 上實際從瀏覽商品走到付款成功，並在後台看到這張訂單。

### Step 12：E2E（之後再做）

Playwright 搭配 compose：瀏覽 → 加入購物車 → 註冊 → 結帳 → 付款成功 → 我的訂單看到已付款；搭配後台出貨後，前台看到已出貨。

---

## 之後再處理

- **建單冪等**：`POST /orders` 加 `Idempotency-Key`，網路重送不會建立第二張訂單
- **購物車同步到帳號**：登入後合併、換裝置也看得到；需要後端的購物車服務或 user-service 的欄位
- **登出時是否清空購物車**：共用電腦的情境
- **個人資料修改、改密碼、忘記密碼**：user-service 補 API（忘記密碼還要寄信）
- **SEO**：商品頁要被搜尋引擎收錄時評估 SSR（Nuxt）或預先產生頁面
- **和後台共用程式碼**：重複多了再改成 monorepo workspace 的共用套件
