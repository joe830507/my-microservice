# vue-management Step 6：商品管理

> 屬於 [vue-management 實作計畫](README.md)

- 列表：篩選、分頁，條件同步到 URL query
- 新增、編輯表單；上架、下架、封存

**測試**：狀態按鈕依目前狀態啟用或停用；封存後不能編輯；上架失敗（例如沒有主圖）顯示錯誤碼。

## 後端的對應（實作前確認過程式碼）

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

## 完成內容（2026-11-10）

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

### 設計

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

### 測試

| 測試檔 | 案例 |
| ------ | ---- |
| `features/products/__tests__/query.spec.ts` | 7 個：預設值、合法參數、不合法參數改回預設、重複參數取第一個、URL 省略預設值、轉成 API 參數 |
| `features/products/__tests__/status.spec.ts` | 16 個：四種狀態可執行的操作、各錯誤代碼的訊息 |
| `features/products/__tests__/ProductListView.spec.ts` | 12 個：預設參數、URL 條件轉成 API 參數並帶入表單、列表內容、讀取失敗；搜尋寫進 URL 並回第一頁、最低大於最高、價格不是數字、換頁、清除條件、欄位排序、上一頁、進入編輯頁 |
| `features/products/__tests__/ProductEditView.spec.ts` | 18 個：新增送出內容並進入編輯頁、必填驗證、原價低於售價、SKU 重複；編輯帶入目前的值與 `version`、`VERSION_CONFLICT`、商品不存在、未儲存的提醒；四種狀態的按鈕、封存後不能編輯、上架成功並帶新的 `version`、沒有主圖、沒有分類、封存取消、封存確認 |
| `layouts/__tests__/AdminLayout.spec.ts` | 新增 1 個：巢狀頁面的麵包屑與選單 |

### 遇到的問題

- **又是 ES2020 的 `lib`**：測試裡用了 `Array.prototype.at`，`vue-tsc` 才報錯（vitest 不做型別檢查）。改成自己寫 `lastRequest()`
- **Element Plus 的型別比較通用**：`el-table` 的 `sort-change` 參數 `prop` 可能是 `null`；`el-tree-select` 的 `disabled` 函式參數是 `TreeNodeData`。都在函式裡自己判斷或轉型
- **動態 import 的頁面**：按「編輯」導到編輯頁時要等元件載入，`flushPromises` 不夠，測試改用 `vi.waitFor`
- **還沒接真正的後端測試**：寫完時 compose 沒有啟動

### 發現的後端問題（沒有修改）

- **`GET /products`、`GET /products/{id}` 公開，而且回傳所有狀態**：未登入的人也能列出草稿、已封存的商品。後台需要看到全部，但前台應該只看得到上架中的商品。之後做前台或整理後端時再決定（例如公開 API 只回 `ACTIVE`，後台另外用 ADMIN 的列表）

## 最終檢查

vitest 169 個通過（新增 54 個）、`npm run build`（含 `vue-tsc`）通過、eslint、oxlint、prettier 都沒有問題。
