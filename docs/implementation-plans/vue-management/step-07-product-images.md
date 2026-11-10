# vue-management Step 7：商品圖片

> 屬於 [vue-management 實作計畫](README.md)

- 流程：選檔 → 前端先檢查類型與大小 → `upload-url` → 帶 `requiredHeaders` PUT → 登記
- 設定主圖、拖曳排序、刪除

**測試**：類型或大小不符時不發請求；PUT 失敗不會登記；排序送出的順序正確。

## 後端的對應（實作前確認過程式碼）

| API | 說明 |
| --- | ---- |
| `POST /products/{id}/images/upload-url` | body `{ contentType, contentLength }`；回 `objectKey`、`uploadUrl`（presigned PUT，10 分鐘）、`requiredHeaders`（`Content-Type`、`Content-Length`）、`expiresAt`。只接受 `image/jpeg`、`image/png`、`image/webp`，最大 5 MB（`storage.allowed-content-types`、`storage.max-size`） |
| PUT `uploadUrl` | 直接上傳到物件儲存（本機是 SeaweedFS `:8333`），不經過 Gateway |
| `POST /products/{id}/images` | 登記 `{ objectKey, altText }`；後端確認檔案存在且符合限制後，把物件從 `tmp/` 搬到 `products/`；第一張自動成為主圖 |
| `GET /products/{id}/images` | 依 `sortOrder` 排序 |
| `POST /products/{id}/images/{imageId}/primary` | 回傳整個列表 |
| `PUT /products/{id}/images/order` | `{ imageIds }`，必須剛好是這個商品的所有圖片，否則 `PRODUCT_IMAGE_IDS_MISMATCH`；回傳整個列表 |
| `DELETE /products/{id}/images/{imageId}` | 上架中（`ACTIVE`）的商品不能刪除主圖（`PRODUCT_IMAGE_PRIMARY_UNDELETABLE`）；其他狀態刪除主圖後**不會自動補上新的主圖** |

- 已封存的商品：所有圖片操作都回 `PRODUCT_ARCHIVED`
- Step 0 已實測 SeaweedFS 的 CORS：預設允許所有來源，上傳不能帶 credentials

## 完成內容（2026-11-10）

| 檔案 | 內容 |
| ---- | ---- |
| `src/features/products/images/types.ts` | 圖片、上傳網址的型別 |
| `src/features/products/images/api.ts` | 圖片 API、`putToStorage`、完整上傳流程 `uploadProductImage` |
| `src/features/products/images/validate.ts` | `validateImageFile`（類型、大小）、`moveItem` |
| `src/features/products/images/messages.ts` | 圖片錯誤代碼的訊息，沒有對應時沿用商品的訊息 |
| `src/features/products/images/ProductImages.vue` | 圖片區塊：上傳（含進度）、主圖標籤、左右移動、拖曳排序、設為主圖、刪除 |
| `src/features/products/ProductEditView.vue` | 編輯頁加上圖片區塊（新增頁沒有，商品建立後才能上傳）；封存時唯讀 |

### 設計

- **上傳流程**：申請上傳網址 → PUT 到物件儲存 → 登記。PUT 失敗就不登記，避免留下指向不存在檔案的圖片；上傳完重新讀取列表（第一張自動成為主圖，順序以後端為準）
- **前端先檢查**：類型（JPEG、PNG、WebP）、空檔案、大小（5 MB），不合格的不申請上傳網址；限制和後端設定一致，後端仍會再檢查
- **物件儲存用另一個 axios 實例**（`storageClient`）：
  - 不能帶我們 API 的 `Authorization`，auth 攔截器只掛在 `apiClient`
  - `withCredentials: false`：SeaweedFS 回 `Access-Control-Allow-Origin: *`，帶 credentials 會被瀏覽器拒絕
  - 網址是完整的 presigned URL，不套用 `baseURL`
  - 錯誤是 S3 的 XML，不是 ProblemDetail，統一轉成前端的 `IMAGE_UPLOAD_FAILED`
- **`Content-Length` 不手動設定**：它是瀏覽器禁止設定的 header，會依 body 自動帶上；簽章裡的值就是檔案大小，所以結果一致。`requiredHeaders` 裡其他的 header（`Content-Type`）照樣帶上
- **上傳進度**：axios 的 `onUploadProgress`（XHR），按鈕顯示「上傳中 n%」
- **排序**：先在畫面上換好順序再送出（所有圖片的 id），失敗時改回原本的順序；成功後用回應的列表為準
  - 拖曳用 HTML5 drag and drop，不另外加套件
  - 每張圖片也有「←」「→」按鈕：不能拖曳時（觸控裝置、鍵盤操作）的替代方式
- **同一時間只做一件事**：上傳、排序、設主圖、刪除進行中時，其他操作的按鈕停用，避免互相覆蓋
- **沒有主圖時提醒**：例如刪除了主圖，提示「上架前要先設定主圖」；刪除主圖前的確認訊息也會提醒
- **刪除**：成功或失敗都重新讀取列表
- 沒有做替代文字（`altText`）的編輯：計畫沒有列，API 已經有（`PUT /images/{imageId}`），需要時再補

### 測試

| 測試檔 | 案例 |
| ------ | ---- |
| `features/products/images/__tests__/ProductImages.spec.ts` | 25 個：`validateImageFile` 6 種情況、`moveItem`；依順序顯示並標出主圖、沒有主圖的提醒、唯讀；完整上傳流程（三個步驟的順序與內容、不手動帶 `Content-Length`）、`storageClient` 的設定、類型不符與超過大小不發請求、PUT 失敗不登記、後端拒絕申請；設為主圖、刪除、刪除主圖的確認訊息、上架中不能刪主圖；往後移、往前移、第一張與最後一張的按鈕停用、拖曳、排序失敗改回原順序 |

- `ProductEditView.spec.ts` 加上圖片 API 的預設 handler：編輯頁現在也會讀圖片

### 遇到的問題

- **vitest 4.1 的 jsdom 橋接不能傳 `Blob`、`File` 當請求 body**：MSW 的 XHR 攔截器會把請求轉成 Node 的 `Request`，vitest 轉換 jsdom Blob 時找不到內部資料（`Cannot read properties of undefined (reading '_buffer')`），連單純的 `new Request(url, { body: new Blob(...) })` 都會失敗。這是測試環境的問題，瀏覽器不受影響。做法：
  - 上傳本體這一步在測試裡 `vi.spyOn(storageClient, 'put')`，驗證網址、檔案、header
  - 申請網址、登記等我們的 API 照樣用 MSW
  - `storageClient` 因此匯出，註解寫明原因
- **還沒在瀏覽器實際上傳過**：寫完時 compose 沒有啟動。實際操作時要特別確認 presigned PUT 的 CORS 與簽章（`Content-Type` 要和申請時一致）

## 最終檢查

vitest 194 個通過（新增 25 個）、`npm run build`（含 `vue-tsc`）通過、eslint、oxlint、prettier 都沒有問題。
