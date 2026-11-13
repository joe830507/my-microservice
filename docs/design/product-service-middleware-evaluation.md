# product-service 中介軟體評估

> 評估日期：2026-10-28
> 目標情境：大流量、多場景（多前端、多服務協作）
> 性質：原始評估與導入取捨；下方「現況」是評估時的基準。Redis 已導入，後續 Kafka、Elasticsearch 等決策與進度見[中介軟體路線圖](../implementation-plans/middleware/middleware-roadmap.md)。

## 現況

> 2026-11-13 文件整理：暫緩／後續項目的現況、前提與影響範圍統一見[集中清單](../implementation-plans/deferred-items.md)；本處原始說明與歷史紀錄保留。


| 項目 | 現況 | 大流量下的問題 |
|---|---|---|
| 資料庫 | 單一 PostgreSQL | 所有讀取都打到 DB |
| 關鍵字搜尋 | `lower(name) LIKE '%kw%'`（`ProductServiceImpl#toSpecification`） | 前置萬用字元無法走 B-tree 索引，全表掃描；無中文斷詞、無相關性排序 |
| 商品圖片 | `ProductImage.url` 只存網址 | 圖片儲存與傳輸方式未定 |
| 服務間通訊 | 無 | 資料異動無法通知其他系統 |

---

## 1. 分散式快取：Redis（優先度：最高）

商品服務屬於「讀多寫少」，快取效益最大。

### 適用對象

| 對象 | 原因 | 建議做法 |
|---|---|---|
| 分類樹 `getCategoriesTree` | 幾乎不變、每頁都會用 | 兩層快取：本機 Caffeine + Redis |
| 商品詳情 `getProductById` / `getProductBySku` | 熱門商品被集中讀取 | Cache-aside，key 用 id（SKU 另建 sku → id 對照） |
| 批次查詢 `getProductsByIds` | order-service 會頻繁呼叫 | Redis `MGET` 批次讀取，未命中的部分再查 DB |

### 必須處理的三個問題

| 問題 | 情境 | 對策 |
|---|---|---|
| 快取穿透 | 一直查不存在的 id，每次都打到 DB | 空值也快取（短 TTL）或加 Bloom filter |
| 快取擊穿 | 熱門 key 過期的瞬間，大量請求同時打到 DB | 互斥鎖重建，或採用邏輯過期 |
| 快取雪崩 | 大量 key 同時過期 | TTL 加隨機值 |

### 其他

- `CommonEntity` 已經有 `@Version`，可以用來做 ETag，也能判斷快取是否為最新版本
- 寫入時採「先更新 DB，再刪除快取」；多實例下本機快取的失效需要靠 MQ 廣播（見第 3 點）
- Redis 之後也是 Gateway 限流、分散式鎖的基礎

### 導入進度（2026-11-04）

