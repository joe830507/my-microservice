# vue-customer Step 5：商品詳情

> 屬於 [vue-customer 實作計畫](README.md)

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

## 完成內容

**測試**（`npm run test:unit`，全部 210 個）：
- `cart/store.spec.ts`：加入、合併、上限 99、數量取整數、重新整理後還在、內容壞掉／不合法項目、localStorage 讀寫失敗
- `QuantityInput.spec.ts`：按鈕增減、邊界停用、各種不合法輸入
- `ImageGallery.spec.ts`：沒有圖、單張、主圖排前面、切換、換商品回第一張
- `ProductDetailView.spec.ts`：資訊與折扣顯示、HTML 說明不被執行、404／400／其他錯誤與重試、庫存的各種狀態（有、少、0、`STOCK_NOT_FOUND`、查詢失敗與重試）、圖片失敗、加入購物車（數量、合併、不超過可賣數量、上限 99、缺貨不能加）
- `StoreLayout.spec.ts`：頂部列購物車件數

`npm run build`、oxlint、eslint 通過。

**測試抓到的問題**：購物車 `add(id, NaN)` 會把數量存成 `NaN`（`Math.max(1, NaN)` 是 `NaN`），已修正。

**尚未用真的後端驗證**：測試用 MSW 模擬，還沒有實際對 product-service、inventory-service 看過一次（特別是公開的 `GET /inventory/{sku}` 經過 Gateway、非上架商品回 404）。
