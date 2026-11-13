# vue-customer（商店前台）

顧客瀏覽商品、購物車、註冊登入、結帳、模擬付款與訂單查詢。技術與 scripts 以 [package.json](package.json) 為準。

## 閱讀入口

- [元件關係圖與職責說明](../../docs/component-relationships/vue-customer.md)：路由、頁面、store、API 與關鍵流程。
- [實作與驗收紀錄](../../docs/implementation-plans/vue-customer/README.md)：Step 0–12、環境準備及 E2E 操作。
- [系統導讀](../../docs/architecture/system-guide.md)：前後端與事件的整體分工。
- [專案執行入口](../../README.md)：後端、JWT 金鑰、開發管理員與基礎設施。

## 本機開發

在本資料夾執行，Node 版本依 package.json 的 engines：

```sh
npm ci
npm run dev
```

預設 http://localhost:5174，Vite 將 `/api` 代理至 localhost:8080 的 Gateway。API base 由 VITE_API_BASE 決定；環境值與後端啟動方式見實作紀錄。正式部署由 nginx 提供 SPA history fallback 與 API 代理。

## 建置與檢查

```sh
npm run build
npm run test:unit -- --run
npm run test:browser
npm run test:e2e
```

build 同時執行型別檢查與 Vite 建置；test:browser 是獨立瀏覽器測試，E2E 需要對應後端、帳號與資料。環境與覆蓋範圍請讀實作紀錄，這裡的指令不代表已重新驗收。`npm run lint` 會自動修正檔案，執行後需檢查差異。
