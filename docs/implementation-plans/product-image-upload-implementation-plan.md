# 商品圖片上傳實作步驟

> 建立日期：2026-11-09
> 分支：`feature/product-image-upload`
> 設計：[product-image-upload-design.md](../design/product-image-upload-design.md)（流程、關鍵決定、API、錯誤碼都以設計文件為準，這裡只列步驟與實作細節）
> 狀態：Step 1–5 全部完成，並實際啟動驗證（2026-11-09）

## 進度

| Step | 內容 | 狀態 |
|---|---|---|
| 1 | compose 加物件儲存（SeaweedFS）與初始化；product-service 接上 S3 SDK（`StorageProperties`、`StorageConfig`） | 完成 |
| 2 | `ImageStorage` 與 `POST /api/v1/products/{productId}/images/upload-url` | 完成 |
| 3 | V4 migration（`url` → `object_key`）與改寫登記圖片 API | 完成 |
| 4 | 刪除圖片時一併刪除物件 | 完成 |
| 5 | 實際啟動驗證：上傳主圖 → 正常上架 → 下單 | 完成 |

每一步都附整合測試，測試通過後單獨 commit。測試用 Testcontainers 跑真的物件儲存（SeaweedFS），實際 PUT 檔案，不用假的 S3。

---

## Step 1：物件儲存與 S3 SDK

| 項目 | 值 |
|---|---|
| 映像 | ~~`minio/minio`~~ → `chrislusf/seaweedfs:3.80`（S3 API 8333） |
| 初始化 | `object-storage-init`（`weed shell`）：建立 bucket `product-images`、`tmp/` TTL 1 天；`products/` 匿名讀設定在 `s3.json` |
| 帳密 | 本機用 `s3.json` 裡的開發帳號，可用環境變數覆蓋 |
| SDK | `software.amazon.awssdk:s3`（BOM 管版本）、`forcePathStyle(true)` |
| 設定 | `storage.endpoint`、`public-base-url`、`bucket`、`access-key`、`secret-key`、`upload-url-ttl`、`max-size`、`allowed-content-types` |

**測試**：context 啟動、`S3Client` 能列出測試容器裡的 bucket。

**已完成**：compose 的 `object-storage`、`object-storage-init`，`infra/seaweedfs/s3.json`；`StorageProperties`、`StorageConfig`；測試 `StorageConfigIntegrationTests`（2），測試用的 SeaweedFS 容器在 `TestcontainersConfiguration`。

- **改用 SeaweedFS**（`chrislusf/seaweedfs:3.80`，S3 port 8333）：MinIO 的映像已經拉不到，詳見設計文件開頭的對應表
- **AWS SDK 要關掉 chunked 上傳、只在必要時算 checksum**：SDK 2.30 起預設用 aws-chunked 加 checksum trailer 上傳，SeaweedFS 不會解開，會把 `5;chunk-signature=...` 整段當成檔案內容存下來（測試實際遇到）
- compose 驗證：`object-storage-init` 建立 bucket、`tmp/` 的 TTL 為 1 天，執行完就結束
- 測試時多一個 SeaweedFS 容器，product-service 的測試啟動時間多約 20 秒

## Step 2：申請上傳網址

- `ImageStorage`：`presignUpload`、`head`、`promote`（tmp → products）、`delete`、`publicUrl`；`ProductImageServiceImpl` 不直接碰 S3 SDK
- 限 ADMIN（和其他寫入 API 一致）；商品不存在 404、已封存 `PRODUCT_ARCHIVED`
- `contentType`、`contentLength` 都簽進 presigned URL

**測試**：實際用回傳的網址 PUT 檔案成功；帶不同的 Content-Length 或 Content-Type 被物件儲存拒絕；類型不允許、超過大小、商品不存在、非 ADMIN。

**已完成**：`ImageStorage.presignUpload`、`publicUrl`，`ProductImageController.createUploadUrl`，錯誤碼 `IMAGE_CONTENT_TYPE_NOT_ALLOWED`、`IMAGE_TOO_LARGE`；測試 `ProductImageUploadUrlIntegrationTests`（19），權限測試加上新的 API。

- 用回傳的網址實際 PUT：帶 `requiredHeaders` 成功，檔案內容與大小、類型正確
- **SeaweedFS 會檢查簽進網址的大小與類型**：申請 1 KB 卻上傳 2 KB、或換成 `text/html`，都回 403 且檔案不會存下來
- 還沒登記的 `tmp/` 檔案匿名讀取回 403
- 類型不分大小寫，簽進網址的統一成小寫；剛好 5 MB 可以，多 1 byte 回 `IMAGE_TOO_LARGE`
- 申請網址不寫資料庫，回 200

