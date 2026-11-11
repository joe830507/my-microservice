# vue-customer Step 7：結帳、建立訂單

> 屬於 [vue-customer 實作計畫](README.md)

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

## 完成內容

**測試**（`npm run test:unit`，全部 258 個）：
- `messages.spec.ts`：各錯誤代碼的訊息、SKU／id 換名稱、對不到時說「部分商品」且不外露識別碼、`refresh`／`uncertain` 旗標
- `CheckoutView.spec.ts`：
  - 顯示：重新查價格與總計、無法購買的不列出不計入、沒有可結帳商品、空購物車、查詢失敗與重試、未登入導到登入頁
  - 送出：body 正確（不含價格、不含無法購買的商品）、成功後只移除已下單的商品並 `replace` 到付款頁、連點三次只送出一次、庫存不足（購物車不變、可再送出）、商品已下架（重新查後標成無法購買）、兩種 503、網路中斷（不確定提示與連結）、`VALIDATION_FAILED`

`npm run build`、oxlint、eslint 通過。

**尚未用真的後端驗證**：測試用 MSW 模擬，還沒有實際建立過一張訂單。要特別確認的是後端錯誤 `detail` 的格式真的是 `out of stock: SKU-A.`、`...: <id>.`（訊息轉換靠這個格式，對不到時會退回「部分商品」，不會壞）。