| 項目 | 狀態 | 位置 / 備註 |
|---|---|---|
| 分類樹快取 | 已完成 | `CategoryTreeCache`，TTL 1h + 隨機秒數 |
| 商品詳情 / SKU 快取 | 已完成 | `ProductDetailCache`，SKU 只存 sku → id 對照 |
| 批次查詢 `getProductsByIds` | 已完成 | MGET 逐筆讀 `productDetail`，與單筆查詢共用同一份快取 |
| 快取穿透 | 已完成 | 查不到時快取 null，TTL 90 秒 |
| 快取雪崩 | 已完成 | TTL 加隨機值 |
| 寫入一致性 | 已完成 | 先寫 DB，commit 後才刪快取 |
| Redis 故障降級 | 已完成 | `REJECT_COMMANDS` + `CacheErrorHandler`，直接查 DB |
| 快取擊穿 | 延後 | 目前規模可接受；要做時自行實作 per-key 鎖，最後用 `put` 寫入 |
| Caffeine 本機快取 | 延後 | 多實例失效需 MQ 廣播，等 Kafka 導入後再做 |
| Bloom filter、ETag | 暫不排入（2026-11-13） | 非必要；改列[路線圖「暫不排入」](../implementation-plans/middleware/middleware-roadmap.md#暫不排入記錄理由) |

### 踩過的坑

- **`@Cacheable(sync = true)` 會讓依 value 計算的 TTL 失效**：sync 走 `Cache#get(key, Callable)`，`RedisCache` 在載入前就計算 TTL，TTL function 拿到的 value 永遠是 null，所有資料都被當成「查不到」只存 90 秒
- **Lettuce 下 `RedisCacheWriter` 預設非同步寫入**：`put` / `evict` 回傳時指令可能還沒執行，「commit 後刪快取」失去保證，舊資料的 `put` 也可能蓋在 `evict` 之後；`RedisConfig` 已改用 `immediateWrites()`

---

## 2. 全文檢索：Elasticsearch / OpenSearch

### 現在的寫法不夠用的地方

- 效能：`LIKE '%kw%'` 用不到索引，資料量一大就是全表掃描
- 功能：缺少中文斷詞、相關性排序、聚合篩選（依品牌或價格區間做 facet）、錯字容錯、搜尋建議

### 兩階段做法

1. **過渡期**：PostgreSQL `pg_trgm` 擴充 + GIN 索引
   - 讓 `LIKE '%kw%'` 能走索引，改動最小
   - Postgres 內建全文檢索對中文支援很差，需要另外裝 zhparser 或 pg_jieba
2. **正式版**：Elasticsearch + 中文分詞器（例如 IK）
   - 商品列表、搜尋頁改查 ES
   - 商品詳情、後台寫入仍以 PostgreSQL 為準（source of truth）

### 前置條件

ES 的資料同步要靠訊息佇列（見第 3 點），**先有 MQ 再上 ES**。

---

## 3. 訊息佇列：Kafka 或 RabbitMQ（關鍵前置元件）

### 為什麼需要

商品一有異動，好幾個地方都要知道：

- ES 索引要更新
- 多台實例的本機快取要失效
- 其他服務（例如 order-service）手上的商品資料要更新

### 雙寫問題

在 Service 裡「寫 DB → 寫 ES → 刪 Redis」，中間任何一步失敗，資料就不一致。

### 解法

| 方案 | 說明 | 優缺點 |
|---|---|---|
| Transactional Outbox | 在同一個 DB 交易裡把事件寫進 `outbox` 表，再由另一個程序發送到 MQ | 應用程式自己控制事件格式；要多寫一個發送程序 |
| CDC（Debezium） | 讀取 PostgreSQL WAL，直接轉成 Kafka 事件 | 不用改應用程式碼；要多維運 Kafka Connect，事件格式貼近資料表結構 |

**建議**：大流量、多場景的目標下選 Kafka，搭配 Outbox 或 Debezium。

---

## 4. 物件儲存 + CDN

- 圖片流量通常比 API 還大，不應該經過應用服務
- 圖片放在 S3 / MinIO（本機開發用 MinIO），前面掛 CDN
- 上傳流程：後端發 presigned URL → 前端直接上傳到物件儲存 → 再呼叫 API 寫入 `ProductImage`
- 上傳流程最好早點定案，避免之後改 API
- 詳細設計見 [圖片上傳設計](../implementation-plans/backend/product-image-upload-implementation-plan.md#image-design)

---

## 5. 平台層（非本服務專屬，但大流量必備）

| 類別 | 元件 | 備註 |
|---|---|---|
| 限流 | API Gateway + Redis | 對應現有的 api-gateway placeholder |
| 指標監控 | Prometheus + Grafana | actuator 開放 `prometheus` endpoint |
| 分散式追蹤 | OpenTelemetry（+ Tempo / Jaeger） | 服務一多，沒有追蹤很難除錯 |
| Log 收集 | Loki 或 ELK | 搭配 trace id 串 log |
| DB 擴展 | Read replica、PgBouncer | 讀寫分離、連線池 |
| 服務發現 / 設定中心 | K8s 原生 | 已定案（2026-11-09），見 [microservice-architecture.md](../architecture/microservice-architecture.md) |
| 熔斷 / 重試 | Resilience4j | 函式庫，不需要另外架服務 |

---

## 服務邊界提醒

- **庫存不要放在 product-service**，應該獨立成 inventory-service
- 秒殺扣庫存通常靠 Redis 原子操作（Lua 腳本）+ MQ 削峰，和商品資料的讀寫模式完全不同
- 整體服務切分、API 與服務互動見 [microservice-architecture.md](../architecture/microservice-architecture.md)

---

## 建議導入順序

| 順序 | 元件 | 原因 |
|---|---|---|
| 1 | Redis | 見效最快，也是限流、鎖的基礎 |
| 2 | MinIO | 早點定案上傳流程，避免回頭改 API |
| 3 | Kafka + Outbox | 建立事件機制，後續同步都靠它 |
| 4 | Elasticsearch | 透過 MQ 同步，接上搜尋 |
| 5 | 可觀測性 | 最好在第 2、3 個服務出現時就加上 |

以上元件都可以先加進 `infra/docker-compose.yml`，在本機跑起來驗證。
