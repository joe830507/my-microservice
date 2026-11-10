# vue-management Step 4：Layout

> 屬於 [vue-management 實作計畫](README.md)

- AdminLayout：側邊選單、頂部列（使用者名稱、登出）、麵包屑
- 403、404 頁面

**測試**：未登入進入後台頁被導到登入頁；不存在的路徑顯示 404。

## 完成內容（2026-11-10）

| 檔案 | 內容 |
| ---- | ---- |
| `src/layouts/AdminLayout.vue` | 側邊選單（品牌名稱 + `el-menu`）、頂部列（麵包屑、使用者名稱、登出）、`<RouterView />` |
| `src/router/index.ts` | 路由改成巢狀：`/` 是 AdminLayout，後台頁面放在 `adminChildren`；新增 `/403`、404（catch-all）、`menuItems`、`installTitleUpdater` |
| `src/views/HomeView.vue` | 改成後台首頁（歡迎訊息）；登出按鈕移到頂部列 |
| `src/views/NotFoundView.vue`、`ForbiddenView.vue` | `el-result` + 「回首頁」按鈕 |
| `src/App.vue` | 全站基本樣式：拿掉 body 外距、字型、背景用 Element Plus 色彩變數 |

### 設計

- **選單由路由產生**：`adminChildren` 裡設了 `meta.menu: true` 的路由會出現在側邊選單，標題用 `meta.title`。之後的功能（分類、商品、訂單…）只要在 `adminChildren` 加路由，不用另外改選單
- **選單選中項目**：目前路徑等於選單路徑，或在它底下（`/products/1` 對應 `/products`）；`/products-x` 這種只是前綴相同的路徑不算
- **麵包屑**：第一層固定是「首頁」，接著是 `route.matched` 裡有 `meta.title` 的層級；最後一層（目前頁面）不放連結
- **瀏覽器分頁標題**：`installTitleUpdater` 在每次導覽後設成「頁面標題 - vue-management」
- **403、404 不需要登入**（`meta.public`），也不在 AdminLayout 裡：
  - 未登入時打錯網址直接顯示 404，不先導到登入頁
  - 403 頁面目前沒有地方會導過去：前端的角色檢查只在登入時做（非 ADMIN 不能登入）。之後如果後端 API 回 403，可以導到這裡
- **AdminLayout 用動態 import**：AdminLayout 會 import `router/index.ts` 的 `menuItems`、`APP_TITLE`；router 如果靜態 import AdminLayout 會形成循環，先載入 AdminLayout 的那一方（例如測試）會在模組初始化時讀到還沒定義的值

### 測試

| 測試檔 | 案例 |
| ------ | ---- |
| `layouts/__tests__/AdminLayout.spec.ts` | 4 個：頂部列顯示使用者名稱、選單列出 `menuItems` 且目前頁面被選中、麵包屑、登出（呼叫 `/auth/logout`、清除狀態、回登入頁） |
| `router/__tests__/guard.spec.ts` | 新增 3 個：不存在的路徑未登入也顯示 404、403 不需要登入、瀏覽器分頁標題 |
| `views/__tests__/StatusViews.spec.ts` | 2 個：404、403 顯示對應文字、不在 AdminLayout 裡、按「回首頁」回到首頁 |
| `__tests__/App.spec.ts` | 改成確認首頁顯示在 AdminLayout 裡 |

「未登入進入後台頁被導到登入頁」在 Step 3 的 `guard.spec.ts` 已經有測試。

### 遇到的問題

- **`Array.prototype.at` 型別檢查失敗**：專案的 TS `lib` 是 ES2020（Step 2 的 `Error.cause` 也是同一個原因），vitest 不做型別檢查所以測試照樣通過，`vue-tsc` 才報錯。改用 `items[items.length - 1]`
- **還沒在瀏覽器實際看過畫面**：後端沒有啟動，登入後才看得到 AdminLayout；版面只靠測試確認結構。之後啟動 compose 時要用 `npm run dev` 檢查版面（含窄螢幕）（2026-11-10 已接上後端，用 API 實測過，見 [backend-integration-check.md](backend-integration-check.md)；畫面還沒在瀏覽器操作過）

## 最終檢查

vitest 82 個通過（新增 9 個）、`npm run build`（含 `vue-tsc`）通過、eslint、oxlint、prettier 都沒有問題。
