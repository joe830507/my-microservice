# vue-customer 前台（商店）實作與驗收紀錄

目前程式分工與依賴圖見[元件關係與職責](../../component-relationships/vue-customer.md)；本文件保留實作步驟、設計決策與當時驗收紀錄。

> 建立日期：2026-11-10
> 實作分支： `feature/vue-customer`
> 位置：`frontend/vue-customer`
> 狀態：完成（Step 0–12）

顧客用的商店前台：瀏覽商品、購物車、註冊登入、下單、付款（模擬金流）、查詢自己的訂單。後台是 [vue-management](../vue-management/README.md)。

## 進度

| Step | 內容                                                         | 狀態   |
| ---- | ------------------------------------------------------------ | ------ |
| [0](#step-00) | 後端前置問題：公開商品 API 只回上架中、列表帶主圖、分類含子分類 | 完成   |
| [1](#step-01) | 建立專案、Vite proxy、版面骨架                               | 完成   |
| [2](#step-02) | HTTP 層與登入狀態（沿用後台的做法）                          | 完成   |
| [3](#step-03) | 註冊、登入、登出                                             | 完成   |
| [4](#step-04) | 商品列表：分類、搜尋、篩選、排序、分頁                       | 完成   |
| [5](#step-05) | 商品詳情：圖片、價格、庫存、加入購物車                       | 完成   |
| [6](#step-06) | 購物車（存在瀏覽器）                                         | 完成   |
| [7](#step-07) | 結帳、建立訂單                                               | 完成   |
| [8](#step-08) | 付款（模擬金流）                                             | 完成   |
| [9](#step-09) | 我的訂單：列表、詳情、取消、繼續付款                         | 完成   |
| [10](#step-10) | 帳號頁：個人資料、登出所有裝置                               | 完成   |
| [11](#step-11) | 部署：Dockerfile、nginx、K8s                                 | 完成（kind 上驗證；CSP 已於 Step 12 驗證） |
| [12](#step-12) | Playwright E2E（真實後端、真實 Chrome）                        | 完成：主流程、瀏覽、購物車例外、訂單；含對著 nginx（CSP）的驗證 |

每一步都附測試，測試通過後單獨 commit。整體設計與各步驟實作、驗收集中於本文件，上方進度表可直接跳到對應章節。

---

## 後端前置問題

| 問題 | 現況 | 決定 |
| ---- | ---- | ---- |
| 公開商品 API 回傳所有狀態 | `GET /products`、`/products/{id}`、`/products/sku/{sku}`、`/products/{id}/images` 公開，草稿、已下架、已封存都看得到（vue-management Step 6 發現） | **已修正（[Step 0](#step-00)）**：不是 ADMIN 的請求只回 `ACTIVE`（列表自動加上條件；單筆查非上架中的回 404 `PRODUCT_NOT_FOUND`）；ADMIN 照舊看全部，後台不用改 |
| 商品列表沒有圖片 | `ProductListItem` 沒有圖片欄位，商品格狀列表要對每個商品再查一次圖片 | **已補上（[Step 0](#step-00)）**：列表項目加 `primaryImageUrl`（沒有主圖為 `null`），一次查出這一頁商品的主圖 |
| 分類篩選不含子分類 | `categoryId` 只比對同一個分類；點「鞋子」看不到「靴子」底下的商品 | **已補上（[Step 0](#step-00)）**：加 `includeSubcategories=true`，由 product-service 用分類樹展開子孫 id |
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
- **購物車不跟帳號綁定**：存在瀏覽器，換裝置不會同步；使用者主動登出或成功登出所有裝置後清空，登入、登入過期、登入取得資料失敗時保留。只由兩個主動登出呼叫端清空，不放進 auth.clear()。
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

## 之後再處理

> 2026-11-13 文件整理：暫緩／後續項目的現況、前提與影響範圍統一見[集中清單](../deferred-items.md)；本處原始說明與歷史紀錄保留。


- **建單冪等**：`POST /orders` 加 `Idempotency-Key`，網路重送不會建立第二張訂單
- **購物車同步到帳號**：登入後合併、換裝置也看得到；需要後端的購物車服務或 user-service 的欄位
- **已完成（2026-11-13）登出清空購物車**：頂部列主動登出、帳號頁登出所有裝置成功後呼叫 cart.clear()，移除 localStorage，既有 storage 事件同步其他分頁。撤銷所有裝置失敗時保持原狀；登入與登入過期不清空。見 [T-03](../deferred-now-tasks.md#t-03-共用電腦登出後清空購物車) 與[集中清單](../deferred-items.md)。
- **個人資料修改、改密碼、忘記密碼**：user-service 補 API（忘記密碼還要寄信）
- **SEO**：商品頁要被搜尋引擎收錄時評估 SSR（Nuxt）或預先產生頁面（2026-11-13 改列[路線圖「暫不排入」](../middleware/middleware-roadmap.md#暫不排入記錄理由)）
- **和後台共用程式碼**：重複多了再改成 monorepo workspace 的共用套件（2026-11-13 改列[路線圖「暫不排入」](../middleware/middleware-roadmap.md#暫不排入記錄理由)）

> 以下 Step 保留實作當時的決策、問題與驗證數字；「待辦／尚未驗證」須連同後續步驟閱讀，不能視為今日重新執行的結果。

---

<a id="step-00"></a>

## vue-customer Step 0：後端前置問題

- product-service：不是 ADMIN 的請求只看得到 `ACTIVE` 的商品
  - `GET /products`：自動加上 `status = ACTIVE`，忽略傳入的 `status`
  - `GET /products/{id}`、`/sku/{sku}`、`/{id}/images`：非上架中回 404 `PRODUCT_NOT_FOUND`
  - `GET /products/batch`：照舊回傳（帶 `status`），購物車靠它知道哪些商品已下架；它只有 id、SKU、名稱、價格、狀態，沒有說明與圖片。要更嚴格的話，非上架中的商品只回 `id` 與 `status`
  - 注意快取：商品詳情有 Redis 快取，判斷要在讀快取之後做，不能把「看不到」的結果快取起來
- `ProductListItem` 加 `primaryImageUrl`：一頁的商品一次查主圖（`WHERE product_id IN (...) AND is_primary`），不要每個商品各查一次
- `GET /products` 加 `includeSubcategories`：用分類樹展開子孫分類 id 再查
- 每一項各自整合測試、單獨 commit；更新 [microservice-architecture.md](../../architecture/microservice-architecture.md)

**測試**：未登入、CUSTOMER 看不到草稿與封存的商品（列表、單筆、圖片）；ADMIN 照舊看得到；列表的主圖網址正確、沒有主圖為 `null`；含子分類的篩選；vue-management 的測試仍然全部通過。

<a id="step-00-完成內容2026-11-10"></a>

### 完成內容（2026-11-10）

三項各自一個 commit，都在 `feature/vue-customer` 分支：

| 項目 | commit | 測試 |
| ---- | ------ | ---- |
| 公開商品 API 只回上架中 | `d0c9790` | `ProductVisibilityIntegrationTests`（12） |
| 列表帶主圖網址 | `3c6fc55` | `ProductListPrimaryImageIntegrationTests`（3） |
| 分類篩選含子分類 | `331dbe2` | `ProductListSubcategoryIntegrationTests`（6） |

<a id="step-00-1-公開商品-api-只回上架中"></a>

#### 1. 公開商品 API 只回上架中

- **怎麼判斷 ADMIN**：controller 讀 Gateway 帶來的 `X-User-Roles`（和 order-service 的 `isAdmin` 一樣），沒登入就沒有這個 header。Gateway 會先移除用戶端送來的 `X-User-*`，所以無法偽造
- service 的讀取方法多一個 `includeUnpublished` 參數，controller 依角色傳入：
  - `getProductList`：不是 ADMIN 時把狀態條件固定成 `ACTIVE`，傳入的 `status` 不採用
  - `getProductById`、`getProductBySku`：**先讀詳情快取再判斷可見性**。快取存的是完整資料，ADMIN 讀過的草稿進了快取，訪客再讀仍然是 404
  - `getProductImages`：不是 ADMIN 時用 `existsByIdAndStatus(id, ACTIVE)` 判斷商品是否存在
- `GET /products/batch`：當時不變，購物車需要它的 `status`；**之後補上了限制，見下方「後續補強」**
- 後台（vue-management）帶著 ADMIN 的 token，行為不變；它的 261 個測試照樣通過
- **連帶修改的既有測試**：
  - `ProductDetailCacheIntegrationTests` 直接呼叫 service，測的是快取，改成傳 `includeUnpublished = true`
  - `ProductImageRegisterIntegrationTests` 有一個案例沒帶身分就查草稿商品的圖片列表，原本是 200、現在是 404；這個案例要驗證的是後台看到的圖片，改成帶 ADMIN 的 header

測試：未登入與 CUSTOMER 的列表只有上架中；傳 `status=DRAFT`、`INACTIVE`、`ARCHIVED` 也一樣；ADMIN 看全部且狀態篩選有效；單筆、SKU、圖片列表的可見性；ADMIN 先讀進快取後訪客仍是 404；批次查詢照舊帶狀態。

<a id="step-00-2-列表帶主圖網址"></a>

#### 2. 列表帶主圖網址

- `ProductListItem` 加 `primaryImageUrl`，沒有主圖為 `null`
- 一頁查一次：`ProductImageRepository.findAllByProductIdInAndIsPrimaryTrue(ids)`，再用 `ImageStorage.publicUrl` 組成公開網址
- 測試直接把圖片寫進 DB，不需要物件儲存：有主圖、只有一般圖片、沒有圖片三種；分頁時只查這一頁的主圖；空列表

<a id="step-00-3-分類篩選含子分類"></a>

#### 3. 分類篩選含子分類

- `ProductQueryRequest` 加 `includeSubcategories`（預設 `false`，後台的分類篩選不受影響）
- 為 `true` 時，從分類表讀出全部分類，依 `parentId` 往下展開子孫 id，再用 `categoryId IN (...)` 查；用記錄看過的 id 的集合避免資料有循環時無限迴圈
- **停用的子分類也包含在內**：商品能不能看到由商品狀態決定（前台只看得到上架中），分類停用不影響這裡
- 測試：從根分類、從中間分類展開；不帶參數或 `false` 只查該分類；葉節點分類；不存在的分類回空列表；和可見性一起用（子分類的草稿訪客看不到、ADMIN 看得到）

<a id="step-00-最終檢查"></a>

### 最終檢查

- product-service：151 個測試全部通過（新增 21 個）
- vue-management：261 個測試全部通過（前端沒有改動，確認後端改動不影響後台）
- `microservice-architecture.md` 已更新 product-service 的說明

<a id="step-00-後續補強2026-11-11get-productsbatch-不外流非上架商品的內容"></a>

### 後續補強（2026-11-11）：`GET /products/batch` 不外流非上架商品的內容

vue-customer 與真的後端對接時發現：上面第一輪漏掉 `/products/batch`，匿名使用者查草稿商品的 id 會拿到完整的 SKU、名稱、價格。這支 API 不能改成要登入（訪客的購物車頁要用），所以改成限制內容：

- 不是 ADMIN（匿名、CUSTOMER，以及沒有帶角色 header 的服務間呼叫）時，**非 `ACTIVE` 的商品只回 `id` 與 `status`**，`sku`、`name`、`price` 為 `null`；`ACTIVE` 照舊回完整內容；ADMIN 全部回完整內容
- 呼叫端都不受影響：order-service 只在商品是 `ACTIVE` 時才用 SKU、名稱、價格，其他狀態只看 `status`（已用真的服務驗證：草稿 → `PRODUCT_NOT_AVAILABLE`、上架商品 → 建單成功且快照正確）；購物車只需要 `status` 就能標成「無法購買」
- 和其他讀取 API 一致：**先讀詳情快取再判斷**，快取存的是完整資料，ADMIN 讀過的草稿進了快取，匿名使用者仍然只拿到 `id`、`status`
- `ProductService.getProductsByIds` 多了 `includeUnpublished` 參數；controller 讀 `X-User-Roles`；`ProductBatchItem.forVisibility` 決定回完整還是只有 status
- **測試**：`ProductVisibilityIntegrationTests` 把原本「批次查詢不變」的案例換成 5 個（匿名、CUSTOMER、ADMIN、ADMIN 先讀快取後匿名、不存在的 id 略過），共 16 個；`ProductDetailCacheIntegrationTests` 改傳 `includeUnpublished = true`。product-service 全部 155 個通過
- 前端（vue-customer）：`BatchProduct` 的 `sku`、`name`、`price` 改成可為 `null`；購物車列上沒有名稱的非上架商品顯示「此商品已下架」

---

<a id="step-01"></a>

## vue-customer Step 1：建立專案

- 設定檔從 vue-management 複製（TypeScript、Router、Pinia、Vitest、ESLint、oxlint、Prettier 相同的設定），不再跑 `npm create vue@latest`；因此 `tsconfig.vitest.json` 等 Step 1–5 在後台遇到的坑不會再踩
- 拿掉 Element Plus 與 `unplugin-auto-import`、`unplugin-vue-components`（前台自己寫元件）；對應拿掉 `tsconfig.vitest.json`、`eslint.config.ts`、`.prettierignore` 裡自動產生型別檔的設定
- `vite.config.ts`：port 5174、proxy `/api` → `http://localhost:8080`；`.env.development` 的 `VITE_API_BASE=/api/v1`
- `styles/tokens.css`：顏色、字級、間距、圓角的 CSS 變數；`styles/base.css`：重設、`.container`、`.btn`。淺色主題、mobile-first（基本樣式以手機為準，768px 以上才加強）
- `StoreLayout`：頂部列（商店名稱、搜尋框、購物車、登入）、內容區、頁尾；手機搜尋框換到第二列。搜尋、購物車、登入目前是假的連結，分別在 Step 4、6、3 接上
- 路由：`/` 在 `StoreLayout` 裡，萬用路由顯示 404（不需要登入）；`meta.requiresAuth` 與 `meta.title` 的型別先宣告，守衛在 Step 2 加入
- 商店名稱 `APP_TITLE = 'Joe 商城'` 放在 `router/index.ts`（暫定，之後想換只改這一處）

<a id="step-01-完成內容"></a>

### 完成內容

**測試**（`npm run test:unit`，3 個）：`App.spec.ts`（首頁在 StoreLayout 裡、未知網址顯示 404）、`StoreLayout.spec.ts`（頂部列、內容區、頁尾）。`npm run build`（vue-tsc + vite build）、oxlint、eslint 都通過。

---

<a id="step-02"></a>

## vue-customer Step 2：HTTP 層與登入狀態

- 從 vue-management 複製 `api/http.ts`、`api/errors.ts`、`features/auth` 的 `api.ts`、`types.ts`、`interceptors.ts`、`store.ts`，以及 `composables/useRouteQuery.ts`（先複製，不抽共用套件）
- store 拿掉 ADMIN 檢查與 `NOT_ADMIN` 錯誤代碼：ADMIN 也能逛和下單，登入後不檢查角色
- refresh token 的 localStorage key 為 `vue-customer.refreshToken`，和後台分開；同一台電腦可以同時登入兩邊的不同帳號（**2026-11-11 SEC-06 後已改為 HttpOnly cookie，前端只留 `vue-customer.hasSession` 提示，見 [known-issues](../../security/known-issues.md)**）
- 路由守衛 `installAuthGuard`：只有 `meta.requiresAuth` 的頁面才需要登入（和後台相反），未登入導到登入頁並帶 `redirect`；重新整理後先用 refresh token 恢復登入狀態。已登入進登入頁回首頁
- `main.ts` 的 session 過期處理：refresh 失敗時，**只有在需要登入的頁面**才導到登入頁；逛商品時不打斷
- `/login` 路由與 `LoginView.vue` 先放佔位內容（Step 3 實作），守衛才有地方可以導

<a id="step-02-完成內容"></a>

### 完成內容

**測試**（`npm run test:unit`，全部 53 個）：
- 沿用後台的 `errors`、`http`、攔截器（同時兩個 401 只 refresh 一次、refresh 失敗清除等）、store（登入、恢復、登出）測試，去掉 ADMIN 相關的案例
- `router/__tests__/guard.spec.ts`：不需要登入的頁面未登入也能進入；需要登入的頁面導到登入頁並帶 `redirect`；有 refresh token 時恢復登入；refresh token 失效導到登入頁；已登入進登入頁回首頁；404；分頁標題

`npm run build`、oxlint、eslint 通過。

---

<a id="step-03"></a>

## vue-customer Step 3：註冊、登入、登出

- `LoginView`、`RegisterView`：自己寫的表單（不用元件庫），欄位檢查放在 `features/auth/validation.ts`，規則和後端 `RegisterReqDto` 一致（Email 最多 254、密碼 8–72、顯示名稱 1–50）。密碼另外檢查 UTF-8 bytes ≤ 72：bcrypt 只取前 72 bytes，中文字元數合格但 bytes 可能超過（後端的 `@Size` 只算字元數）。登入只檢查有沒有填，不套用新密碼的規則
- 送出中停用按鈕，連點只送出一次
- 錯誤訊息依代碼：登入 `INVALID_CREDENTIALS`、`USER_DISABLED`、`TOO_MANY_LOGIN_ATTEMPTS`、`TOO_MANY_REQUESTS`；註冊 `EMAIL_ALREADY_EXISTS`、`TOO_MANY_REQUESTS`；其他顯示後端的 `detail`
- 註冊：`POST /auth/register`（回 201，沒有 token）→ 自動 `login`。若註冊成功但登入失敗，store 丟出 `REGISTERED_LOGIN_FAILED`，畫面說明「帳號已建立，請到登入頁登入」，避免使用者重複註冊
- `safeRedirect` 沿用後台，另外擋掉 `/register`；登入頁與註冊頁互相連結時保留 `redirect`
- 頂部列：未登入顯示「登入」（帶目前頁面的 `redirect`）、「註冊」；已登入顯示名稱與「登出」。登出後在需要登入的頁面會回首頁；購物車保留
- `main.ts` 啟動時背景呼叫 `restoreSession()`，重新整理後頂部列才會顯示已登入；和路由守衛共用同一次恢復

<a id="step-03-完成內容"></a>

### 完成內容

**測試**（`npm run test:unit`，全部 102 個）：
- `validation.spec.ts`：各欄位邊界（8／72 字元、72 bytes、50 字元、空白）
- `messages.spec.ts`：登入、註冊錯誤代碼對應；`safeRedirect`
- `LoginView.spec.ts`：成功後回 `redirect`、外部網址回首頁、四種錯誤代碼、欄位驗證不發請求、連點只送一次
- `RegisterView.spec.ts`：送出的 body（去前後空白）、註冊後已登入並回 `redirect`、`EMAIL_ALREADY_EXISTS`、註冊成功但登入失敗、欄位驗證
- `StoreLayout.spec.ts`：未登入／已登入的頂部列、登出清除狀態、在需要登入的頁面登出後回首頁，再進去被導到登入頁

`npm run build`、oxlint、eslint 通過。

**尚未用真的後端驗證**：測試都用 MSW 模擬，還沒啟動 user-service 實際註冊、登入一次。

---

<a id="step-04"></a>

## vue-customer Step 4：商品列表

- `features/catalog`：`types.ts`、`api.ts`（`GET /categories/tree`、`/categories/slug/{slug}`、`/products`）、`query.ts`、`categoryStore.ts`
- **條件都在網址上**（`useRouteQuery`）：`q`（關鍵字）、`minPrice`、`maxPrice`、`sort`（`latest`／`price-asc`／`price-desc`）、`page`（從 1 開始，送後端時減 1）。和預設值相同的不放進網址；不合法的值（手改網址）改回預設值。每頁固定 12 件。排序只開放後端支援的 `createdAt`、`price`
- 換條件（關鍵字、價格、排序）回第一頁，換頁保留其他條件；價格欄位是草稿，按「套用」才寫進網址，並檢查格式與「最低價不能高於最高價」（對應後端的 `isPriceRangeValid`）
- **分類**：`/c/:slug` 用 slug 查分類（`CATEGORY_NOT_FOUND`，含已停用，顯示 404），列表帶 `categoryId` 與 `includeSubcategories=true`，點「鞋子」看得到「靴子」底下的商品；有子分類時顯示子分類連結（從分類樹找）。換分類時列表整個重建
- 分類樹放 Pinia（`categoryStore`）：頂部列、首頁、分類頁共用，只載入一次；載入失敗就保持空的，分類入口不出現但商品照常可以逛
- **搜尋**：頂部列搜尋框送出到 `/search?q=`（去前後空白，空白就列出全部）；在搜尋頁時顯示目前關鍵字。沒有關鍵字的 `/search` 標題是「全部商品」
- **首頁**：分類入口（頂層分類）、最新上架 8 件、「查看全部商品」
- 元件：`ProductCard`（主圖、品牌、名稱最多兩行、價格；沒有主圖顯示「尚無圖片」的預設圖）、`PriceTag`（有原價且高於售價時顯示刪除線原價與 `-N%`，N 至少 1）、`PaginationBar`（頁數多時只顯示目前頁附近加首尾，中間省略號）。手機 2 欄、768px 以上 3 欄、1024px 以上 4 欄
- **請求競爭**：快速切換條件時，舊請求比新請求晚回來也不會蓋掉結果（只接受最後發出的）
- 商品詳情 `/p/:id` 先放佔位頁（Step 5 實作），商品卡片才有地方可以連

<a id="step-04-完成內容"></a>

### 完成內容

**測試**（`npm run test:unit`，全部 165 個）：
- `query.spec.ts`：URL ↔ 條件 ↔ API 參數（預設值、不合法值、分頁換算、排序、分類帶 `includeSubcategories`、價格區間檢查）
- `format.spec.ts`：價格格式、折扣百分比（含原價只高一點點、原價低於售價、原價為 0）
- `components.spec.ts`：價格與折扣顯示、沒有主圖用預設圖、分頁的省略號與按鈕狀態
- `ProductListing.spec.ts`：網址條件轉成 API 參數、空結果、切換排序回第一頁、價格套用與清除、不合法價格不發請求、分頁保留條件、查詢失敗與重試、舊請求晚回來不覆蓋
- `CategoryView.spec.ts`：用 slug 列出商品且含子分類、子分類連結、分類不存在顯示 404 且不查商品、錯誤與重試
- `HomeView.spec.ts`：分類入口與最新 8 件、沒有商品、分類失敗商品仍顯示、商品失敗與重試
- `StoreLayout.spec.ts`：分類列、搜尋框送出與同步關鍵字

`npm run build`、oxlint、eslint 通過。

**尚未用真的後端驗證**：測試用 MSW 模擬，還沒有啟動 product-service 與 Gateway 實際瀏覽一次（特別是 `includeSubcategories`、`primaryImageUrl`、圖片跨來源載入）。

---

<a id="step-05"></a>

## vue-customer Step 5：商品詳情

- `/p/:id`（`ProductDetailView`）：先查 `GET /products/{id}`，再**並行**查 `GET /products/{id}/images` 與 `GET /inventory/{sku}`（庫存要用商品的 SKU，所以要等商品回來）
- 圖片與庫存各自獨立失敗：圖片載入失敗只是顯示預設圖；庫存查詢失敗顯示「庫存查詢失敗」與重試、不能加入購物車，商品資訊照常顯示
- **找不到**：`PRODUCT_NOT_FOUND`（不存在，或 Step 0 後非上架中的商品）與 400（網址上的 id 不是合法 UUID）都顯示 404，不再查圖片與庫存
- `ImageGallery`：主圖排第一張（不管後端順序）、點縮圖切換、只有一張時不顯示縮圖列、沒有圖片顯示預設圖；`alt` 優先用後端的說明，否則用商品名稱
- 庫存：`getStock` 在 `STOCK_NOT_FOUND` 回 `null`，畫面當成缺貨。`available > 0` 才能加入；`available ≤ 5` 顯示「僅剩 N 件」，否則「尚有庫存」，0 或沒有資料「缺貨」
- `QuantityInput`：`−`／`+` 按鈕與直接輸入，範圍 1 到 `min(99, 可賣數量)`；輸入 0、負數、小數、空白、非數字、超過上限都會被拉回範圍內。99 和建單 API 的單項上限一致
- 說明用文字插值輸出（`white-space: pre-wrap` 保留換行），**不用 `v-html`**，說明裡的 HTML 不會被執行
- 快速在商品間切換時，舊請求晚回來不會蓋掉新商品（和商品列表同樣的做法）
- **購物車 store 先做最小版**（`features/cart/store.ts`）：`add` 合併同一商品、總數上限 99，存 localStorage（key `vue-customer.cart`，只存 `productId`、`qty`）；讀取時丟掉不合法項目、合併重複、限制數量；localStorage 讀寫失敗只是不保存。頂部列「購物車」顯示件數（數量加總）。Step 6 在這個 store 上加修改數量、移除，並做購物車頁
- `/cart` 路由與 `CartView` 先放佔位頁（Step 6 實作），「前往購物車」與頂部列才有地方可以連

<a id="step-05-完成內容"></a>

### 完成內容

**測試**（`npm run test:unit`，全部 210 個）：
- `cart/store.spec.ts`：加入、合併、上限 99、數量取整數、重新整理後還在、內容壞掉／不合法項目、localStorage 讀寫失敗
- `QuantityInput.spec.ts`：按鈕增減、邊界停用、各種不合法輸入
- `ImageGallery.spec.ts`：沒有圖、單張、主圖排前面、切換、換商品回第一張
- `ProductDetailView.spec.ts`：資訊與折扣顯示、HTML 說明不被執行、404／400／其他錯誤與重試、庫存的各種狀態（有、少、0、`STOCK_NOT_FOUND`、查詢失敗與重試）、圖片失敗、加入購物車（數量、合併、不超過可賣數量、上限 99、缺貨不能加）
- `StoreLayout.spec.ts`：頂部列購物車件數

`npm run build`、oxlint、eslint 通過。

**測試抓到的問題**：購物車 `add(id, NaN)` 會把數量存成 `NaN`（`Math.max(1, NaN)` 是 `NaN`），已修正。

**尚未用真的後端驗證**：測試用 MSW 模擬，還沒有實際對 product-service、inventory-service 看過一次（特別是公開的 `GET /inventory/{sku}` 經過 Gateway、非上架商品回 404）。

---

<a id="step-06"></a>

## vue-customer Step 6：購物車

- **store**（`features/cart/store.ts`，Step 5 建了最小版）：新增 `setQty`、`remove`、`removeMany`（結帳成功後移除已下單的商品會用到）。`add` 改成回傳 `boolean`
- **種類上限 50**：後端建單 API（`CreateOrderReqDto`）一張訂單最多 50 種商品、單項 1–99 件，購物車一開始就用同樣的限制，不要等到結帳才失敗。已滿時新的商品加不進去（商品詳情頁顯示「購物車最多放 50 種商品」），已在購物車裡的商品仍可增加數量；從 localStorage 讀取時超過的部分丟掉
- **購物車頁** `/cart`：進來時用 `GET /products/batch?ids=` 查最新的名稱、價格、狀態，列表依購物車順序。localStorage 的 `productId`、`qty` 以外的欄位一概不信
  - `ids` 要寫成重複的 key（`ids=a&ids=b`）：axios 預設會寫成 `ids[]=a&ids[]=b`，Spring 的 `@RequestParam List<UUID>` 綁不到，所以設 `paramsSerializer: { indexes: null }`，測試直接檢查請求的查詢字串
  - batch 只回 id、SKU、名稱、價格、狀態，沒有圖片與原價，所以購物車列表不顯示圖片、折扣。**非上架中的商品後端只回 id 與 status**（見 [Step 0 的後續補強](#step-00)），沒有名稱與價格，畫面顯示「此商品已下架」
  - 狀態不是 `ACTIVE`（草稿、下架、封存）或查不到（商品已被刪除）：標成「無法購買」（查不到顯示「商品已不存在」），不能改數量、不計入總計，可以單獨移除或「全部移除」
  - 修改數量（`QuantityInput`，1–99）、移除：立刻反映在小計、總計與 localStorage；商品資料只在進頁面時查一次
  - 沒有可購買的商品時「前往結帳」停用；空的購物車顯示提示與「去逛逛」；查詢失敗顯示錯誤與重試，購物車內容不受影響
- **金額用「分」計算**（`lines.ts`）：`0.1 × 3` 不會顯示成 `0.30000000000000004`。實際金額以建單時的快照為準，這裡只是預覽
- `/checkout` 路由與 `CheckoutView` 先放佔位頁（Step 7 實作），標 `requiresAuth`：按「前往結帳」未登入會導到登入頁，登入後回到結帳頁
- 頂部列的件數（Step 5 做的）會跟著數量與移除更新；它算的是購物車所有商品的數量加總，包含已經無法購買的

<a id="step-06-完成內容"></a>

### 完成內容

**測試**（`npm run test:unit`，全部 233 個）：
- `store.spec.ts`：加入與合併、50 種上限（含已有的商品仍可加）、`setQty`（範圍、NaN、不在車裡）、`remove`／`removeMany` 且重新整理後不會回來、壞掉的 localStorage、讀寫失敗
- `lines.spec.ts`：配對最新資料、各種狀態的可購買判斷、小計與總計（只算可購買、浮點誤差、空購物車）
- `CartView.spec.ts`：空購物車、`ids=a&ids=b`、價格以最新查詢為準、修改數量即時更新並保存、移除、無法購買的商品（標示、不計入、單獨與全部移除、全部無法購買不能結帳）、查詢失敗與重試、未登入前往結帳導到登入頁並帶 `redirect`
- `ProductDetailView.spec.ts`：購物車已滿的提示

`npm run build`、oxlint、eslint 通過。

**尚未用真的後端驗證**：測試用 MSW 模擬，還沒有實際對 product-service 的 `/products/batch` 看過一次（尤其 `ids` 的格式，以及非上架的商品是否照預期帶 `status` 回來）。

---

<a id="step-07"></a>

## vue-customer Step 7：結帳、建立訂單

- `/checkout`（`requiresAuth`）：未登入導到登入頁，登入後回到結帳頁。進頁面時用 `GET /products/batch` 重新查價格與狀態，列出**可購買**的商品（名稱、單價 × 數量、小計）與總計；無法購買的不列出、不計入，提示回購物車處理。畫面上的金額只是預覽，實際金額以建單時的快照為準
- 購物車頁與結帳頁共用的「查商品、算行與總計」抽成 `cart/useCartLines.ts`（購物車頁改用它，行為不變）
- `POST /orders`（`features/orders/api.ts`）：body 只有 `productId`、`qty`，不送名稱與價格（後端一律以 product-service 為準）。型別放 `features/orders/types.ts`，Step 8、9 沿用
- **防止重複下單**：後端沒有冪等 key，重送會建兩張訂單（各自預扣庫存）。送出中按鈕停用並顯示「送出中…」、函式開頭也擋掉重入；建單成功後 `router.replace` 到付款頁，按上一頁不會回到可以再送出的結帳頁
- 成功：只從購物車移除**這次送出的商品**，因為無法購買而沒送出的留著，再導到 `/orders/:id/pay`
- **失敗**（`checkout/messages.ts`，購物車一律不變）：
  - `OUT_OF_STOCK`：後端 detail 是 `out of stock: SKU-A, SKU-B.`，用送出時的商品清單把 SKU 換成商品名稱，「『商品 b』庫存不足，請調整數量或移除後再試」
  - `PRODUCT_NOT_AVAILABLE`、`PRODUCT_NOT_FOUND`：detail 列的是商品 id，同樣換成名稱；並**重新查商品**，把剛被下架的標成無法購買
  - `PRODUCT_SERVICE_UNAVAILABLE`、`INVENTORY_SERVICE_UNAVAILABLE`、`TOO_MANY_REQUESTS`：「訂單尚未建立，請稍後再試」（這幾種下游錯誤發生在建單之前，確定沒有建立）
  - **網路中斷、逾時（沒收到回應）**：請求可能已經送到後端並建立了訂單，直接再送會變兩張。訊息要使用者先到「我的訂單」確認，並附連結。這是沒有冪等 key 下最容易出事的一種
  - 其他（`VALIDATION_FAILED` 等）：顯示後端的 `detail`
  - 對不到商品時顯示「部分商品」，不把 SKU 或 id 丟給顧客
- 付款頁 `/orders/:id/pay`（Step 8）與我的訂單 `/orders`（Step 9）先放佔位頁（兩者都 `requiresAuth`），建單成功與「我的訂單」連結才有地方可以去

<a id="step-07-完成內容"></a>

### 完成內容

**測試**（`npm run test:unit`，全部 258 個）：
- `messages.spec.ts`：各錯誤代碼的訊息、SKU／id 換名稱、對不到時說「部分商品」且不外露識別碼、`refresh`／`uncertain` 旗標
- `CheckoutView.spec.ts`：
  - 顯示：重新查價格與總計、無法購買的不列出不計入、沒有可結帳商品、空購物車、查詢失敗與重試、未登入導到登入頁
  - 送出：body 正確（不含價格、不含無法購買的商品）、成功後只移除已下單的商品並 `replace` 到付款頁、連點三次只送出一次、庫存不足（購物車不變、可再送出）、商品已下架（重新查後標成無法購買）、兩種 503、網路中斷（不確定提示與連結）、`VALIDATION_FAILED`

`npm run build`、oxlint、eslint 通過。

**尚未用真的後端驗證**：測試用 MSW 模擬，還沒有實際建立過一張訂單。要特別確認的是後端錯誤 `detail` 的格式真的是 `out of stock: SKU-A.`、`...: <id>.`（訊息轉換靠這個格式，對不到時會退回「部分商品」，不會壞）。

---

<a id="step-08"></a>

## vue-customer Step 8：付款（模擬金流）

- `/orders/:id/pay`（`OrderPayView`，`requiresAuth`）。流程邏輯放 `features/payments/usePayFlow.ts`（composable），畫面只負責依 `phase` 顯示；`api.ts` 有 `createPayment`、`getPayment`、`mockCallback`，`orders/api.ts` 加了 `getOrder`
- **進頁面**：先 `GET /orders/{id}` 再決定顯示什麼：`PAID`／`SHIPPED`／`COMPLETED` → 已付款（不建立付款）；`CANCELLED` → 已取消；不存在或別人的訂單（`ORDER_NOT_FOUND`、400）→ 404；`PENDING_PAYMENT` → `POST /payments`
- **重新整理不會重複建立**：後端對同一張訂單已有進行中（PENDING）的付款時回 200 與同一筆，前端每次進頁面都呼叫、用回傳的 id
- **剩餘時間**：建單 `createdAt` + 30 分鐘（對應 order-service 的 `payment-timeout`），每秒更新，`mm:ss`。用的是使用者電腦的時鐘，只用來提示與停用按鈕，真正的期限以後端為準。到期後停用付款按鈕、提示付款期限已到，並重新查一次訂單（若已被取消就切到已取消）
- **付款成功／失敗**：兩個按鈕呼叫 `mock-callback`（`SUCCESS`／`FAILED`）；處理中按鈕停用，連點只送一次
  - `SUCCEEDED`：輪詢 `GET /orders/{id}` 到 `PAID`（付款通知 order-service 是非同步重試，可能晚幾秒）。間隔 1 秒、最多 10 次（`pollConfig`，測試會改小）。到上限仍不是 `PAID` → 「付款處理中，請稍後到我的訂單查看」，附「再查一次」
  - `FAILED`：顯示付款失敗，「重新付款」再呼叫 `POST /payments`（上一筆已是終點，後端會建立新的），之後的回呼用新的付款 id
  - 輪詢時訂單變成 `CANCELLED`（付款成功時剛好逾時被取消）：說明已付的款項會退回（雛型階段後端只記錄，還沒有真的退款）
  - 離開頁面（元件卸載）就停止輪詢與計時器
- **回呼失敗時，以後端記錄的付款狀態為準**，不自己猜結果：回呼丟錯（409 `PAYMENT_ALREADY_COMPLETED`、沒收到回應等）時改查 `GET /payments/{id}`——已 `SUCCEEDED` 就繼續確認訂單、已 `FAILED` 就顯示失敗、仍 `PENDING` 才回到可以再按並顯示錯誤。避免「錢其實付成功了，畫面卻說失敗，顧客再付一次」
- **建立付款的錯誤**：`ORDER_ALREADY_PAID`（上次付完重新整理）→ 不能再付，輪詢到 `PAID`；`ORDER_NOT_PAYABLE`（訂單剛被取消）→ 重新查訂單決定顯示；`ORDER_SERVICE_UNAVAILABLE` 等 → 顯示錯誤與重試
- 付款頁、我的訂單的佔位頁：付款頁換成真的內容；`/orders` 還是佔位（Step 9）

<a id="step-08-完成內容"></a>

### 完成內容

**測試**（`npm run test:unit`，全部 286 個）：
- `OrderPayView.spec.ts`：
  - 等待付款：金額與商品、剩餘時間、建立付款只帶 `orderId`、期限已到停用按鈕且不送回呼
  - 付款成功：輪詢到 `PAID`、到上限顯示處理中並可再查一次、付款成功時訂單已取消、處理中連點只送一次
  - 付款失敗：重新付款建立新的付款並用新 id 回呼
  - 回呼失敗：409 但其實已成功、沒收到回應且仍 `PENDING`（回到可再按）、沒收到回應但其實已失敗
  - 進頁面時的訂單狀態：`PAID`／`SHIPPED`／`COMPLETED`、`CANCELLED`、不存在、查詢失敗與重試
  - 建立付款的錯誤：`ORDER_ALREADY_PAID`、`ORDER_NOT_PAYABLE`、`ORDER_SERVICE_UNAVAILABLE`、重新整理（200 同一筆）
  - 離開頁面後停止輪詢
- `format.spec.ts`：倒數時間格式（進位、負數、30 分鐘）

`npm run build`、oxlint、eslint 通過。

**測試遇到的問題**：頁面沒有卸載時計時器與輪詢會繼續跑，打到下一個測試的 mock，造成「離開頁面後停止輪詢」偶發失敗；測試改成每個測試結束後卸載所有掛載的頁面。

**尚未用真的後端驗證**：測試用 MSW 模擬，還沒有從頭到尾走過一次真的 `POST /orders` → `POST /payments` → `mock-callback` → 訂單變 `PAID`。特別要確認 payment-service 通知 order-service 的非同步延遲通常多久（決定輪詢 10 秒夠不夠），以及剩餘時間的 30 分鐘和 order-service 實際設定一致。

---

<a id="step-09"></a>

## vue-customer Step 9：我的訂單

兩個頁面都 `requiresAuth`（未登入導到登入頁並帶 `redirect`）。`features/orders` 新增 `getOrders`、`cancelOrder`、`query.ts`、`status.ts`、`OrderStatusBadge.vue`。

<a id="step-09-列表-orders"></a>

### 列表 `/orders`

- `GET /orders`：後端依登入者過濾，只會拿到自己的訂單（前端不帶 `userId`），固定最新的在前。每頁 10 筆
- 依狀態篩選（全部、待付款、已付款、已出貨、已完成、已取消）與頁碼放在網址上（`useRouteQuery`）：換狀態回第一頁、換頁保留狀態；網址上不合法的狀態、頁碼改回預設值
- 每張訂單：編號、狀態標籤、建立時間、金額，連到詳情；**待付款的多一個「去付款」**（連到 `/orders/:id/pay`）
- **訂單編號**取 UUID 後 8 碼大寫（`orderNumber`）：訂單 id 是 UUIDv7，前段是時間戳，同一時間建立的訂單前綴會一樣，取前段會撞
- 沒有訂單（含篩選後沒有，說明是哪個狀態）、查詢失敗與重試、快速切換條件時舊請求晚回來不覆蓋（和商品列表同樣的做法）

<a id="step-09-詳情-ordersid"></a>

### 詳情 `/orders/:id`

- 商品是**下單當下的快照**（名稱、單價、數量、小計），之後商品改名改價不影響；總計；訂單進度（只列出已發生的：建立、付款、出貨、完成、取消）
- 別人的訂單、不存在（後端都回 404 `ORDER_NOT_FOUND`，不透露存在與否）、網址的 id 不合法（400）→ 顯示找不到
- 按鈕依狀態：
  - 待付款：剩餘付款時間（建單 + 30 分鐘，每秒更新）、「繼續付款」、「取消訂單」；期限已到時隱藏「繼續付款」，提示即將被取消
  - 已付款、已出貨、已完成、已取消：都沒有（已付款之後雛型沒有退款流程，不能自己取消）
- **取消訂單**：先在頁面內確認（不用瀏覽器的 `confirm` 對話框），確認中按鈕停用、連點只送一次。成功後直接用回應更新畫面
- **取消失敗，一律重新查訂單，以後端的狀態為準**：
  - `ORDER_NOT_CANCELLABLE`（例如剛好付款了）：說明原因，畫面更新成已付款
  - `INVENTORY_SERVICE_UNAVAILABLE`：後端是先把訂單改成 `CANCELLED` 再釋放庫存，所以釋放失敗時訂單其實已取消。重新查詢後顯示已取消，並提示「請確認訂單是否已取消」，庫存由後端補釋放（取消 API 是冪等的）
  - 其他錯誤：顯示後端訊息，維持待付款，可以再試

<a id="step-09-完成內容"></a>

### 完成內容

**測試**（`npm run test:unit`，全部 322 個）：
- `query.spec.ts`：網址 ↔ 條件 ↔ API 參數、不合法值、訂單編號
- `OrderListView.spec.ts`：未登入導到登入頁、列表內容與連結、參數（每頁 10、不帶 `userId`）、待付款才有「去付款」、篩選與分頁、不合法網址參數、空結果、查詢失敗與重試、舊請求不覆蓋
- `OrderDetailView.spec.ts`：商品快照與各階段時間、404／400、查詢失敗與重試、各狀態的按鈕、待付款期限已到、取消（確認與返回、成功、連點、剛付款、庫存服務忙碌、其他錯誤、訂單已不存在）

`npm run build`、oxlint、eslint 通過。

**尚未用真的後端驗證**：測試用 MSW 模擬。要實際確認的：別人的訂單 id 是否真的回 404（不是 403）、`GET /orders` 對一般使用者忽略 `userId` 與過濾是否如預期、取消後庫存是否真的釋放。

---

<a id="step-10"></a>

## vue-customer Step 10：帳號頁

- `/account`（`requiresAuth`）：顯示名稱、Email、註冊時間，連到「我的訂單」。頂部列已登入時的顯示名稱改成連到這頁
- 資料來源：先顯示登入時取得的 `auth.user`，進頁面再 `GET /users/me`（`auth.refreshUser`）更新；重新取得失敗就維持目前的資料，不顯示錯誤、不打斷頁面
- **登出所有裝置**（`DELETE /users/me/sessions`，`auth.logoutAll`）：
  - 在頁面內先確認（不用瀏覽器的 `confirm`），處理中按鈕停用、連點只送一次
  - **先讓伺服器撤銷成功，才清除本機狀態**，再回首頁；購物車保留。撤銷失敗時維持登入並說明「其他裝置可能仍然登入」，可以再試——如果先清了本機狀態，使用者會以為其他裝置也登出了，其實還登入著
  - 頁面上說明：已經發出去的 access token 在到期前（後端 `access-token-ttl: 15m`）仍然有效，這是無狀態 JWT 的限制，不是這個功能的 bug
  - 請求要帶 access token（不是 `skipAuth`）；本機的 refresh token 也已被撤銷，所以不再呼叫 `/auth/logout`
- 個人資料修改、改密碼：後端沒有 API，畫面不放（見計畫的「之後再處理」）

<a id="step-10-完成內容"></a>

### 完成內容

**測試**（`npm run test:unit`，全部 330 個）：
- `AccountView.spec.ts`：未登入導到登入頁並帶 `redirect`、顯示資料、進頁面重新取得並更新、重新取得失敗仍顯示舊資料；登出所有裝置：先確認／返回不送請求、成功（帶 `Bearer` access token、清除狀態與 localStorage、回首頁、購物車保留、再進帳號頁被導到登入頁）、失敗（維持登入、說明其他裝置可能仍登入、可以再試）、連點只送一次
- `StoreLayout.spec.ts`：頂部列名稱連到 `/account`

`npm run build`、oxlint、eslint 通過。

**尚未用真的後端驗證**：測試用 MSW 模擬。要實際確認：`DELETE /users/me/sessions` 經 Gateway 後是否回 204、撤銷後別的裝置用舊 refresh token 是否真的被拒絕。

---

<a id="step-11"></a>

## vue-customer Step 11：部署

專案裡原本沒有任何 Dockerfile 或 K8s 設定（vue-management 的部署也還沒做），vue-customer 是第一個。後台之後可以照這一套改。

<a id="step-11-內容"></a>

### 內容

| 檔案 | 內容 |
| ---- | ---- |
| `.env.production` | `VITE_API_BASE=/api/v1`。**沒有這個檔案時 production build 的 `VITE_API_BASE` 是 `undefined`**，所有請求會打到 `/products` 而不是 `/api/v1/products`；開發時有 `.env.development` 掩蓋了這件事，部署才會發現 |
| `Dockerfile` | 多階段：`node:24-alpine` 建置（`npm ci`、`npm run build`，含 `vue-tsc` 型別檢查）→ `nginxinc/nginx-unprivileged:1.29-alpine`（非 root、監聽 8080）。測試與 lint 在 CI 先跑，不放進映像檔的建置 |
| `nginx/default.conf.template` | SPA fallback、`/api/v1/` 轉發、快取、探針；啟動時用環境變數 `API_GATEWAY_URL`、`STORAGE_PUBLIC_ORIGIN` 展開（只展開這兩個，nginx 自己的 `$uri` 等變數不受影響） |
| `nginx/security-headers.inc.template` | 安全標頭（見下） |
| `k8s/` | kustomize：`configmap.yaml`、`deployment.yaml`（2 個副本）、`service.yaml`（ClusterIP）、`kustomization.yaml` |
| `.dockerignore` | 不把 `node_modules`、`dist`、`.git` 送進建置 |

<a id="step-11-nginx-的幾個決定"></a>

#### nginx 的幾個決定

- **SPA fallback**：找不到實體檔案的路徑（`/p/123`、`/orders/x/pay`）回 `index.html`，由 vue-router 處理，包含 404 頁
- **只轉發 `/api/v1/`**：其他 `/api/...`（例如 `/api/actuator/health`）直接回 404，不會被轉給後端
- **`X-Forwarded-For` 用覆蓋**（`$remote_addr`），不附加用戶端自己帶的值：否則依 IP 的登入限流可以被偽造 header 繞過。如果前面還有 Ingress，要改成信任 Ingress 傳來的值
- **清掉 `X-User-Id`、`X-User-Roles`**：身分 header 只能由 Gateway 在驗過 JWT 後產生，nginx 這層多清一次（Gateway 本來就會清）
- **快取**：`/assets/`（檔名帶雜湊）一年、`immutable`；`index.html` 與 SPA fallback 一律 `no-cache`，發布新版後使用者才不會拿到指向舊檔名的頁面。`/assets/` 不要同時用 `expires` 與 `add_header Cache-Control`，會輸出兩個重複的標頭（實測發現並修正）
- **`add_header` 不繼承**：location 裡只要有自己的 `add_header`，就不會繼承 server 層的。所以安全標頭放在 `security-headers.inc`，每個 location 各自 `include`，server 層不重複 include（否則沒有 `add_header` 的 location 會輸出兩份）
- `/healthz`：K8s 的存活與就緒探針
- 請求本體上限 1m（前端不上傳檔案）、`proxy_read_timeout 30s`（對應 axios 的 30 秒逾時）、`server_tokens off`

<a id="step-11-安全標頭對應-sec-06securityknown-issuesmdsec-06-前端缺少瀏覽器端防護"></a>

#### 安全標頭（對應 [SEC-06](../../security/known-issues.md#sec-06-前端缺少瀏覽器端防護)）

- `Content-Security-Policy`：`default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: <STORAGE_PUBLIC_ORIGIN>; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'`。能用這麼嚴格是因為打包後的 `index.html` 沒有 inline script 或 style，Vue 用的是不含執行期編譯器的版本（不需要 `unsafe-eval`），API 經 nginx 轉發所以同源。商品圖片在物件儲存（另一個來源），所以 `img-src` 要加上 `STORAGE_PUBLIC_ORIGIN`——**後端的 `STORAGE_PUBLIC_BASE_URL` 改了（例如換成 CDN 網域），這個值要跟著改**，否則圖片會被 CSP 擋掉
- `X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`、`Referrer-Policy: strict-origin-when-cross-origin`、`Permissions-Policy`（關掉相機、麥克風、定位、付款）
- **HSTS 沒有加**：只能設在 HTTPS 上，TLS 在 Ingress 終結，要在那裡加

<a id="step-11-k8s-的決定"></a>

#### K8s 的決定

- Service 是 **ClusterIP**：對外請用 Ingress，不要用 NodePort 或 LoadBalancer 直接曝露
- 後端位址放在 ConfigMap（`API_GATEWAY_URL: http://api-gateway:8080`），映像檔裡的預設值也是 K8s 的 Service 名稱，不是 localhost；符合「服務探索用 K8s 原生，不用註冊中心」的決定
- Pod 以非 root 執行、`allowPrivilegeEscalation: false`、拿掉所有 capability、`seccompProfile: RuntimeDefault`、設 requests／limits。**沒有開 `readOnlyRootFilesystem`**：啟動時要把設定樣板展開寫進 `/etc/nginx/conf.d`
- nginx 在啟動時就解析 `api-gateway` 的主機名稱：**後端的 Service 還不存在時 nginx 會啟動失敗（`host not found in upstream`）**，Pod 會不斷重啟，直到 Service 建好才正常（實測確認）。探針沒通過之前不會有流量進來，所以不影響使用者，只是部署順序上先建好 `api-gateway` 的 Service 比較乾淨
- 映像檔是本機 `vue-customer:0.1.0`、`imagePullPolicy: Never`（kind 用 `kind load` 載入）；換成映像檔倉庫後要改

<a id="step-11-驗證"></a>

### 驗證

<a id="step-11-在本機用-docker-驗證-nginx"></a>

#### 在本機用 Docker 驗證 nginx

用 `docker run -e API_GATEWAY_URL=http://host.docker.internal:8080` 對本機真的 Gateway 驗證：
- 以非 root（uid 101）執行；設定樣板只展開兩個變數
- `/`、`/p/abc`、`/orders/x/pay`、`/no/such/page`、`/login` 都回 200 `text/html`（SPA fallback）
- 靜態資源只有一個 `Cache-Control: public, max-age=31536000, immutable`；不存在的 asset 回 404
- `/api/v1/categories/tree`、`/api/v1/products` 轉發到真的 Gateway 回 200；`/api/actuator/health`、`/api/v2/anything` 回 404；`/actuator/health` 是 SPA 的 `index.html`，不是後端的
- 偽造 `X-User-Roles: ADMIN` 讀草稿商品：404
- 每個回應都有五個安全標頭

<a id="step-11-在-kind-上驗證計畫要求的"></a>

#### 在 kind 上驗證（計畫要求的）

1. 建立叢集時發現：**這台機器的 Docker 用 cgroup v1，kind 節點映像檔（kubelet 1.37）預設拒絕在 cgroup v1 上執行**（`failCgroupV1: true`），`kubeadm init` 會失敗。用 kind 設定檔暫時允許（不需要改系統的 WSL 設定）：

   ```yaml
   kind: Cluster
   apiVersion: kind.x-k8s.io/v1alpha4
   nodes:
     - role: control-plane
       kubeadmConfigPatches:
         - |
           kind: KubeletConfiguration
           failCgroupV1: false
   ```

   `kind create cluster --name my-microservice --config <上面的檔案>`。長期要處理，cgroup v1 之後會被移除（把 Docker／WSL 切到 cgroup v2）。
2. `kind load docker-image vue-customer:0.1.0 --name my-microservice`，`kubectl apply -k k8s`
3. 因為後端還沒有放進 K8s，驗證時另外建一個**只用於驗證、不放進 repo** 的 Service，讓叢集裡的 `api-gateway:8080` 指向本機的 Gateway：

   ```yaml
   apiVersion: v1
   kind: Service
   metadata:
     name: api-gateway
   spec:
     type: ExternalName
     externalName: host.docker.internal
   ```

4. `kubectl port-forward svc/vue-customer 8091:80`，從叢集外**透過 K8s 裡的 nginx** 走完整流程，全部成功：瀏覽分類與商品 → 商品詳情與庫存 → 註冊、登入 → 購物車 batch → 建單（`PENDING_PAYMENT`，2560）→ 建立付款 → 付款成功回呼 → 輪詢 1 次訂單變 `PAID` → 我的訂單 → **後台（管理員）看到同一張訂單**

<a id="step-11-還沒做的"></a>

### 還沒做的

- **CSP 已在 [Step 12](#step-12) 於真實 Chrome 驗證**：對著這個 nginx 映像檔跑 E2E，10 個測試全過、沒有 CSP 違規（商品圖片、API、建單到付款的整條流程）
- 上面 kind 驗證的流程是用 HTTP 呼叫走的，不是瀏覽器點擊；前端畫面在真實瀏覽器裡的行為由 Step 12 的 E2E 驗證（對著同一個 nginx 映像檔）
- 後端的 Dockerfile／K8s 設定、`NetworkPolicy` 見 [後端部署](../backend-deployment-implementation-plan.md)（兩個前端與後端已經一起在 kind 上部署並驗證）
- Ingress、TLS、HSTS；映像檔倉庫與 CI；`securityContext.readOnlyRootFilesystem`
- 和後台一起規劃網域（例如 `shop.example.com`、`admin.example.com`）

<a id="step-11-清理"></a>

### 清理

驗證用的叢集與 port-forward 還開著：`kind delete cluster --name my-microservice`。

---

<a id="step-12"></a>

## vue-customer Step 12：Playwright E2E

對著**真的後端**，在**真的瀏覽器（Chrome）**裡操作商店前台：瀏覽 → 加入購物車 → 註冊 → 結帳 → 付款成功 → 我的訂單看到已付款；搭配後台出貨後，前台看到已出貨。檔案在 `frontend/vue-customer/`：`playwright.config.ts`、`e2e/`、`tsconfig.e2e.json`。

<a id="step-12-怎麼跑"></a>

### 怎麼跑

```bash
# 1. 先啟動整套後端（Gateway :8080 與各服務、資料庫、物件儲存）
# 2. 管理員帳密用環境變數傳入，不寫進 repo（本機開發用的值在 backend/spring-user-service/secrets/dev.env）
#    E2E 用管理員只是為了準備與清理資料（建立 E2E 專用商品、調整庫存、出貨），不是測試的對象
cd frontend/vue-customer
export E2E_ADMIN_EMAIL=...  E2E_ADMIN_PASSWORD=...
npm run test:e2e
```

- 預設自己啟動 Vite dev server（**:5184**，`--strictPort`），`/api` 由 Vite proxy 轉給 Gateway；用專屬連接埠，不和平常開發的 :5174 衝突
- 預設用本機安裝的 Chrome（`channel: 'chrome'`），不用另外下載瀏覽器；CI 上設 `E2E_CHANNEL=` 改用 Playwright 自己的 Chromium（先 `npx playwright install chromium`）
- **對著 nginx 映像檔跑（有 CSP 與安全標頭的版本）**：

  ```bash
  docker run -d --name vc-e2e -p 8090:8080 -e API_GATEWAY_URL=http://host.docker.internal:8080 vue-customer:0.1.0
  E2E_BASE_URL=http://localhost:8090 npm run test:e2e
  ```

  這時 `e2e/support/test.ts` 的檢查才有意義：每個測試結束時，頁面不能有 CSP 違規或未捕捉的錯誤

<a id="step-12-測試內容10-個"></a>

### 測試內容（10 個）

| 檔案 | 測試 |
| ---- | ---- |
| `purchase-flow.spec.ts` | **計畫的主流程**：搜尋 → 商品詳情 → 加入 2 件 → 購物車（總計 NT$ 1,600）→ 前往結帳，沒登入被導到登入頁 → 改去註冊（`redirect` 保留）→ 註冊成功回到結帳頁 → 送出訂單（購物車清空）→ 付款頁（金額與倒數）→ 付款成功 → 我的訂單顯示已付款 → 庫存少 2 件 → **後台出貨（API）後，前台訂單詳情顯示已出貨**且沒有取消按鈕 |
| `browse.spec.ts` | 搜尋（卡片的名稱、價格、折扣 `-20%`、主圖載入）；分類頁；分類或商品不存在顯示 404；商品詳情（庫存、數量超過上限被拉回、加入購物車後件數更新、重新整理仍在）；**手機寬度 390px 沒有水平捲軸**（首頁、搜尋、詳情、登入） |
| `cart-edge.spec.ts` | 結帳時庫存不夠：訊息指出是哪個商品、購物車不變、按鈕可再按；購物車裡的商品被下架：顯示「此商品已下架」、不洩漏名稱與價格、不能結帳 |
| `orders.spec.ts` | 待付款的訂單：列表有「去付款」，詳情有商品快照與倒數，取消要先確認（返回不取消），取消後狀態與庫存釋放；別人的訂單 id 顯示 404 |

<a id="step-12-設計決定與遇到的問題"></a>

### 設計決定與遇到的問題

- **自給自足，不動真實的商品目錄**：`global-setup.ts` 每次執行前確保 E2E 專用的分類（`e2e-playwright`）與商品（`E2E-SHOP-001`，800 元、原價 1000、有主圖、庫存 20）存在且上架，執行後（teardown）**商品下架、分類停用**，商店看不到。兩者每次共用同一個，不會越建越多；庫存也只動這件商品，不會消耗真實目錄的庫存
- **登入限流**：Gateway 的登入限流依來源 IP（約每分鐘 10 次，每 6 秒補 1 次）。每次執行約 6 次登入（管理員 1 次、固定顧客 4 次、註冊 1 次），API 登入遇到 429 會等 7 秒重試
- **refresh token 是單次使用的**：每個需要登入狀態的測試，各自用 API 登入拿自己的 refresh token，放進瀏覽器的 localStorage（`startSignedIn`），不共用（在後台 E2E 實際踩過，見 [vue-management Step 12](../vue-management/README.md#step-12)）。注入腳本只在 localStorage 還沒有時才設定，否則重新整理會把輪替過的新 token 蓋回舊的
- 購物車的預設內容用同樣的方式預先放進 localStorage（`startWithCart`），例外情況的測試不必每次先逛一圈
- **用 API 準備資料、用畫面驗證行為**：改庫存、下架商品、出貨、建立待付款訂單都走 API；測試斷言的是畫面上的結果
- 第一次跑 10 個測試通過 9 個，失敗的是我的選擇器太寬（頁面上有兩個含「購物車」的連結），不是應用程式的問題；**E2E 沒有發現前台的缺陷**

<a id="step-12-驗證"></a>

### 驗證

- Vite dev server 上連續 **3 次**全部通過（10 個測試，每次約 19 秒）
- **對著 nginx 映像檔（嚴格 CSP）通過**：10 個測試全過、沒有 CSP 違規。這是商店前台 CSP 第一次在真實瀏覽器驗證：商品圖片來自物件儲存（`img-src` 允許 `http://localhost:8333`）、API 同源、建單到付款整條流程都正常。CSP 偵測本身是否有效，已在後台 E2E 用故意違規確認過（同一份 nginx 設定與偵測規則）
- 上面的情境是對**真實後端**驗證：庫存不足的錯誤訊息格式（`out of stock: SKU.` 換成商品名稱）、下架商品在 `/products/batch` 只回 `id` 與 `status`（SEC-11 的修正）、取消訂單後庫存釋放、付款成功後訂單輪詢到 `PAID`、別人的訂單回 404
- 既有檢查不受影響：332 個單元測試、build、oxlint、eslint、`vue-tsc --build`（含 e2e）、prettier 全過

<a id="step-12-測試留下的資料"></a>

### 測試留下的資料

- 商店看不到任何東西（已確認：分類 4 個、商品 25 件，沒有 E2E 字樣）
- 每次執行會多一個顧客帳號與一張訂單：`e2e-shopper-<時間>@example.com`（註冊是計畫要測的流程，所以每次要用新的 Email）。後台的使用者與訂單列表看得到，已付款、已出貨，沒有影響商店。商品與訂單沒有刪除 API，要清除只能直接操作資料庫
- 固定顧客帳號 `e2e-customer@example.com`（不會增加）、E2E 專用商品與分類（不會增加）

<a id="step-12-還沒做的"></a>

### 還沒做的

- 沒有放進 CI（需要整套後端與資料庫）；Playwright 的報告與追蹤檔（`test-results/`）、`e2e/.auth/` 已加進 `.gitignore`
- 付款失敗後重新付款、付款逾時、登入限流提示、帳號頁、登出所有裝置沒有 E2E（單元測試有涵蓋）
- 只用 Chrome 一個瀏覽器；手機只驗證了寬度 390px 沒有溢出，沒有在真實手機上看過
