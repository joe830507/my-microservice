# vue-customer Step 6：購物車

> 屬於 [vue-customer 實作計畫](README.md)

- **store**（`features/cart/store.ts`，Step 5 建了最小版）：新增 `setQty`、`remove`、`removeMany`（結帳成功後移除已下單的商品會用到）。`add` 改成回傳 `boolean`
- **種類上限 50**：後端建單 API（`CreateOrderReqDto`）一張訂單最多 50 種商品、單項 1–99 件，購物車一開始就用同樣的限制，不要等到結帳才失敗。已滿時新的商品加不進去（商品詳情頁顯示「購物車最多放 50 種商品」），已在購物車裡的商品仍可增加數量；從 localStorage 讀取時超過的部分丟掉
- **購物車頁** `/cart`：進來時用 `GET /products/batch?ids=` 查最新的名稱、價格、狀態，列表依購物車順序。localStorage 的 `productId`、`qty` 以外的欄位一概不信
  - `ids` 要寫成重複的 key（`ids=a&ids=b`）：axios 預設會寫成 `ids[]=a&ids[]=b`，Spring 的 `@RequestParam List<UUID>` 綁不到，所以設 `paramsSerializer: { indexes: null }`，測試直接檢查請求的查詢字串
  - batch 只回 id、SKU、名稱、價格、狀態，沒有圖片與原價，所以購物車列表不顯示圖片、折扣
  - 狀態不是 `ACTIVE`（草稿、下架、封存）或查不到（商品已被刪除）：標成「無法購買」（查不到顯示「商品已不存在」），不能改數量、不計入總計，可以單獨移除或「全部移除」
  - 修改數量（`QuantityInput`，1–99）、移除：立刻反映在小計、總計與 localStorage；商品資料只在進頁面時查一次
  - 沒有可購買的商品時「前往結帳」停用；空的購物車顯示提示與「去逛逛」；查詢失敗顯示錯誤與重試，購物車內容不受影響
- **金額用「分」計算**（`lines.ts`）：`0.1 × 3` 不會顯示成 `0.30000000000000004`。實際金額以建單時的快照為準，這裡只是預覽
- `/checkout` 路由與 `CheckoutView` 先放佔位頁（Step 7 實作），標 `requiresAuth`：按「前往結帳」未登入會導到登入頁，登入後回到結帳頁
- 頂部列的件數（Step 5 做的）會跟著數量與移除更新；它算的是購物車所有商品的數量加總，包含已經無法購買的

## 完成內容

**測試**（`npm run test:unit`，全部 233 個）：
- `store.spec.ts`：加入與合併、50 種上限（含已有的商品仍可加）、`setQty`（範圍、NaN、不在車裡）、`remove`／`removeMany` 且重新整理後不會回來、壞掉的 localStorage、讀寫失敗
- `lines.spec.ts`：配對最新資料、各種狀態的可購買判斷、小計與總計（只算可購買、浮點誤差、空購物車）
- `CartView.spec.ts`：空購物車、`ids=a&ids=b`、價格以最新查詢為準、修改數量即時更新並保存、移除、無法購買的商品（標示、不計入、單獨與全部移除、全部無法購買不能結帳）、查詢失敗與重試、未登入前往結帳導到登入頁並帶 `redirect`
- `ProductDetailView.spec.ts`：購物車已滿的提示

`npm run build`、oxlint、eslint 通過。

**尚未用真的後端驗證**：測試用 MSW 模擬，還沒有實際對 product-service 的 `/products/batch` 看過一次（尤其 `ids` 的格式，以及非上架的商品是否照預期帶 `status` 回來）。
