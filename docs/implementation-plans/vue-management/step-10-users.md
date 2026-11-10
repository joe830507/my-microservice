# vue-management Step 10：使用者列表

> 屬於 [vue-management 實作計畫](README.md)

**測試**：分頁、篩選條件同步到 URL query。

## 後端的對應（實作前確認過程式碼）

`GET /api/v1/users`，限 ADMIN：

- 篩選：`keyword`（不分大小寫，比對 email 或顯示名稱，最多 254 字）、`status`（`ACTIVE`、`DISABLED`）、`role`（`ADMIN`、`CUSTOMER`）
- 分頁：`page`（從 0 開始）、`size`（1–100，預設 20）
- 排序：`sortBy`（`createdAt`、`email`、`displayName`）、`sortDirection`；預設最新註冊的排前面
- 項目：`id`、`email`、`displayName`、`status`、`roles`、`createdAt`
- 雛型階段只有列表：不能修改角色或停用（計畫的「後端前置問題」已決定）

## 完成內容（2026-11-10）

| 檔案 | 內容 |
| ---- | ---- |
| `src/features/users/types.ts` | 使用者、分頁、查詢參數的型別；`Role`、`UserStatus` 沿用 `features/auth/types.ts` |
| `src/features/users/api.ts` | `getUsers` |
| `src/features/users/query.ts` | URL query ↔ 列表條件 ↔ API 參數 |
| `src/features/users/UserListView.vue` | 關鍵字、狀態、角色篩選；Email、顯示名稱、註冊時間排序；分頁；「查看訂單」 |
| `src/router/index.ts` | `/users`，出現在側邊選單 |

### 設計

- 和商品、訂單列表一樣用 `useRouteQuery`：條件放在 URL，搜尋與排序回到第一頁，不合法的值改回預設
- **查看訂單**：每一列可以直接到訂單列表，帶上 `?userId=` 只看這個使用者的訂單（Step 9 的訂單列表支援這個條件）
- 角色可能有多個（例如同時是 ADMIN 與 CUSTOMER），每個角色一個標籤

### 測試

| 測試檔 | 案例 |
| ------ | ---- |
| `features/users/__tests__/UserListView.spec.ts` | 11 個：URL 條件的解析（預設、合法、不合法）、轉成 URL 與 API 參數；預設參數、URL 條件轉成 API 參數並帶入表單、角色與狀態的顯示、搜尋、換頁、欄位排序、清除條件、讀取失敗、查看訂單 |

### 之後可以考慮

- **三個列表的 URL 條件處理很像**（`first`、`positiveInt`、省略預設值）：目前各自寫在 `query.ts`，之後如果再多一個列表，可以抽出共用的工具
- **訂單列表顯示 email**：order-service 只回 `userId`；可以批次查使用者，或在訂單列表上提供「查看使用者」

## 最終檢查

vitest 254 個通過（新增 11 個）、`npm run build`（含 `vue-tsc`）通過、eslint、oxlint、prettier 都沒有問題。
