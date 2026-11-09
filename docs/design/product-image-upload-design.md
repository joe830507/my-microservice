# 商品圖片上傳設計（MinIO / S3）

> **2026-11-09 更新：本機改用 SeaweedFS**。MinIO 已不提供免費映像（Docker Hub 不存在、quay.io 需要授權），
> 改用同樣相容 S3 的 SeaweedFS；程式仍用 AWS SDK v2，流程與 API 不變。對應關係：
>
> | 設計裡的 MinIO 做法 | SeaweedFS 的做法 |
> |---|---|
> | `mc` 建 bucket | `weed shell` 的 `s3.bucket.create` |
> | `products/*` 匿名讀的 bucket policy | `s3.json` 的 `anonymous` 身分只給 `Read:product-images/products/` |
> | `tmp/` 的 lifecycle | `fs.configure -locationPrefix=/buckets/product-images/tmp/ -ttl=1d` |
> | port 9000 | port 8333 |

> 設計日期：2026-11-04
> 對應：`product-service-middleware-evaluation.md` 第 4 點「物件儲存 + CDN」
> 狀態：實作中，步驟見 [product-image-upload-implementation-plan.md](../implementation-plans/product-image-upload-implementation-plan.md)

## 現況

| 項目                 | 現況                                                     | 問題                                                  |
| -------------------- | -------------------------------------------------------- | ----------------------------------------------------- |
| `ProductImage.url` | 存完整網址（`@URL` 驗證）                              | 圖片放哪裡、怎麼上傳都沒有定案；換 CDN 網域要改資料庫 |
| 新增圖片 API         | `POST /api/v1/products/{productId}/images`，帶 `url` | 後端無法確認檔案真的存在、類型與大小是否合理          |

---

## 整體流程

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

## 關鍵設計決定

| 項目           | 決定                                                                                                              | 原因                                                                        |
| -------------- | ----------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------- |
| 資料表存什麼   | **object key**（`products/{productId}/{uuid}.webp`）                                                      | 換 CDN 網域或換成 S3 時不用改資料，網址在回應時組                           |
| 孤兒檔案       | 先上傳到 `tmp/` 前綴，登記時複製到 `products/`；bucket 設 lifecycle 讓 `tmp/` 一天後自動刪除                | 拿到網址卻沒登記的檔案自動清掉，不用自己寫排程                              |
| 公開讀取       | 只開放 `products/*` 匿名讀，`tmp/` 私有                                                                       | 尚未登記的檔案不會被外部讀到                                                |
| 大小與類型限制 | 申請網址時帶 `contentType`、`contentLength`，兩者都**簽進 presigned URL**；登記時再用 `HEAD` 檢查一次 | presigned PUT 無法設 size range，簽入 Content-Length 後前端無法改傳其他大小 |
| SDK            | **AWS SDK v2**（`software.amazon.awssdk:s3`），MinIO 開 `forcePathStyle`                                | 之後換成 S3 不用改程式；沒有 POST policy，所以用簽 PUT 的方式               |
| 刪除圖片       | 刪資料列後以 afterCommit 刪除物件，失敗只記 log                                                                   | 與快取 evict 相同模式；漏刪的物件之後可用對帳清理                           |
| 商品快取       | 不需處理                                                                                                          | `GetProductByIdRes` 不含圖片欄位                                          |

### 限制值

| 項目              | 值                                            |
| ----------------- | --------------------------------------------- |
| 允許類型          | `image/jpeg`、`image/png`、`image/webp` |
| 檔案大小上限      | 5 MB                                          |
| 上傳網址有效時間  | 10 分鐘                                       |
| `tmp/` 保留時間 | 1 天（bucket lifecycle）                      |

---

## API

### 新增：申請上傳網址

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

### 修改：登記圖片

`POST /api/v1/products/{productId}/images`

- `AddOneNewProductImageReq.url` 改為 `objectKey`，移除 `@URL`
- 驗證步驟：
  1. key 必須以 `tmp/products/{productId}/` 開頭——**否則可以把別的商品的檔案登記到自己名下**
  2. `HEAD` 確認物件存在，且 Content-Type、大小符合限制
  3. Copy 到 `products/{productId}/{uuid}.{ext}`，刪除 tmp 物件
  4. 寫入 `product_image.object_key`（主圖、排序規則不變）
- 回應的 `ProductImageItem.url` 對外格式不變，由 `publicBaseUrl + "/" + objectKey` 組成

### 新增錯誤碼

| 錯誤碼                             | 情境                                        |
| ---------------------------------- | ------------------------------------------- |
| `IMAGE_CONTENT_TYPE_NOT_ALLOWED` | 類型不在允許清單                            |
| `IMAGE_TOO_LARGE`                | 超過大小上限                                |
| `IMAGE_OBJECT_KEY_INVALID`       | key 前綴不屬於此商品，或格式錯誤            |
| `IMAGE_UPLOAD_NOT_FOUND`         | HEAD 找不到檔案，或實際類型、大小與限制不符 |

---

## 程式結構

```
config/StorageProperties        @ConfigurationProperties("storage")：endpoint、publicBaseUrl、bucket、accessKey、secretKey
config/StorageConfig            S3Client、S3Presigner bean
storage/ImageStorage            presignUpload / headObject / promote(tmp→products) / deleteAfterCommit / publicUrl
dto/productImage/createUploadUrl/CreateUploadUrlReq、CreateUploadUrlRes
db/migration/V4__product_image_object_key.sql   url → object_key（開發資料可直接清除）
```

`ImageStorage` 獨立出來，`ProductImageServiceImpl` 不直接碰 S3 SDK：測試好寫，之後換實作只改一處。

### 設定範例

```yaml
storage:
  endpoint: http://localhost:9000
  public-base-url: http://localhost:9000/product-images   # 之後換成 CDN 網域
  bucket: product-images
  access-key: ${STORAGE_ACCESS_KEY:minioadmin}
  secret-key: ${STORAGE_SECRET_KEY:minioadmin}
```

---

## 實作順序

1. `infra/docker-compose.yml` 加 `minio`（9000 API、9001 console），以及一個 `mc` 初始化容器：建立 bucket、設定 `products/*` 匿名讀、設定 `tmp/` lifecycle
2. pom 加 `awssdk:s3`、`testcontainers-minio`；完成 `StorageProperties`、`StorageConfig`，確認應用程式能啟動
3. `ImageStorage` + upload-url API，先用 curl 手動 PUT 驗證流程
4. V4 migration，改寫登記圖片 API（驗證 key、HEAD、搬移）
5. 刪除圖片時一併刪除物件
6. 整合測試：申請網址 → 用 `HttpClient` 實際 PUT → 登記 → GET 公開網址取得檔案；以及類型、大小、key 不合法等錯誤情境

---

## 之後再處理

- 前面掛 CDN：只需把 `public-base-url` 換成 CDN 網域
- 縮圖 / 多尺寸：可由物件儲存事件觸發轉檔服務，不在本階段範圍
- 已登記但資料列被刪、物件漏刪的對帳清理
