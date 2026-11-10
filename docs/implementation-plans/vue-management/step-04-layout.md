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
- **瀏覽器分頁標題**：`installTitleUpdater` 在每次導覽後設成「頁面標題 - 商城管理後台」（原本是 vue-management，見下方「系統名稱」）
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

## 管理頁面改版（2026-11-10）

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

## 系統名稱（2026-11-10）

畫面上顯示的名稱從專案名稱 `vue-management` 改成「商城管理後台」：技術名稱（Vue）對使用者沒有意義，管理系統通常用「品牌或用途 + 後台」命名；之後有店名時把「商城」換成店名即可。

- 名稱集中在 `src/router/index.ts` 的 `APP_TITLE`：側邊欄、瀏覽器分頁標題（「頁面標題 - 商城管理後台」）、登入頁都用它；`index.html` 的 `<title>` 也改了
- 登入頁的說明改成「商品、訂單與庫存管理」，避免和名稱重複「後台管理」
- **沒改的**：repo、資料夾、`package.json` 的名稱仍是 `vue-management`；localStorage 的 key `vue-management.refreshToken` 是內部用的，改了會讓已登入的人都被登出

## 字體放大（2026-11-10）

使用者覺得整體字體偏小。做法是在 `src/styles/theme.css` 統一調整，不在各頁面個別改：

- Element Plus 的字級變數各加 2px：`--el-font-size-base` 14 → 16px、`small` 13 → 15px、`extra-small` 12 → 14px、`medium` 16 → 18px、`large` 18 → 20px、`extra-large` 20 → 22px；`body` 也用 `--el-font-size-base`
- 元件高度跟著加高（`--el-component-size` 32 → 36px、`large` 40 → 44px、`small` 24 → 28px），放大後的字不會擠
- 自己寫死的字級（12、13、14、16、18、20px）都改成上面的變數，之後要再調只改一個地方；標題、數字卡片這類大字改成 24、28、42px
- **Element Plus 有些元件不用字級變數，另外覆寫**：`el-tag`（12px）、`el-breadcrumb`、`el-select__wrapper` 與 `--el-select-input-font-size`、`el-pagination`、`el-alert`、`el-dialog` 內容、`el-message`、`el-descriptions` 的儲存格、`el-timeline` 的時間（都是寫死 14px 或 12px）
- **原生 `<button>` 不會繼承頁面字級**（瀏覽器預設約 13px）：首頁的數字卡片、小卡片、待辦項目是自己做的按鈕，全域加上 `button { font: inherit }`
- 用 headless Chrome 截圖確認首頁、商品列表、訂單詳情
