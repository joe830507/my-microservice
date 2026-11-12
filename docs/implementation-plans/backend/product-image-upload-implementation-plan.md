# 商品圖片上傳設計、實作與驗收

> 建立日期：2026-11-09
> 分支：`feature/product-image-upload`
> 設計：[圖片上傳設計](#image-design)（流程、關鍵決定、API、錯誤碼集中於本文後段；前段記錄步驟與實作結果）
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

> 原始設計整合於[設計與取捨](#image-design)。其中的「現況」與候選技術描述屬設計時點；實際採用方案與驗收見本文件前段。

---

<a id="image-design"></a>

## 商品圖片上傳設計（MinIO / S3）

> **2026-11-09 更新：本機改用 SeaweedFS**。MinIO 已不提供免費映像（Docker Hub 不存在、quay.io 需要授權），
> 改用同樣相容 S3 的 SeaweedFS；程式仍用 AWS SDK v2，流程與 API 不變。對應關係：
>
> | 設計裡的 MinIO 做法 | SeaweedFS 的做法 |
> |---|---|
> | `mc` 建 bucket | `weed shell` 的 `s3.bucket.create` |
> | `products/*` 匿名讀的 bucket policy | `s3.json` 的 `anonymous` 身分只給 `Read:product-images/products/*`（**結尾一定要 `*`**，沒有的話等於完全不開放） |
> | `tmp/` 的 lifecycle | `fs.configure -locationPrefix=/buckets/product-images/tmp/ -ttl=1d` |
> | port 9000 | port 8333 |

> 設計日期：2026-11-04
> 對應：`product-service-middleware-evaluation.md` 第 4 點「物件儲存 + CDN」
> 狀態：已實作（2026-11-09），步驟與驗證結果見 [product-image-upload-implementation-plan.md](#)

<a id="image-design-現況"></a>

### 現況

| 項目                 | 現況                                                     | 問題                                                  |
| -------------------- | -------------------------------------------------------- | ----------------------------------------------------- |
| `ProductImage.url` | 存完整網址（`@URL` 驗證）                              | 圖片放哪裡、怎麼上傳都沒有定案；換 CDN 網域要改資料庫 |
| 新增圖片 API         | `POST /api/v1/products/{productId}/images`，帶 `url` | 後端無法確認檔案真的存在、類型與大小是否合理          |

---

<a id="image-design-整體流程"></a>

### 整體流程

```
前端                     product-service                    MinIO
 │ 1. POST .../images/upload-url ─▶ 驗證商品、類型、大小
 │                                  產生 objectKey + presigned PUT
 │ ◀── { objectKey, uploadUrl, headers, expiresAt }
 │ 2. PUT uploadUrl（檔案本體）──────────────────────────────▶ tmp/...
 │ 3. POST .../images { objectKey, altText } ─▶ 驗證 key、HEAD 確認檔案存在
 │                                  Copy tmp/… → products/…、刪除 tmp
 │                                  寫入 product_image
 │ ◀── { id, url, ... }  （url = 公開網址前綴 + objectKey）
```

圖片流量不經過 product-service，後端只負責發簽名網址與登記。

---

<a id="image-design-關鍵設計決定"></a>

### 關鍵設計決定

| 項目           | 決定                                                                                                              | 原因                                                                        |
| -------------- | ----------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------- |
| 資料表存什麼   | **object key**（`products/{productId}/{uuid}.webp`）                                                      | 換 CDN 網域或換成 S3 時不用改資料，網址在回應時組                           |
| 孤兒檔案       | 先上傳到 `tmp/` 前綴，登記時複製到 `products/`；bucket 設 lifecycle 讓 `tmp/` 一天後自動刪除                | 拿到網址卻沒登記的檔案自動清掉，不用自己寫排程                              |
| 公開讀取       | 只開放 `products/*` 匿名讀，`tmp/` 私有                                                                       | 尚未登記的檔案不會被外部讀到                                                |
| 大小與類型限制 | 申請網址時帶 `contentType`、`contentLength`，兩者都**簽進 presigned URL**；登記時再用 `HEAD` 檢查一次 | presigned PUT 無法設 size range，簽入 Content-Length 後前端無法改傳其他大小 |
| SDK            | **AWS SDK v2**（`software.amazon.awssdk:s3`），MinIO 開 `forcePathStyle`                                | 之後換成 S3 不用改程式；沒有 POST policy，所以用簽 PUT 的方式               |
| 刪除圖片       | 刪資料列後以 afterCommit 刪除物件，失敗只記 log                                                                   | 與快取 evict 相同模式；漏刪的物件之後可用對帳清理                           |
| 商品快取       | 不需處理                                                                                                          | `GetProductByIdRes` 不含圖片欄位                                          |

<a id="image-design-限制值"></a>

#### 限制值

| 項目              | 值                                            |
| ----------------- | --------------------------------------------- |
| 允許類型          | `image/jpeg`、`image/png`、`image/webp` |
| 檔案大小上限      | 5 MB                                          |
| 上傳網址有效時間  | 10 分鐘                                       |
| `tmp/` 保留時間 | 1 天（bucket lifecycle）                      |

---

<a id="image-design-api"></a>

### API

<a id="image-design-新增申請上傳網址"></a>

#### 新增：申請上傳網址

`POST /api/v1/products/{productId}/images/upload-url`

```json
// Request
{ "contentType": "image/webp", "contentLength": 245760 }

// Response
{
  "objectKey": "tmp/products/{productId}/{uuid}.webp",
  "uploadUrl": "http://localhost:9000/...&X-Amz-Signature=...",
  "requiredHeaders": { "Content-Type": "image/webp", "Content-Length": "245760" },
  "expiresAt": "2026-11-04T12:10:00Z"
}
```

- 商品不存在 → 404 `PRODUCT_NOT_FOUND`；已封存 → `PRODUCT_ARCHIVED`（與現有圖片 API 規則一致）
- 副檔名由 `contentType` 決定，不採用前端提供的檔名
- 前端 PUT 時必須帶上 `requiredHeaders`，否則簽章不符

<a id="image-design-修改登記圖片"></a>

#### 修改：登記圖片

`POST /api/v1/products/{productId}/images`

- `AddOneNewProductImageReq.url` 改為 `objectKey`，移除 `@URL`
- 驗證步驟：
  1. key 必須以 `tmp/products/{productId}/` 開頭——**否則可以把別的商品的檔案登記到自己名下**
  2. `HEAD` 確認物件存在，且 Content-Type、大小符合限制
  3. Copy 到 `products/{productId}/{uuid}.{ext}`，刪除 tmp 物件
  4. 寫入 `product_image.object_key`（主圖、排序規則不變）
- 回應的 `ProductImageItem.url` 對外格式不變，由 `publicBaseUrl + "/" + objectKey` 組成

<a id="image-design-新增錯誤碼"></a>

#### 新增錯誤碼

| 錯誤碼                             | 情境                                        |
| ---------------------------------- | ------------------------------------------- |
| `IMAGE_CONTENT_TYPE_NOT_ALLOWED` | 類型不在允許清單                            |
| `IMAGE_TOO_LARGE`                | 超過大小上限                                |
| `IMAGE_OBJECT_KEY_INVALID`       | key 前綴不屬於此商品，或格式錯誤            |
| `IMAGE_UPLOAD_NOT_FOUND`         | HEAD 找不到檔案，或實際類型、大小與限制不符 |

---

<a id="image-design-程式結構"></a>

### 程式結構

```
config/StorageProperties        @ConfigurationProperties("storage")：endpoint、publicBaseUrl、bucket、accessKey、secretKey
config/StorageConfig            S3Client、S3Presigner bean
storage/ImageStorage            presignUpload / headObject / promote(tmp→products) / deleteAfterCommit / publicUrl
dto/productImage/createUploadUrl/CreateUploadUrlReq、CreateUploadUrlRes
db/migration/V4__product_image_object_key.sql   url → object_key（開發資料可直接清除）
```

`ImageStorage` 獨立出來，`ProductImageServiceImpl` 不直接碰 S3 SDK：測試好寫，之後換實作只改一處。

<a id="image-design-設定範例"></a>

#### 設定範例

```yaml
storage:
  endpoint: http://localhost:9000
  public-base-url: http://localhost:9000/product-images   # 之後換成 CDN 網域
  bucket: product-images
  access-key: ${STORAGE_ACCESS_KEY:minioadmin}
  secret-key: ${STORAGE_SECRET_KEY:minioadmin}
```

---

<a id="image-design-實作順序"></a>

### 實作順序

1. `infra/docker-compose.yml` 加 `minio`（9000 API、9001 console），以及一個 `mc` 初始化容器：建立 bucket、設定 `products/*` 匿名讀、設定 `tmp/` lifecycle
2. pom 加 `awssdk:s3`、`testcontainers-minio`；完成 `StorageProperties`、`StorageConfig`，確認應用程式能啟動
3. `ImageStorage` + upload-url API，先用 curl 手動 PUT 驗證流程
4. V4 migration，改寫登記圖片 API（驗證 key、HEAD、搬移）
5. 刪除圖片時一併刪除物件
6. 整合測試：申請網址 → 用 `HttpClient` 實際 PUT → 登記 → GET 公開網址取得檔案；以及類型、大小、key 不合法等錯誤情境

---

<a id="image-design-之後再處理"></a>

### 之後再處理

- 前面掛 CDN：只需把 `public-base-url` 換成 CDN 網域
- 縮圖 / 多尺寸：可由物件儲存事件觸發轉檔服務，不在本階段範圍
- 已登記但資料列被刪、物件漏刪的對帳清理
