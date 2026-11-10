# vue-customer Step 0：後端前置問題

> 屬於 [vue-customer 實作計畫](README.md)

- product-service：不是 ADMIN 的請求只看得到 `ACTIVE` 的商品
  - `GET /products`：自動加上 `status = ACTIVE`，忽略傳入的 `status`
  - `GET /products/{id}`、`/sku/{sku}`、`/{id}/images`：非上架中回 404 `PRODUCT_NOT_FOUND`
  - `GET /products/batch`：照舊回傳（帶 `status`），購物車靠它知道哪些商品已下架；它只有 id、SKU、名稱、價格、狀態，沒有說明與圖片。要更嚴格的話，非上架中的商品只回 `id` 與 `status`
  - 注意快取：商品詳情有 Redis 快取，判斷要在讀快取之後做，不能把「看不到」的結果快取起來
- `ProductListItem` 加 `primaryImageUrl`：一頁的商品一次查主圖（`WHERE product_id IN (...) AND is_primary`），不要每個商品各查一次
- `GET /products` 加 `includeSubcategories`：用分類樹展開子孫分類 id 再查
- 每一項各自整合測試、單獨 commit；更新 [microservice-architecture.md](../../architecture/microservice-architecture.md)

**測試**：未登入、CUSTOMER 看不到草稿與封存的商品（列表、單筆、圖片）；ADMIN 照舊看得到；列表的主圖網址正確、沒有主圖為 `null`；含子分類的篩選；vue-management 的測試仍然全部通過。

## 完成內容（2026-11-10）

三項各自一個 commit，都在 `feature/vue-customer` 分支：

| 項目 | commit | 測試 |
| ---- | ------ | ---- |
| 公開商品 API 只回上架中 | `d0c9790` | `ProductVisibilityIntegrationTests`（12） |
| 列表帶主圖網址 | `3c6fc55` | `ProductListPrimaryImageIntegrationTests`（3） |
| 分類篩選含子分類 | `331dbe2` | `ProductListSubcategoryIntegrationTests`（6） |

### 1. 公開商品 API 只回上架中

- **怎麼判斷 ADMIN**：controller 讀 Gateway 帶來的 `X-User-Roles`（和 order-service 的 `isAdmin` 一樣），沒登入就沒有這個 header。Gateway 會先移除用戶端送來的 `X-User-*`，所以無法偽造
- service 的讀取方法多一個 `includeUnpublished` 參數，controller 依角色傳入：
  - `getProductList`：不是 ADMIN 時把狀態條件固定成 `ACTIVE`，傳入的 `status` 不採用
  - `getProductById`、`getProductBySku`：**先讀詳情快取再判斷可見性**。快取存的是完整資料，ADMIN 讀過的草稿進了快取，訪客再讀仍然是 404
  - `getProductImages`：不是 ADMIN 時用 `existsByIdAndStatus(id, ACTIVE)` 判斷商品是否存在
- `GET /products/batch`：不變，購物車需要它的 `status`
- 後台（vue-management）帶著 ADMIN 的 token，行為不變；它的 261 個測試照樣通過
- **連帶修改的既有測試**：
  - `ProductDetailCacheIntegrationTests` 直接呼叫 service，測的是快取，改成傳 `includeUnpublished = true`
  - `ProductImageRegisterIntegrationTests` 有一個案例沒帶身分就查草稿商品的圖片列表，原本是 200、現在是 404；這個案例要驗證的是後台看到的圖片，改成帶 ADMIN 的 header

測試：未登入與 CUSTOMER 的列表只有上架中；傳 `status=DRAFT`、`INACTIVE`、`ARCHIVED` 也一樣；ADMIN 看全部且狀態篩選有效；單筆、SKU、圖片列表的可見性；ADMIN 先讀進快取後訪客仍是 404；批次查詢照舊帶狀態。

### 2. 列表帶主圖網址

- `ProductListItem` 加 `primaryImageUrl`，沒有主圖為 `null`
- 一頁查一次：`ProductImageRepository.findAllByProductIdInAndIsPrimaryTrue(ids)`，再用 `ImageStorage.publicUrl` 組成公開網址
- 測試直接把圖片寫進 DB，不需要物件儲存：有主圖、只有一般圖片、沒有圖片三種；分頁時只查這一頁的主圖；空列表

### 3. 分類篩選含子分類

- `ProductQueryRequest` 加 `includeSubcategories`（預設 `false`，後台的分類篩選不受影響）
- 為 `true` 時，從分類表讀出全部分類，依 `parentId` 往下展開子孫 id，再用 `categoryId IN (...)` 查；用記錄看過的 id 的集合避免資料有循環時無限迴圈
- **停用的子分類也包含在內**：商品能不能看到由商品狀態決定（前台只看得到上架中），分類停用不影響這裡
- 測試：從根分類、從中間分類展開；不帶參數或 `false` 只查該分類；葉節點分類；不存在的分類回空列表；和可見性一起用（子分類的草稿訪客看不到、ADMIN 看得到）

## 最終檢查

- product-service：151 個測試全部通過（新增 21 個）
- vue-management：261 個測試全部通過（前端沒有改動，確認後端改動不影響後台）
- `microservice-architecture.md` 已更新 product-service 的說明
