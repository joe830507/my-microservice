# vue-management Step 5：分類管理

> 屬於 [vue-management 實作計畫](README.md)

- 樹狀顯示、新增（可選父分類）、修改、刪除

**測試**：表單驗證；刪除失敗時依錯誤碼顯示訊息。

## 後端補強（2026-11-10）

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

## 完成內容（2026-11-10）

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

### 設計

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

### 測試

| 測試檔 | 案例 |
| ------ | ---- |
| `features/categories/__tests__/tree.spec.ts` | 5 個：組樹與順序、葉節點沒有 `children`、找不到父分類、循環資料、`subtreeIds` |
| `features/categories/__tests__/messages.spec.ts` | 11 個：新增、修改、刪除各錯誤代碼的訊息 |
| `features/categories/__tests__/CategoriesView.spec.ts` | 17 個：樹狀顯示含停用、讀取失敗；新增（送出內容、去空白、成功後關閉並重新讀取）、新增子分類帶入上層、必填驗證、只有空白、5 種錯誤的 slug、slug 重複；編輯帶入目前的值與 `version`、`VERSION_CONFLICT`；刪除成功、取消不發請求、`DATA_CONFLICT` 顯示原因 |

### 遇到的問題

- **`ElMessage`、`ElMessageBox` 型別找不到**：它們由 unplugin-auto-import 自動引入，宣告在 `src/auto-imports.d.ts`。`tsconfig.vitest.json` 原本只 include 測試檔與 `env.d.ts`，被測試 import 的元件改用這個專案檢查型別時看不到宣告。include 加上 `auto-imports.d.ts`、`components.d.ts`
- **`auto-imports.d.ts` 是執行 vite（dev、build、vitest）時才更新**：第一次用到 `ElMessage` 後要跑過一次，檔案才會加上宣告；這個檔案有 commit
- **`el-table` 插槽的 `row` 是通用的 `DefaultRow`**：在插槽解構上標型別會和 Element Plus 的插槽型別衝突，改成呼叫時 `row as CategoryNode`
- **表單驗證訊息要等下一次渲染**：`validate()` 失敗後訊息不會馬上出現在 DOM，測試要用 `vi.waitFor`
- **還沒接真正的後端測試**：寫完時 compose 沒有啟動。前端只用 MSW 驗證；後端 API 已用整合測試（Testcontainers）驗證（2026-11-10 已接上後端，用 API 實測過，見 [backend-integration-check.md](backend-integration-check.md)；畫面還沒在瀏覽器操作過）

## 最終檢查

- 前端：vitest 115 個通過（新增 33 個）、`npm run build`（含 `vue-tsc`）通過、eslint、oxlint、prettier 都沒有問題
- 後端：product-service 全部 130 個通過（含新 API 與刪除行為的測試）
