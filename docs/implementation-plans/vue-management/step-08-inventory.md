# vue-management Step 8：庫存

> 屬於 [vue-management 實作計畫](README.md)

- 放在商品編輯頁：依商品 SKU 查詢與調整庫存

**測試**：調整成功後重新讀取；SKU 尚未建立庫存時的顯示。

## 後端的對應（實作前確認過程式碼）

| API | 說明 |
| --- | ---- |
| `GET /api/v1/inventory/{sku}` | 公開；回 `sku`、`available`（可賣，不含已預扣）、`reserved`（已預扣、待付款）、`updatedAt`；還沒建立回 404 `STOCK_NOT_FOUND` |
| `PUT /api/v1/inventory/{sku}` | 限 ADMIN；body `{ available }`（0 以上的整數），直接設定盤點後的可賣數量；不存在就建立（201），存在就更新（200）；`reserved` 不能從這裡改 |

- **沒有版本檢查**：以最後一次設定為準
- 雛型階段 inventory-service 不檢查 product-service 是否有這個 SKU

## 完成內容（2026-11-10）

| 檔案 | 內容 |
| ---- | ---- |
| `src/features/products/inventory/api.ts` | `getStock`（404 `STOCK_NOT_FOUND` 轉成 `null`）、`setStock`；SKU 放在路徑上會編碼 |
| `src/features/products/inventory/ProductInventory.vue` | 顯示可賣、已預扣、更新時間；設定可賣數量（建立或更新） |
| `src/features/products/ProductEditView.vue` | 編輯頁在圖片下方加上庫存區塊，用**已儲存的** SKU；封存時唯讀 |

### 設計

- **還沒建立庫存不是錯誤**：`getStock` 把 404 `STOCK_NOT_FOUND` 轉成 `null`，畫面顯示「還沒有建立庫存，設定可賣數量後就會建立」，按鈕是「建立庫存」；其他錯誤（例如 inventory-service 掛了）才顯示錯誤，而且不顯示表單
- **調整成功後重新讀取**：預扣數量可能同時有變化，以後端為準
- **用已儲存的 SKU**：表單上還沒儲存的 SKU 不算；商品儲存了新的 SKU 後重新讀取
- **修改 SKU 的影響**：庫存以 SKU 對應，修改商品的 SKU 後，舊 SKU 的庫存不會跟著搬過去。目前只在畫面上提示，要重新設定
- **前端檢查**：可賣數量要是 0 以上的整數（`el-input-number` 限制最小值與整數，清空時另外檢查）
- 封存的商品只顯示庫存，不能調整

### 測試

| 測試檔 | 案例 |
| ------ | ---- |
| `features/products/inventory/__tests__/ProductInventory.spec.ts` | 9 個：顯示可賣與已預扣、調整成功後重新讀取、還沒建立時的提示與建立、沒填數量不發請求、儲存失敗、讀取失敗、SKU 編碼、SKU 改了重新讀取、唯讀 |

- `ProductEditView.spec.ts` 加上庫存 API 的預設 handler：編輯頁現在也會讀庫存

### 之後可以考慮

- **修改 SKU 時庫存跟著搬**：目前要手動重新設定；可以在 product-service 修改 SKU 時通知 inventory-service，或改成以商品 id 對應庫存
- **庫存設定沒有版本檢查**：兩個管理員同時調整時，後送出的會蓋掉先送出的；盤點情境通常可以接受

## 最終檢查

vitest 203 個通過（新增 9 個）、`npm run build`（含 `vue-tsc`）通過、eslint、oxlint、prettier 都沒有問題。
