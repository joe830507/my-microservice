# vue-customer Step 1：建立專案

> 屬於 [vue-customer 實作計畫](README.md)

- 設定檔從 vue-management 複製（TypeScript、Router、Pinia、Vitest、ESLint、oxlint、Prettier 相同的設定），不再跑 `npm create vue@latest`；因此 `tsconfig.vitest.json` 等 Step 1–5 在後台遇到的坑不會再踩
- 拿掉 Element Plus 與 `unplugin-auto-import`、`unplugin-vue-components`（前台自己寫元件）；對應拿掉 `tsconfig.vitest.json`、`eslint.config.ts`、`.prettierignore` 裡自動產生型別檔的設定
- `vite.config.ts`：port 5174、proxy `/api` → `http://localhost:8080`；`.env.development` 的 `VITE_API_BASE=/api/v1`
- `styles/tokens.css`：顏色、字級、間距、圓角的 CSS 變數；`styles/base.css`：重設、`.container`、`.btn`。淺色主題、mobile-first（基本樣式以手機為準，768px 以上才加強）
- `StoreLayout`：頂部列（商店名稱、搜尋框、購物車、登入）、內容區、頁尾；手機搜尋框換到第二列。搜尋、購物車、登入目前是假的連結，分別在 Step 4、6、3 接上
- 路由：`/` 在 `StoreLayout` 裡，萬用路由顯示 404（不需要登入）；`meta.requiresAuth` 與 `meta.title` 的型別先宣告，守衛在 Step 2 加入
- 商店名稱 `APP_TITLE = 'Joe 商城'` 放在 `router/index.ts`（暫定，之後想換只改這一處）

## 完成內容

**測試**（`npm run test:unit`，3 個）：`App.spec.ts`（首頁在 StoreLayout 裡、未知網址顯示 404）、`StoreLayout.spec.ts`（頂部列、內容區、頁尾）。`npm run build`（vue-tsc + vite build）、oxlint、eslint 都通過。
