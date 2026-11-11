# vue-customer Step 4：商品列表

> 屬於 [vue-customer 實作計畫](README.md)

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

## 完成內容

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