## Step 3：登記圖片

- V4：`url` 改成 `object_key`；開發資料直接清除（現有的 `url` 都是手動填的，沒有對應的檔案）
- 依設計文件的四個驗證步驟；回應的 `url` 由 `publicBaseUrl + "/" + objectKey` 組成，對外格式不變
- 主圖、排序規則不變

**測試**：完整流程（申請 → PUT → 登記 → 匿名 GET 公開網址拿到同樣的檔案）、tmp 物件已刪除、別的商品的 key、不存在的 key、上傳後的實際類型或大小不符。

**已完成**：`V4__product_image_object_key.sql`、`ImageStorage.head`、`promote`、`delete`，改寫 `ProductImageService.addOneNewProductImage`，錯誤碼 `IMAGE_OBJECT_KEY_INVALID`、`IMAGE_UPLOAD_NOT_FOUND`；測試 `ProductImageRegisterIntegrationTests`（17）。

- V4 刪掉原本手動填網址的圖片（物件儲存裡沒有對應的檔案），原本有圖片的上架商品改成 `INACTIVE`，重新上傳主圖後再上架
- key 用完整格式比對（`tmp/products/{productId}/{uuid}.{jpg|png|webp}`），`..`、別的商品、已經搬到 `products/` 的 key 都擋下
- 同一個檔案登記第二次：tmp 已經不在 → `IMAGE_UPLOAD_NOT_FOUND`；`object_key` 也有唯一約束
- 繞過上傳網址直接放進物件儲存的檔案，實際類型或大小不符一樣擋下
- 物件搬到 `products/` 之後交易 rollback：afterCompletion 刪掉物件，不留下沒有資料列的檔案
- 回應的 `url` 由 DTO 的 `from(image, urlOf)` 組成，service 傳 `imageStorage::publicUrl`
- **測試抓到的設定錯誤**：SeaweedFS 的匿名讀取規則要寫 `Read:product-images/products/*`，原本少了結尾的 `*`，`products/` 也讀不到（Step 2 的「tmp/ 回 403」其實是完全不開放）。用容器逐一試了五種寫法確認；本機的 `object-storage` 容器要重新建立才會讀到新的 `s3.json`

## Step 4：刪除圖片

刪資料列後在 afterCommit 刪除物件，失敗只記 log；交易 rollback 時不刪。

**測試**：刪除後物件不存在；刪除失敗（例如 rollback）時物件還在。

**已完成**：`ProductImageService.deleteOneProductImage` 在 afterCommit 刪除物件；測試 `ProductImageDeleteIntegrationTests`（4）：刪除後物件不在、請求被拒絕（上架中商品的主圖）時物件還在、物件儲存刪除失敗仍回 204、只刪該張圖片的物件。

- 商品不會被刪除（只會封存），所以沒有「刪商品時一併刪圖片」的情況

## Step 5：實際啟動驗證

用 compose 的物件儲存，經過 Gateway：申請網址 → PUT → 登記為主圖 → **正常上架**（不再直接改 DB）→ 下單 → 付款。

**結果**（六個服務 + compose 的 SeaweedFS，全部經過 Gateway；product-service 啟動時把本機 DB 遷移到 V4）：

| 情境 | 結果 |
|---|---|
| 沒有主圖就上架 | 400 `PRODUCT_PRIMARY_IMAGE_REQUIRED` |
| CUSTOMER 申請上傳網址 | 403 |
| ADMIN 申請上傳網址，用 curl PUT 3000 bytes 的檔案 | 200 |
| 登記為主圖 | 201，`url` 是 `http://localhost:8333/product-images/products/...` |
| 匿名讀取公開網址 | 拿到和上傳時完全相同的檔案 |
| 匿名讀取 tmp/ 的 key | 403（已經搬走，且 tmp/ 不公開） |
| 上架 | 200 `ACTIVE`；公開的圖片列表帶網址 |
| 下單 1 件 → 付款成功 | 訂單 `PAID`，庫存 2 / 0 |
| 刪除上架中商品的主圖 | 400 `PRODUCT_IMAGE_PRIMARY_UNDELETABLE`，公開網址仍可讀 |

之前兩次完整流程的實測都要直接在 DB 把商品改成 `ACTIVE`，這次不需要了。

---

## 之後再處理

- K8s 部署時，presigned URL 的主機名稱要是瀏覽器連得到的位址（和 product-service 內部連 MinIO 的位址可能不同），屆時加一個 `presign-endpoint` 設定
- 設計文件的「之後再處理」：CDN、縮圖、物件對帳清理
